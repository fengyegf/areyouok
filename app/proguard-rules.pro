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
# ML Kit 的 AAR 自带一部分 consumer 规则（native 绑定等），它引用的 Play Services
# 可选类在离线打包时并不存在，压掉这些告警即可。
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# 组件注册必须显式 keep：ML Kit 用 Firebase Components 的反射发现机制，从 Manifest
# 元数据里的「类名字符串」实例化 ComponentRegistrar（见
# com.google.firebase.components.ComponentDiscovery.instantiate(String)），再通过
# getComponents() 注册真正的实现类。R8 看不到这条字符串引用，会把这些类当成无用代码：
#   - 注册实现的 Scanner 类被整个删掉（zzg/zzh/zzl…），
#   - Registrar 的无参构造被裁掉，
# 结果是 MlKitContext.get(zzg.class) 返回 null，首次 BarcodeScanning.getClient() 就
# 抛 NPE —— 现象就是「release 包一打开扫一扫就闪退」（debug 不混淆所以正常）。
# 这里把注册相关类整包 keep，确保组件能被创建与注册。
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }

