plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.neonpulse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "uz.neonpulse.app"
        minSdk = 28          // Health Connect работает с Android 9
        targetSdk = 36      // требование Google Play для новых приложений
        // Номер версии берём из сборки GitHub (каждая сборка — новая версия, обновление ставится поверх)
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Тестовая сборка (pull request) — отдельное приложение «Neon Pulse TEST»:
        // ставится рядом с рабочим и не мешает обновлению рабочей версии
        val test = System.getenv("NP_TEST") == "1"
        if (test) applicationIdSuffix = ".test"
        manifestPlaceholders["appLabel"] = if (test) "Neon Pulse TEST" else "Neon Pulse"
        manifestPlaceholders["linkScheme"] = if (test) "neonpulsetest" else "neonpulse"
        // Вход через Google: ID клиента (тип Web) из Google Cloud. Пусто — кнопка Google скрыта, остаётся вход через Telegram
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"" + (System.getenv("NP_GOOGLE_CLIENT_ID") ?: "") + "\"")
    }

    // Подпись: ключ приходит из секретов GitHub; без него — отладочная подпись (для проверки)
    val ks = System.getenv("NP_KEYSTORE")
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = file(ks)
            storePassword = System.getenv("NP_STORE_PASS")
            keyAlias = System.getenv("NP_KEY_ALIAS") ?: "neon"
            keyPassword = System.getenv("NP_KEY_PASS") ?: System.getenv("NP_STORE_PASS")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.health.connect:connect-client:1.1.0-rc01")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Вход через Google (Credential Manager)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
