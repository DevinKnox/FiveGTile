plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

providers.gradleProperty("fivegBuildDirectory").orNull?.let {
    layout.buildDirectory.set(file(it))
}

android {
    namespace = "dev.devinknox.fivegtile"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.cheng.fivegtile"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    signingConfigs {
        getByName("debug") {
            providers.gradleProperty("fivegDebugKeystore").orNull?.let {
                storeFile = file(it)
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.10.4")
    implementation("androidx.compose.foundation:foundation:1.10.4")

    // 只用于编译；Xposed API 必须由设备上的 LSPosed 提供，不能打包进 APK。
    compileOnly(files("libs/xposed-api-82.jar"))
}
