// Outpost's bridge to llama.cpp: chat generation with streaming callbacks, and text embeddings.
//
// Design notes
// - Each loaded model is an opaque handle (Engine*), so a chat model and an embedding
//   model can be resident together.
// - Kotlin sends the whole conversation every turn. The chat template is applied here,
//   and the KV cache is reused for the longest common token prefix with the previous
//   turn, so follow-up turns only process the new tokens.
// - All calls for one handle must come from one thread; Kotlin guarantees that with a
//   single-threaded dispatcher. stop() is safe from any thread.

#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#else
// Host build (used by the JVM test harness): log to stderr.
#include <cstdio>
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) (fprintf(stderr, "[%s] ", tag), fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>
#if defined(__linux__)
#include <sched.h>
#endif

#include "chat.h"
#include "core_plan.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"
#include "ggml-backend.h"
#if defined(__x86_64__) || defined(__i386__)
#include <cpuid.h>
#endif

#define TAG "OutpostLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int BATCH = 512;

// Which CPU cores run the model, and how many threads (see plan_threads).
struct ThreadPlan {
    int n_threads = 0;
    std::vector<int> cores; // CPUs to run on; empty leaves the scheduler free to choose
    const char *source = "none"; // which signal told the cores apart (for the log)
};

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    ggml_threadpool_t pool = nullptr;
    common_chat_templates_ptr templates;
    std::vector<llama_token> cached; // tokens currently in the KV cache (seq 0)
    bool embedding = false;
    int n_ctx = 0;
    std::atomic<bool> stop{false};
    ThreadPlan plan;
};

void log_cb(ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
             : ANDROID_LOG_DEBUG;
    __android_log_print(prio, TAG, "%s", text);
}

// ---- Thread placement ----
// Phones mix fast and slow cores, and ggml synchronises its threads at every op, so one thread
// on a little core holds the whole model to that core's pace. core_plan.h picks the fast cores
// from the scheduler's cpu_capacity, else the cores' MIDR part numbers, else cpufreq alone (the
// old 80%-of-top-clock rule, which misfires on mid-range chips whose little cores clock close to
// the big ones). Without any of those (emulators, the JVM harness) or on a uniform CPU, fall
// back to a plain count.
long read_long(const char *path) {
    std::ifstream f(path);
    long v = 0;
    return (f >> v) ? v : 0;
}

long read_cpu_long(int cpu, const char *leaf) {
    char path[128];
    snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/%s", cpu, leaf);
    return read_long(path);
}

uint32_t read_cpu_midr(int cpu) {
    char path[128];
    snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/regs/identification/midr_el1", cpu);
    std::ifstream f(path);
    unsigned long long v = 0;
    return (f >> std::hex >> v) ? (uint32_t) v : 0;
}

// Older kernels lack the midr_el1 sysfs file; /proc/cpuinfo carries the same fields per core.
void fill_midr_from_cpuinfo(std::vector<outpost::CoreInfo> &cores) {
    std::ifstream f("/proc/cpuinfo");
    if (!f) return;
    std::string line;
    int cpu = -1;
    uint32_t impl = 0, part = 0;
    auto flush = [&]() {
        if (cpu < 0 || !impl || !part) return;
        for (auto &c : cores) if (c.cpu == cpu && c.midr == 0) c.midr = (impl << 24) | (part << 4);
    };
    while (std::getline(f, line)) {
        auto colon = line.find(':');
        if (colon == std::string::npos) continue;
        std::string key = line.substr(0, colon), val = line.substr(colon + 1);
        key.erase(key.find_last_not_of(" \t") + 1);
        if (key == "processor") { flush(); cpu = (int) strtol(val.c_str(), nullptr, 10); impl = part = 0; }
        else if (key == "CPU implementer") impl = (uint32_t) strtoul(val.c_str(), nullptr, 0);
        else if (key == "CPU part") part = (uint32_t) strtoul(val.c_str(), nullptr, 0);
    }
    flush();
}

