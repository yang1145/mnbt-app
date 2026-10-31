plugins {
    alias(libs.plugins.android.library)
}

android {
    // 上游 terminal-view 的包名。两个 textselection 源文件写死了 `import com.termux.view.R;`，
    // 沿用这个 namespace 就不必改动任何内联代码。
    namespace = "com.termux.view"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            // 混淆只在 :app 里做一次。库先自行裁剪会把 app 还要用的公开 API 砍掉。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // 内联的上游单元测试跑在 JVM 上，会碰到 android.util.Base64 这类桩方法。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}
