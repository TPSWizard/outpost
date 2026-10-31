package com.conza.outpost.ai;
public class NativeLlm {
  public static native void nativeInit(String dir);
  public static native String nativeSystemInfo();
  public static native long nativeLoad(String path, int nCtx, int nThreads, boolean embedding);
  public static native void nativeFree(long h);
  public static native void nativeStop(long h);
  public static native int nativeContextSize(long h);
  public static native int nativeCountTokens(long h, String t);
  public static native int nativeGenerate(long h, String[] roles, String[] contents, int maxTokens, float temp, float topP, TokenCallback cb);
  public static native float[] nativeEmbed(long h, String text);
}