std::vector<outpost::CoreInfo> probe_cores() {
    long total = sysconf(_SC_NPROCESSORS_CONF);
    if (total < 1) total = 1;
    std::vector<outpost::CoreInfo> cores;
    for (int cpu = 0; cpu < total && cpu < GGML_MAX_N_THREADS; cpu++) {
        outpost::CoreInfo c;
        c.cpu = cpu;
        c.capacity = read_cpu_long(cpu, "cpu_capacity");
        c.max_khz = read_cpu_long(cpu, "cpufreq/cpuinfo_max_freq");
        c.midr = read_cpu_midr(cpu);
        cores.push_back(c);
    }
    if (std::any_of(cores.begin(), cores.end(), [](const outpost::CoreInfo &c) { return c.midr == 0; }))
        fill_midr_from_cpuinfo(cores);
    return cores;
}

ThreadPlan plan_threads(int requested) {
    ThreadPlan plan;
    std::vector<outpost::CoreInfo> cores = probe_cores();
    const long total = (long) cores.size();
    outpost::CoreChoice choice = outpost::choose_fast_cores(cores);
    if (!choice.fast.empty()) {
        plan.cores = choice.fast;
        plan.source = outpost::core_source_name(choice.source);
        // Two threads at least: a lone prime core takes the next-best core along.
        if (plan.cores.size() < 2) {
            for (const auto &c : cores) {
                if (std::find(plan.cores.begin(), plan.cores.end(), c.cpu) == plan.cores.end()) { plan.cores.push_back(c.cpu); break; }
            }
        }
        plan.n_threads = (int) std::max<size_t>(2, std::min<size_t>(6, plan.cores.size()));
    }
    // Uniform or unknown layout: leave headroom for the UI.
    if (plan.n_threads == 0) plan.n_threads = (int) std::max(2L, std::min(6L, total - 2));
    if (requested > 0) plan.n_threads = requested;
    return plan;
}

// Pins the calling thread to the plan's cores. ggml applies the same mask to its own workers;
// this covers the thread that drives them (Kotlin's single-thread dispatcher), which may be a
// different OS thread from one call to the next.
void apply_affinity(const ThreadPlan &plan) {
#if defined(__linux__)
    if (plan.cores.empty()) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c : plan.cores) if (c < CPU_SETSIZE) CPU_SET(c, &set);
    if (sched_setaffinity(0, sizeof set, &set) != 0) LOGW("could not pin to the fast cores (errno %d)", errno);
#else
    (void) plan;
#endif
}

std::string describe(const ThreadPlan &plan) {
    std::string s = std::to_string(plan.n_threads) + " threads";
    if (!plan.cores.empty()) {
        s += " on cpu";
        for (size_t i = 0; i < plan.cores.size(); i++) s += (i ? "," : " ") + std::to_string(plan.cores[i]);
        s += std::string(" (by ") + plan.source + ")";
    }
    return s;
}

// The CPU backend is a dynamically loaded library (GGML_BACKEND_DL), so its threadpool
// functions are reached through the backend registry rather than linked directly.
using threadpool_new_fn = ggml_threadpool_t (*)(struct ggml_threadpool_params *);
using threadpool_free_fn = void (*)(ggml_threadpool_t);

template <typename F> F cpu_proc(const char *name) {
    ggml_backend_dev_t dev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    ggml_backend_reg_t reg = dev ? ggml_backend_dev_backend_reg(dev) : nullptr;
    return reg ? (F) ggml_backend_reg_get_proc_address(reg, name) : nullptr;
}

ggml_threadpool_t make_threadpool(const ThreadPlan &plan) {
    auto create = cpu_proc<threadpool_new_fn>("ggml_threadpool_new");
    if (!create) { LOGW("CPU backend has no threadpool; using ggml's default threads"); return nullptr; }
    struct ggml_threadpool_params tpp = ggml_threadpool_params_default(plan.n_threads);
    for (int c : plan.cores) if (c < GGML_MAX_N_THREADS) tpp.cpumask[c] = true;
    tpp.strict_cpu = false; // any fast core, not one core per thread
    return create(&tpp);
}

