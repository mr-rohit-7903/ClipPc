# ProGuard rules for ClipBridge
# https://developer.android.com/studio/build/shrink-code

# Keep OkHttp and Okio for WebSocket connectivity
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep OkHttp platform-specific classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
