# release 构建的 R8 规则。
#
# 大部分依赖（Compose、OkHttp、ZXing、Miuix、kotlinx.coroutines）自带 consumer
# 规则，这里只补三类：kotlinx.serialization 的 serializer 入口、少数反射/动态
# 加载路径，以及压制第三方可选依赖的 missing-class 警告。

# ---------------- Kotlinx Serialization ----------------
# R8 8+ 已内置 @Serializable 支持，但保留 serializer() 入口可以吃掉两类回归：
# 1) 跨版本/跨 R8 行为差异导致的 "serializer not found"；
# 2) 只通过反射（Json.decodeFromString<T> 的 reified 路径被内联后）访问的情况。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class **$$serializer {
    *** serializer(...);
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------- OkHttp / Okio ----------------
# 可选 TLS 提供方（Conscrypt/BouncyCastle/OpenJSSE）在运行时才探测，编译期缺失属正常。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------- kotlinx.coroutines ----------------
# DebugProbes 只在调试器下使用，release 不需要。
-dontwarn kotlinx.coroutines.**

# ---------------- ZXing (zxing-android-embedded) ----------------
# CaptureActivity 由 Manifest 声明，AAPT 会自动 keep；这里额外兜底扫码结果的
# 回调接口，避免 R8 把仅由库内部调用的接口合并掉。
-keep interface com.journeyapps.barcodescanner.BarcodeCallback { *; }