void free_threadpool(ggml_threadpool_t pool) {
    if (!pool) return;
    auto destroy = cpu_proc<threadpool_free_fn>("ggml_threadpool_free");
    if (destroy) destroy(pool);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java's NewStringUTF wants modified UTF-8; build the string from UTF-16 instead to be safe
// with 4-byte characters (emoji) that models love to emit.
jstring to_jstring(JNIEnv *env, const std::string &s) {
    std::u16string u16;
    u16.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = s[i];
        uint32_t cp; int len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { i++; continue; }
        if (i + len > s.size()) break;
        for (int k = 1; k < len; k++) cp = (cp << 6) | (s[i + k] & 0x3F);
        i += len;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u16.push_back((char16_t) (0xD800 + (cp >> 10)));
            u16.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
        } else {
            u16.push_back((char16_t) cp);
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(u16.data()), (jsize) u16.size());
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 character.
size_t complete_utf8_prefix(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = s[i - 1];
        if ((c & 0xC0) != 0x80) { // lead byte
            int need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (n - (i - 1)) >= (size_t) need ? n : i - 1;
        }
        i--; back++;
    }
    return n;
}

int decode_tokens(Engine *e, common_batch &batch, const std::vector<llama_token> &tokens,
                  size_t from, bool want_last_logits) {
    for (size_t i = from; i < tokens.size(); i += BATCH) {
        size_t n = std::min((size_t) BATCH, tokens.size() - i);
        batch.clear();
        for (size_t j = 0; j < n; j++) {
            bool last = want_last_logits && (i + j == tokens.size() - 1);
            batch.add(tokens[i + j], (llama_pos) (i + j), 0, last);
        }
        int rc = llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) { LOGE("decode failed: %d", rc); return rc; }
    }
    return 0;
}


