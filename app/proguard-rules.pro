# ===== whisper.cpp JNI =====
# C 侧按符号名查找 native 方法：类名/方法名被混淆会直接 UnsatisfiedLinkError
-keepclasseswithmembernames class * {
    native <methods>;
}
# 桥接类整体保留（external fun 声明 + JNI 实现类）
-keep class com.galigalimusic.whisper.** { *; }

# ===== Room =====
# 实体字段名即列名（KSP 生成代码按名引用），保留防反射场景翻车
-keep class com.galigalimusic.data.local.** { *; }

# ===== Media3 =====
# DownloadService/DownloadNotification 经反射构造
-keep class androidx.media3.exoplayer.offline.** { *; }
-dontwarn androidx.media3.**

# ===== OkHttp / Okio =====
# okhttp4 自带 consumer rules；这几条 internal 平台类在某些环境触发警告
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ===== 通用 =====
# 保留行号，崩溃栈可读（release 也方便定位）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
