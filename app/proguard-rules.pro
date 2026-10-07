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