// ---- CPU backend selection ----
// ggml picks the best CPU variant from CPUID alone. Under some hypervisors (Android emulators on
// Windows/Hyper-V), CPUID advertises AVX/AVX-512 while the OS hasn't enabled that register state,
// and the "best" variant crashes with SIGILL. On x86 we check XCR0 ourselves and, if the two
// disagree, load a variant the OS actually supports.
#if defined(__x86_64__) || defined(__i386__)
static uint64_t read_xcr0() {
    uint32_t eax, edx;
    __asm__ volatile("xgetbv" : "=a"(eax), "=d"(edx) : "c"(0));
    return ((uint64_t) edx << 32) | eax;
}
std::string pick_x86_variant_if_needed() {
    unsigned a, b, c, d;
    if (!__get_cpuid(1, &a, &b, &c, &d)) return "x64";
    bool sse42 = c & (1u << 20), fma = c & (1u << 12), f16c = c & (1u << 29), avx = c & (1u << 28), osxsave = c & (1u << 27);
    unsigned b7 = 0, c7 = 0, d7 = 0, a7 = 0;
    __get_cpuid_count(7, 0, &a7, &b7, &c7, &d7);
    bool avx2 = b7 & (1u << 5), avx512f = b7 & (1u << 16);
    uint64_t xcr0 = osxsave ? read_xcr0() : 0;
    bool avx_os = osxsave && (xcr0 & 0x6) == 0x6;
    bool avx512_os = avx_os && (xcr0 & 0xE0) == 0xE0;
    bool mismatch = (avx && !avx_os) || (avx512f && !avx512_os);
    if (!mismatch) return ""; // CPUID is trustworthy: let ggml choose
    LOGW("CPU advertises AVX%s but the OS hasn't enabled it; choosing a safe CPU backend", avx512f ? "-512" : "");
    if (avx_os && avx2 && fma && f16c) return "haswell";
    if (avx_os) return "sandybridge";
    if (sse42) return "sse42";
    return "x64";
}
std::string baseline_variant() { return "x64"; }
#else
std::string pick_x86_variant_if_needed() { return ""; }
std::string baseline_variant() { return "android_armv8.0_1"; }
#endif

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeInit(JNIEnv *env, jclass, jstring nativeLibDir, jboolean safeMode) {
    llama_log_set(log_cb, nullptr);
    std::string dir = jstr(env, nativeLibDir);
    std::string variant = safeMode ? baseline_variant() : pick_x86_variant_if_needed();
    if (!variant.empty()) {
        // Load exactly one CPU backend (safe mode, or a hypervisor that misreports CPU features).
        std::string path = dir + "/libggml-cpu-" + variant + ".so";
        LOGI("loading single CPU backend %s", path.c_str());
        if (!ggml_backend_load(path.c_str())) {
            LOGW("could not load %s; falling back to automatic selection", path.c_str());
            ggml_backend_load_all_from_path(dir.c_str());
        }
    } else {
        ggml_backend_load_all_from_path(dir.c_str());
    }
    llama_backend_init();
    LOGI("backends ready: %s", llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeSystemInfo(JNIEnv *env, jclass) {
    return to_jstring(env, llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nCtx,
                                               jint nThreads, jboolean embedding) {
    std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    // Default load mode memory-maps the weights, so they page in from the file instead of being copied.
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("failed to load %s", path.c_str()); return 0; }

    llama_context_params cp = llama_context_default_params();
    int trained = llama_model_n_ctx_train(model);
    int ctx = nCtx > 0 ? nCtx : 4096;
    if (trained > 0) ctx = std::min(ctx, trained);
    cp.n_ctx = ctx;
    const ThreadPlan plan = plan_threads(nThreads);
    cp.n_threads = cp.n_threads_batch = plan.n_threads;
    if (embedding) {
        cp.embeddings = true;
        cp.n_batch = cp.n_ubatch = ctx; // non-causal encoders need the whole input in one ubatch
    } else {
        cp.n_batch = cp.n_ubatch = BATCH;
    }
    llama_context *lctx = llama_init_from_model(model, cp);
    if (!lctx) { llama_model_free(model); LOGE("failed to create context"); return 0; }

    auto *e = new Engine();
    e->model = model;
    e->ctx = lctx;
    e->embedding = embedding;
    e->n_ctx = ctx;
    e->plan = plan;
    apply_affinity(plan); // the workers inherit this thread's mask as well as ggml's own cpumask
    e->pool = make_threadpool(plan);
    if (e->pool) llama_attach_threadpool(lctx, e->pool, e->pool);
    if (!embedding) e->templates = common_chat_templates_init(model, "");
    LOGI("loaded %s (ctx=%d, %s, embedding=%d)", path.c_str(), ctx, describe(plan).c_str(), (int) embedding);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    e->templates.reset();
    if (e->ctx && e->pool) llama_detach_threadpool(e->ctx);
    if (e->ctx) llama_free(e->ctx);
    free_threadpool(e->pool);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeStop(JNIEnv *, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e) e->stop = true;
}

JNIEXPORT jint JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    return e ? e->n_ctx : 0;
}

JNIEXPORT jint JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeCountTokens(JNIEnv *env, jclass, jlong handle, jstring jtext) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return -1;
    return (jint) common_tokenize(e->ctx, jstr(env, jtext), false, false).size();
}

/**
 * Generate an assistant reply for the given conversation.
 * Returns 0 on success, -1 bad handle, -2 prompt too long for the context, -3 decode error.
 * The callback's `onToken(String): Boolean` receives text pieces; returning false stops.
 */
