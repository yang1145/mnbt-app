# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================================
# R8 full mode 已开启（gradle.properties: android.enableR8.fullMode=true）。
# 下面每一条 keep 都对应一处真实的反射 / ServiceLoader / JNI 调用点，
# 删任何一条都要先在真机上跑过 assembleRelease 再决定。
# ============================================================================

# 泛型签名与注解：kotlinx.serialization、Room、MINA SSHD 的 NamedFactory 都依赖
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations, AnnotationDefault
-keepattributes *Annotation*

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.mnbt.app.**$$serializer { *; }
-keepclassmembers class com.mnbt.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.mnbt.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Apache MINA SSHD
# 客户端工厂（Cipher / Mac / Signature / Compression / KeyExchange / Random /
# IoServiceFactory）全部通过 META-INF/services 里的 ServiceLoader 装配，
# R8 full mode 看不到调用点，会直接删掉。
# ---------------------------------------------------------------------------
-keep class * implements org.apache.sshd.common.NamedFactory { <init>(); }
-keep class * implements org.apache.sshd.common.NamedResource { <init>(); }
-keep class * implements org.apache.sshd.common.io.IoServiceFactoryFactory { <init>(); }
-keep class org.apache.sshd.common.io.nio2.** { *; }
# SecurityUtils 用 Class.forName 探测注册器，命中不了就静默降级成无 BC
-keep class org.apache.sshd.common.util.security.** { *; }
-keep class org.apache.sshd.common.util.security.bouncycastle.** { *; }
-keep class org.apache.sshd.common.util.security.eddsa.** { *; }

# MINA SSHD 的可选依赖：SFTP / LDAP / Spring / AWT / JNDI / Kerberos 我们都不用
-dontwarn org.apache.sshd.**
-dontwarn java.nio.file.**
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn javax.security.**
-dontwarn org.ietf.jgss.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**

# ---------------------------------------------------------------------------
# BouncyCastle
# Provider 用字符串类名注册算法实现，几乎所有 jcajce 类都是反射进去的，
# 没有可靠的精简 keep 集合。代价约 6MB dex，这是体积预算里最大的一块。
# ---------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------------------
# sora-editor + language-textmate
# tm4e 的语法注册与 jcodings 的编码表都是反射装配。
# ---------------------------------------------------------------------------
-keep class org.eclipse.tm4e.** { *; }
-keep class org.jcodings.** { *; }
-keep class org.joni.** { *; }
-dontwarn org.eclipse.tm4e.**
-dontwarn org.jcodings.**
-dontwarn org.joni.**

# ---------------------------------------------------------------------------
# OkHttp / Okio
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# 逃生舱：如果 release 包在真机上出现 ClassNotFoundException /
# NoSuchAlgorithmException（几乎只会来自 MINA SSHD 的工厂装配），
# 先打开下面这一条确认是不是 keep 规则漏了，再回去补精确规则。
# ---------------------------------------------------------------------------
#-keep class org.apache.sshd.** { *; }
