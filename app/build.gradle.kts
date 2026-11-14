plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.catmoecircle.areyouok"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.catmoecircle.areyouok"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // 打开 R8：代码压缩/混淆 + 资源裁剪。release 包体积主要来自 DEX，
            // 关闭时 3 个 dex 约 30 MB；开启后会砍掉绝大部分未使用的 Compose/
            // OkHttp 代码。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    packaging {
        resources {
            // 运行时用不到的构建/许可元数据，以及只有 Kotlin 反射才读的 builtins。
            excludes += setOf(
                "/META-INF/*.version",
                "/META-INF/androidx/**",
                "/META-INF/com/android/build/gradle/app-metadata.properties",
                "/META-INF/version-control-info.textproto",
                "DebugProbesKt.bin",
                // OkHttp 只在解析 Cookie 时读这份公共后缀表，本应用不使用 CookieJar。
                "okhttp3/internal/publicsuffix/publicsuffixes.gz",
                "kotlin/**",
            )
        }
    }
    androidResources {
        // 应用只有中文文案（默认 values/ 即中文），依赖库自带的其他语言翻译用不上。
        localeFilters += listOf("zh", "en")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    // dsh-gateway 客户端：/v1 REST + /v1/stream WebSocket。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // 扫码入口：CameraX 取景 + ML Kit 二维码识别。barcode-scanning 是内置模型版本，
    // 不依赖 Google Play Services，离线也能扫码。
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // 仅测试用：仪器测试里生成二维码图片，喂给 ML Kit 验证解码链路。
    androidTestImplementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}