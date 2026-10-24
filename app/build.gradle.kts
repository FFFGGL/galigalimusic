import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// release 签名信息放在根目录 keystore.properties（本地私有，勿外传）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "com.galigalimusic"
    compileSdk = 36

    defaultConfig {
        // 正式包名：与 RN 旧版（com.galigalimusic）及早期开发包（com.galigalimusic.dev）均不同，
        // 三者可在设备上共存；切换包名是一次性的，此后不再变更
        applicationId = "com.galigalimusic.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
        ndk {
            // whisper.cpp 只编 arm64（真机），缩短原生编译时间
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 渠道维度：dev = 日常调试（沿用 .dev 身份，设备上的登录态/歌单/缓存都在这个包里）
    //           prod = 正式发布（com.galigalimusic.app，release 签名）
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            applicationId = "com.galigalimusic.dev"
        }
        create("prod") {
            dimension = "channel"
            applicationId = "com.galigalimusic.app"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        // 设置页「关于」要读 VERSION_NAME，不能写死版本号
        buildConfig = true
    }
}

// Room schema 导出：每次版本变更生成 app/schemas/<version>.json，写迁移时用来精确 diff
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Media3 ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")

    // Network（全部请求走 OkHttp + org.json 手写解析；retrofit 与 kotlinx-serialization 未使用，已移除）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Local storage
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // QR code generation (登录扫码)，纯 Java 无相机依赖
    implementation("com.google.zxing:core:3.5.3")
}
