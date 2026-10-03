# Keep Gemini SDK models and kotlinx.serialization metadata used by the generativeai client
-keep class com.google.ai.client.generativeai.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn com.google.ai.client.generativeai.**
