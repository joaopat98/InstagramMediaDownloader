import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}

val releaseStoreFile = providers.environmentVariable("RELEASE_STORE_FILE").orNull
val releasePassword = providers.environmentVariable("RELEASE_PASSWORD").orNull
val releaseBuildNumber = providers.environmentVariable("RELEASE_BUILD_NUMBER").orNull?.toInt()

android {
    namespace = "com.joaopat98.instagrammediadownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joaopat98.instagrammediadownloader"
        minSdk = 29
        targetSdk = 35
        versionCode = releaseBuildNumber ?: 1
        versionName = releaseBuildNumber?.let { "1.0.$it" } ?: "1.0"
    }

    if (releaseStoreFile != null && releasePassword != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStoreFile)
            storeType = "PKCS12"
            storePassword = releasePassword
            keyAlias = "instagram-media-downloader"
            keyPassword = releasePassword
        }
        buildTypes.named("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
