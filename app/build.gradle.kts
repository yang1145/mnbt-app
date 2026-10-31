plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mnbt.app"
    compileSdk = 35
    // AGP 8.8 默认要 Build Tools 35.0.0；钉到本机已装的 35.0.1，避免构建期联网下载 SDK 组件
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.mnbt.app"
        // 26：MINA SSHD 依赖 java.time / java.nio.file 类型，低于 26 会在运行期 NoClassDefFoundError。
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // sora-editor 0.22.2 的构件是 Java 17 字节码（class v61），其 Kotlin 内联函数
    // 无法在 jvmTarget 11 里展开，编译期直接报 "Cannot inline bytecode built with JVM target 17"。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/license*",
                "META-INF/NOTICE*",
                "META-INF/notice*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/proguard/**",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // 部署程序图标（MN_bs.src）来自客户站点自身，需要网络图片加载 + 磁盘缓存。
    implementation(libs.coil.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.security.crypto)

    implementation(libs.zxing.android.embedded)

    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)

    // SSH：sshd-core 已含 Nio2ServiceFactory，不需要 sshd-mina / sshd-netty。
    // 不引 sshd-sftp —— 文件管理走 MNBT bridge 接口，终端 tab 只做 shell。
    implementation(libs.sshd.core)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.nop)
    // ed25519 / chacha20-poly1305@openssh.com 在 Android 内置 provider 上不可用，必须自带 BC。
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    // 终端渲染：内联的 Termux Apache-2.0 源码，见 terminal/NOTICE.md。
    implementation(project(":terminal"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
