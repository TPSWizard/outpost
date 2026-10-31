import com.conza.outpost.ai.*;
public class Main {
  static float cos(float[] a, float[] b){ float d=0,x=0,y=0; for(int i=0;i<a.length;i++){d+=a[i]*b[i];x+=a[i]*a[i];y+=b[i]*b[i];} return (float)(d/Math.sqrt(x*y)); }
  public static void main(String[] a) throws Exception {
    System.load(new java.io.File("host/liboutpost_llm.so").getAbsolutePath());
    NativeLlm.nativeInit("host/bin");
    long h = NativeLlm.nativeLoad("models/qwen0.5.gguf", 2048, 2, false);
    System.out.println("handle="+h+" ctx="+NativeLlm.nativeContextSize(h));
    StringBuilder sb = new StringBuilder();
    long t=System.currentTimeMillis();
    int rc = NativeLlm.nativeGenerate(h, new String[]{"system","user"}, new String[]{"You are concise.","Name three ways to purify water. One line each. Use an emoji 💧."}, 120, 0.2f, 0.95f, p -> { sb.append(p); return true; });
    System.out.println("rc="+rc+" ms="+(System.currentTimeMillis()-t)+"\n---\n"+sb+"\n---");
    // Follow-up turn: KV prefix reuse
    String ans = sb.toString(); sb.setLength(0); t=System.currentTimeMillis();
    if (a.length>0) { NativeLlm.nativeFree(h); h = NativeLlm.nativeLoad("models/qwen0.5.gguf", 2048, 2, false); }
    rc = NativeLlm.nativeGenerate(h, new String[]{"system","user","assistant","user"}, new String[]{"You are concise.","Name three ways to purify water. One line each. Use an emoji 💧.", ans, "Which is fastest?"}, 60, 0.0f, 0.95f, p -> { sb.append(p); return true; });
    System.out.println("rc2="+rc+" ms="+(System.currentTimeMillis()-t)+"\n---\n"+sb+"\n---");
    // Stop via callback
    sb.setLength(0); final int[] n={0};
    rc = NativeLlm.nativeGenerate(h, new String[]{"user"}, new String[]{"Count from 1 to 100."}, 200, 0.2f, 0.95f, p -> { sb.append(p); return ++n[0] < 5; });
    System.out.println("rc3="+rc+" pieces="+n[0]+" text="+sb);
    // Too long
    StringBuilder big=new StringBuilder(); for(int i=0;i<3000;i++) big.append("water ");
    rc = NativeLlm.nativeGenerate(h, new String[]{"user"}, new String[]{big.toString()}, 100, 0.2f, 0.95f, p -> true);
    System.out.println("rc_long="+rc);
    NativeLlm.nativeFree(h);
    long e = NativeLlm.nativeLoad("models/nomic.gguf", 2048, 2, true);
    float[] q = NativeLlm.nativeEmbed(e, "search_query: how to make water safe to drink");
    float[] d1 = NativeLlm.nativeEmbed(e, "search_document: Boiling water for one minute kills most pathogens.");
    float[] d2 = NativeLlm.nativeEmbed(e, "search_document: The stock market rose sharply on Tuesday.");
    System.out.println("dim="+q.length+" relevant="+cos(q,d1)+" irrelevant="+cos(q,d2));
    NativeLlm.nativeFree(e);
  }
}
