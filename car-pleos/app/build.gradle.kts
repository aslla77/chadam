plugins {
    id("com.android.application")
}

android {
    namespace = "io.cabincaption.car"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.cabincaption.car"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // QR 생성(Apache-2.0). 오너 승인 2026-10-29, ADR-0004
    implementation("com.google.zxing:core:3.5.4")
    // Pleos Connect Vehicle SDK(공식 Maven, 017과 같은 판). 기어 상태 = UXR과 함께 주행 판정(fail-closed)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    testImplementation("junit:junit:4.13.2")
}
