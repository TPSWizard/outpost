# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.conza.outpost.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# MapLibre native bridge
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
# libzim JNI wrappers: native code reads `nativeHandle` fields and builds wrappers by class name
-keep class org.kiwix.** { *; }
-dontwarn org.kiwix.**
-dontwarn com.getkeepsafe.relinker.**
# llama.cpp JNI bridge: native code calls TokenCallback.onToken by name
-keep class com.conza.outpost.ai.NativeLlm { *; }
-keep interface com.conza.outpost.ai.TokenCallback { *; }
-keep class * implements com.conza.outpost.ai.TokenCallback { *; }
-dontwarn org.commonmark.**
# PDFBox-Android: optional JPEG2000 decoder and AWT-only paths aren't on Android
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
