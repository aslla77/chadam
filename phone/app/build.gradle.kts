plugins {
    id("com.android.application")
}

android {
    namespace = "io.cabincaption.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.cabincaption.phone"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 기기 점검 하네스(androidTest, 라이브러리 없음). src/README.md "폰 기기 점검"
        testInstrumentationRunner = "io.cabincaption.phone.E2eRun"
    }

    // debug 전용 미리보기 extras를 BuildConfig.DEBUG로 막는다(Preview.kt)
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // QR 디코딩(Apache-2.0). 차 앱과 같은 판, 오너 승인 2026-10-29. 카메라는 표준 Camera2
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
