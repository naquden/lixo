import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Set `kmp.ios.enabled=false` in local.properties to skip iOS targets (e.g. on Windows/Linux).
// Defaults to true; see doc/ios.md for the simulator build/test/run workflow.
val localProps: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val iosEnabled: Boolean = localProps.getProperty("kmp.ios.enabled", "true").toBoolean()

kotlin {
    android {
        namespace = "se.lixo.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }

        withHostTest {}
    }

    if (iosEnabled) {
        // iosX64 (Intel simulator) is intentionally excluded: sqlite-bundled 2.7.1 does not
        // publish an iosX64 artifact, so including it fails dependency resolution before any
        // code is compiled.
        listOf(
            iosArm64(),
            iosSimulatorArm64()
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "ComposeApp"
                isStatic = true
            }
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.vosk.android)
            // doc/plans/030 §7: Google code scanner for QR, no CAMERA permission needed.
            implementation(libs.play.services.code.scanner)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.gitlive.firebase.auth)
            implementation(libs.gitlive.firebase.firestore)
            implementation(libs.gitlive.firebase.crashlytics)
            // doc/plans/030 §7: renders the "Min kompiskod" QR (FriendQrCode).
            implementation(libs.qrose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        if (iosEnabled) {
            iosTest.dependencies {
                implementation(libs.compose.ui.test.multiplatform)
            }
        }
    }
}

if (iosEnabled) {
    // Test-only: the iosSimulatorArm64 test binary links GitLive's Firebase cinterop klibs,
    // which each carry `-framework FirebaseX` linker options. Those frameworks are not present
    // on a CI/dev machine unless fetched here. The shipped app gets Firebase from SPM instead
    // (see iosApp/project.yml) — this task never touches the app's framework{} block.
    val firebaseIosDir = rootProject.layout.buildDirectory.dir("firebase-ios/${libs.versions.firebase.ios.test.get()}")
    val fetchFirebaseIosTestFrameworks = tasks.register<Exec>("fetchFirebaseIosTestFrameworks") {
        inputs.file(rootProject.file("tools/ios/fetch-firebase-test-frameworks.sh"))
        outputs.dir(firebaseIosDir)
        commandLine(
            "bash",
            rootProject.file("tools/ios/fetch-firebase-test-frameworks.sh").absolutePath,
            firebaseIosDir.get().asFile.absolutePath
        )
    }

    val xcodeDev = providers.exec { commandLine("xcode-select", "-p") }.standardOutput.asText.map { it.trim() }

    kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
        .matching { it.name == "iosSimulatorArm64" }
        .configureEach {
            binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
                linkerOpts(
                    "-F${firebaseIosDir.get().asFile.absolutePath}/simulator",
                    "-ObjC",
                    "-lc++",
                    "-lsqlite3",
                    "-lz",
                    "-L${xcodeDev.get()}/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/iphonesimulator",
                    "-L${xcodeDev.get()}/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk/usr/lib/swift"
                )
                listOf(
                    "FirebaseCore", "FirebaseCoreInternal", "FirebaseInstallations", "GoogleUtilities",
                    "nanopb", "FBLPromises", "FirebaseAuth", "FirebaseAppCheckInterop", "FirebaseAuthInterop",
                    "FirebaseCoreExtension", "GTMSessionFetcher", "RecaptchaInterop", "FirebaseFirestore",
                    "FirebaseFirestoreInternal", "FirebaseSharedSwift", "absl", "grpc", "grpcpp", "leveldb",
                    "openssl_grpc", "FirebaseCrashlytics", "FirebaseRemoteConfigInterop", "FirebaseSessions",
                    "GoogleDataTransport", "Promises"
                ).forEach { framework -> linkerOpts("-framework", framework) }
            }
        }

    tasks.matching { it.name == "linkDebugTestIosSimulatorArm64" }.configureEach {
        dependsOn(fetchFirebaseIosTestFrameworks)
    }

    // KGP 2.3.20 defaults iosSimulatorArm64Test to `standalone=true`, which runs
    // `simctl spawn --standalone` with a NULL bootstrap port, so the process has no system
    // services (AVFoundation, Speech, cfprefsd), and it auto-picks a device from
    // `simctl list devices available`. Set `kmp.ios.testDevice=<booted UDID>` in
    // local.properties to run tests inside that already-Booted simulator instead; with the
    // property absent, KGP's defaults are kept.
    localProps.getProperty("kmp.ios.testDevice")?.takeIf { it.isNotBlank() }?.let { udid ->
        tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
            device.set(udid)
            standalone.set(false)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    if (iosEnabled) {
        add("kspIosSimulatorArm64", libs.room.compiler)
        add("kspIosArm64", libs.room.compiler)
    }
}