JNIEXPORT jint JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeGenerate(JNIEnv *env, jclass, jlong handle,
                                                   jobjectArray roles, jobjectArray contents,
                                                   jint maxTokens, jfloat temperature, jfloat topP,
                                                   jobject callback) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e || e->embedding) return -1;
    e->stop = false;
    apply_affinity(e->plan);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");

    // 1. Render the conversation with the model's own chat template.
    common_chat_templates_inputs inputs;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = jstr(env, (jstring) env->GetObjectArrayElement(roles, i));
        m.content = jstr(env, (jstring) env->GetObjectArrayElement(contents, i));
        inputs.messages.push_back(m);
    }
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = false; // keep small reasoning models (Qwen3) answering directly
    std::string prompt;
    try {
        inputs.use_jinja = true;
        prompt = common_chat_templates_apply(e->templates.get(), inputs).prompt.str();
    } catch (const std::exception &ex) {
        LOGW("jinja template failed (%s); falling back to legacy template", ex.what());
        try {
            inputs.use_jinja = false;
            prompt = common_chat_templates_apply(e->templates.get(), inputs).prompt.str();
        } catch (const std::exception &ex2) {
            LOGE("chat template failed: %s", ex2.what());
            return -3;
        }
    }

    std::vector<llama_token> tokens = common_tokenize(e->ctx, prompt, true, true);
    const int reserve = std::max(64, std::min((int) maxTokens, e->n_ctx / 2));
    if ((int) tokens.size() + reserve > e->n_ctx) {
        LOGW("prompt too long: %d tokens (ctx %d)", (int) tokens.size(), e->n_ctx);
        return -2;
    }

    // 2. Reuse the KV cache for the shared prefix with the previous turn.
    size_t keep = 0;
    while (keep < e->cached.size() && keep < tokens.size() && e->cached[keep] == tokens[keep]) keep++;
    if (keep == tokens.size()) keep--; // must decode at least one token to get logits
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
        llama_memory_clear(mem, false);
        keep = 0;
    }
    e->cached.resize(keep);

    common_batch batch(e->ctx);
    if (decode_tokens(e, batch, tokens, keep, true) != 0) {
        llama_memory_clear(mem, false);
        e->cached.clear();
        return -3;
    }
    e->cached = tokens;

    // 3. Sample until end-of-generation, the token budget, the context limit, or stop().
    common_params_sampling sp;
    sp.temp = temperature;
    sp.top_p = topP;
    common_sampler *smpl = common_sampler_init(e->model, sp);
    const llama_vocab *vocab = llama_model_get_vocab(e->model);

    std::string pending; // bytes not yet forming complete UTF-8
    int rc = 0;
    for (int i = 0; i < maxTokens && !e->stop; i++) {
        if ((int) e->cached.size() >= e->n_ctx - 1) break;
        llama_token tok = common_sampler_sample(smpl, e->ctx, -1);
        common_sampler_accept(smpl, tok, true);
        if (llama_vocab_is_eog(vocab, tok)) break;

        pending += common_token_to_piece(e->ctx, tok, false);
        size_t ok = complete_utf8_prefix(pending);
        if (ok > 0) {
            jstring piece = to_jstring(env, pending.substr(0, ok));
            pending.erase(0, ok);
            jboolean cont = env->CallBooleanMethod(callback, onToken, piece);
            env->DeleteLocalRef(piece);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            if (!cont) break;
        }

        batch.clear();
        batch.add(tok, (llama_pos) e->cached.size(), 0, true);
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { rc = -3; break; }
        e->cached.push_back(tok);
    }
    common_sampler_free(smpl);
    return rc;
}

/** Mean/CLS-pooled, L2-normalized embedding of `text`, or null on failure. */
JNIEXPORT jfloatArray JNICALL
Java_com_conza_outpost_ai_NativeLlm_nativeEmbed(JNIEnv *env, jclass, jlong handle, jstring jtext) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e || !e->embedding) return nullptr;
    apply_affinity(e->plan);
    std::vector<llama_token> tokens = common_tokenize(e->ctx, jstr(env, jtext), true, false);
    if (tokens.empty()) return nullptr;
    if ((int) tokens.size() > e->n_ctx) tokens.resize(e->n_ctx);

    llama_memory_t mem = llama_get_memory(e->ctx);
    if (mem) llama_memory_clear(mem, true);

    common_batch batch(e->ctx);
    for (size_t i = 0; i < tokens.size(); i++) batch.add(tokens[i], (llama_pos) i, 0, true);
    if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) < 0) return nullptr;

    const int dim = llama_model_n_embd(e->model);
    const float *v = nullptr;
    if (llama_pooling_type(e->ctx) != LLAMA_POOLING_TYPE_NONE) v = llama_get_embeddings_seq(e->ctx, 0);
    if (!v) v = llama_get_embeddings_ith(e->ctx, (int32_t) tokens.size() - 1);
    if (!v) return nullptr;

    std::vector<float> out(dim);
    common_embd_normalize(v, out.data(), dim, 2);
    jfloatArray arr = env->NewFloatArray(dim);
    env->SetFloatArrayRegion(arr, 0, dim, out.data());
    return arr;
}

} // extern "C"
