# release 构建的 R8 规则。
#
# 大部分依赖（Compose、OkHttp、ML Kit、Miuix、kotlinx.coroutines）自带 consumer
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

# ---------------- ML Kit（barcode-scanning，内置模型） ----------------
# ML Kit 的 AAR 自带 consumer 规则（模型入口与 native 绑定都在里面），业务侧不用
# 再 keep；它引用的 Play Services 可选类在离线打包时并不存在，压掉这些告警即可。
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

