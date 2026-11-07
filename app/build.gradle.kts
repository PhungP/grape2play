import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Khoá bí mật đọc từ local.properties (không commit) hoặc biến môi trường.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String, def: String = ""): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: def).trim()

android {
    namespace = "com.grape.play"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.grape.play"
        minSdk = 26          // Android 8.0
        targetSdk = 34
        versionCode = 24
        versionName = "0.8.3"
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${secret("GOOGLE_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${secret("GOOGLE_CLIENT_SECRET")}\"")
        // Client ID của Microsoft là public client, không có secret.
        buildConfigField("String", "MS_CLIENT_ID", "\"${secret("MS_CLIENT_ID", "9100e450-02b5-4808-9076-53125990fa0b")}\"")
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += listOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST") } }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("com.hierynomus:smbj:0.13.0")          // SMB (NAS)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")  // WebDAV, UPnP/DLNA, Google Drive, OneDrive
    implementation("androidx.tvprovider:tvprovider:1.0.0")  // hàng GRAPEPlay trên trang chủ Android TV
    implementation("com.google.zxing:core:3.5.3")         // mã QR khi đăng nhập đám mây
}
