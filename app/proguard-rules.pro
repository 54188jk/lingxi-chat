# R8 / ProGuard 规则
# 目标：混淆业务代码，但保住反射、JNI、序列化与系统组件需要的部分。

# ---- OkHttp / Okio：大量反射与可选依赖 ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }
-keepclasseswithmembers,includedescriptorclasses class * {
    @okhttp3.* <methods>;
}
-dontwarn javax.annotation.**
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.Metadata { *; }

# ---- 自定义 View：系统按 XML 反射构造（View 的三参构造器）----
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---- App 自身数据模型：被 JSON 序列化/反序列化 ----
-keep class com.lingxi.chat.data.** { *; }

# ---- 保留行号，便于崩溃定位 ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# ---- Shizuku：AIDL 代理 + 隐藏 API 反射，整包保住 ----
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-dontwarn moe.shizuku.**
-dontwarn rikka.shizuku.**

# ---- 无障碍服务：系统按类名绑定，方法由框架回调 ----
-keep class com.lingxi.chat.control.ControlService { *; }

# ---- MediaPipe 手部推理：C++ 侧按类名/方法名反射回调 Java，整包不能改名 ----
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.protobuf.** { *; }
# 这条不能删：MediaPipe 的 Graph 在静态初始化里用 FluentLogger.forEnclosingClass()，
# 而 flogger 靠「栈里有没有自己这一帧」找调用者。R8 把 flogger 改名后它找不到，
# 进特效页就抛 ExceptionInInitializerError（用户看到的是一句「这份文件读不起来」）。
# 本机用 r8 8.7.18 在 JVM 上复现并验证：加这条立即恢复，关掉 full mode 或 assumenosideeffects 都无效。
-keep class com.google.common.flogger.** { *; }
-keep class com.google.common.flogger.backend.system.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.common.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.ar.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# 传递依赖里一堆只在服务端用的可选实现，Android 上没有，别让 R8 因为找不到而停住
-dontwarn io.opentelemetry.**
-dontwarn io.perfmark.**
-dontwarn net.jodah.typetools.**
-dontwarn com.google.api.**
-dontwarn com.google.auth.**
