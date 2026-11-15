# JVM harness for the native LLM bridge

Exercises `app/src/main/cpp/outpost_llm.cpp` on a desktop Linux machine through real JNI calls,
using the same class and method names the app uses (`com.conza.outpost.ai.NativeLlm`).

```bash
cmake -S ../../app/src/main/cpp -B host -G Ninja -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=ON
cmake --build host -j
# edit the model paths in Main.java (a small chat GGUF and nomic-embed-text), then:
javac -d out Main.java com/conza/outpost/ai/*.java && java -cp out Main
```

It checks: streaming generation, KV-cache reuse on a follow-up turn, stopping from the
callback, the "prompt too long" code (-2), and embedding similarity.

The host build also produces `core_plan_test`, which checks the fast-core selection in
`app/src/main/cpp/core_plan.h` against synthetic phone layouts (no model or JNI needed):

```bash
./host/core_plan_test
```
