# 只保留必要的东西；其余交给 R8 默认规则。
# OkHttp 4.x 自带的 consumer proguard 规则已经够用，这里只兜底几处反射入口。

# BuildConfig 常量被 R8 内联后仍要能被读到（避免误删）
-keep class net.mosslive.notebook.BuildConfig { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
