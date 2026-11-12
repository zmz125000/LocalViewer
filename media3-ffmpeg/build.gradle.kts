plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("proguard-rules.txt")
    }
}

dependencies {
    api("androidx.media3:media3-decoder:1.11.0")
    implementation(libs.androidx.media3.exoplayer)
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
}
