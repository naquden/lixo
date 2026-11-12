plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
    // doc/plans/030: FirebaseFriendCloudEmulatorTest's local `@Serializable InviteMirror` needs
    // the compiler plugin applied in THIS module (composeApp has it; androidApp's androidTest
    // source set didn't) to get a generated serializer rather than failing at runtime.
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "se.lixo.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "se.lixo.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(compose.preview)
    implementation(project.dependencies.platform(libs.firebase.bom))
    // doc/plans/030 §2a: App Check providers, no enforcement switched on yet. Play Integrity is
    // the real-device provider (release + debug builds both get a real token on a real device);
    // the debug provider lets a non-attested emulator/dev build register a debug token instead.
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    androidTestImplementation(compose.ui)
    androidTestImplementation(compose.foundation)
    androidTestImplementation(compose.material3)
    androidTestImplementation(project.dependencies.platform(libs.firebase.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.gitlive.firebase.auth)
    // doc/plans/030: FirebaseFriendCloudEmulatorTest exercises the real FirebaseSyncService
    // against the local Firestore emulator, which needs the gitlive Firestore API directly.
    androidTestImplementation(libs.gitlive.firebase.firestore)
    androidTestImplementation(libs.kotlinx.datetime)
    androidTestImplementation(libs.sqlite.bundled)
    // decodes the rendered QR bitmap (ZXing) to verify its payload
    androidTestImplementation(libs.zxing.core)
    // doc/plans/030 §2a: androidTest also needs a debug App Check token registered (doc/friends.md).
    androidTestImplementation(libs.firebase.appcheck.debug)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
