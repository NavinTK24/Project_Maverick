plugins {
    id("com.android.application")
}

android {
    namespace = "com.maverickgrid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maverickgrid.app"
        minSdk = 29            // Android 10
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // installable demo build; use a real key for store release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += listOf("bin", "mgr", "txt", "json")
    }
}

dependencies {
    // none: the app uses only the Android framework and the MaverickGRID engine sources
}
