plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.cheems.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.cheems.browser"
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters.add(providers.gradleProperty("targetAbi").getOrElse("arm64-v8a")) }
        targetSdk = 35
        versionCode = 9
        versionName = "0.2.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // 已发布的 GeckoView 完整通用构建（Mozilla Maven 仓库）。
    // 发布浏览器前应及时更新至 GeckoView 最新稳定安全版本。
    implementation("org.mozilla.geckoview:geckoview-omni:158.0.20261021001945")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
