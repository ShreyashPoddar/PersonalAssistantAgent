# Keep Gemini SDK models and kotlinx.serialization metadata used by the generativeai client
-keep class com.google.ai.client.generativeai.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn com.google.ai.client.generativeai.**

# Native bridges are found by name through JNI/JNA — shrinking or renaming them crashes at runtime
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class net.zetetic.database.** { *; }
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
