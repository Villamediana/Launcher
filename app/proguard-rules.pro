# MediaPipe GenAI reaches into these classes from JNI.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aura.launcher.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Methods a page calls through addJavascriptInterface (the score bridge).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
