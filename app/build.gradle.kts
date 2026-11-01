plugins {
    id("com.android.application")
}

val releaseKeystore = System.getenv("TVMCP_KEYSTORE")
val releaseStorePassword = System.getenv("TVMCP_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("TVMCP_KEY_ALIAS") ?: "tvmcp"
val releaseKeyPassword = System.getenv("TVMCP_KEY_PASSWORD")
val hasReleaseSigning = !releaseKeystore.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.ambitie.tvmcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ambitie.tvmcp"
        minSdk = 27
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/**",
                "META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")

    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.7.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")

    implementation("io.ktor:ktor-server-core:3.2.0")
    implementation("io.ktor:ktor-server-cio:3.2.0")
    implementation("io.ktor:ktor-server-cors:3.2.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.google.zxing:core:3.5.3")
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("org.slf4j:slf4j-simple:2.0.18")

    testImplementation("junit:junit:4.13.2")
}
