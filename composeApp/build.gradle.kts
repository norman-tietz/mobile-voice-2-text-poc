plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

kotlin {
    androidTarget()

    // Static libs for iosX64 (Intel simulator) are intentionally not built (see
    // scripts/build-whisper-ios.sh) and that target is excluded from this loop: this Mac is
    // Apple Silicon, and Task 1 already found Compose Multiplatform 1.11.1 has an unresolvable
    // dependency graph for iosX64 on this machine (see task-1-report.md). Scoped to iosArm64
    // (device) + iosSimulatorArm64 (Apple Silicon simulator), which is what this environment can
    // build and verify.
    val whisperCppDir = "$rootDir/third_party/whisper.cpp"

    // whisper.cpp's CMake build (see scripts/build-whisper-ios.sh) produces libwhisper.a plus
    // several ggml static libs that whisper.cpp links against, spread across a few directories.
    // BUILD_SHARED_LIBS=OFF was required to get .a files at all (this whisper.cpp version
    // defaults to building .dylib on non-MINGW platforms). The set of libs and Apple frameworks
    // below mirrors whisper.cpp's own CMake target_link_libraries graph:
    //   whisper       PUBLIC ggml, Threads
    //   ggml          PUBLIC ggml-base, and (since GGML_BACKEND_DL is off when static) the
    //                 enabled backends: ggml-cpu, ggml-blas, ggml-metal
    //   ggml-blas     PRIVATE Accelerate.framework (BLAS on Apple platforms)
    //   ggml-metal    PRIVATE Foundation.framework, Metal.framework, MetalKit.framework
    // (Metal shader source is embedded into libggml-metal.a at build time - GGML_METAL_EMBED_LIBRARY -
    // so no separate .metallib runtime resource is needed.)
    // Note: a local `data class` was tried here to hold the four per-target directories, but it
    // crashed the Kotlin script compiler backend ("Exception while generating code for... FUN
    // name:execute") - a known limitation of declaring local classes inside a Gradle Kotlin DSL
    // script block. Using a plain Map<String, List<String>> of "-L" dirs per target instead.
    val iosWhisperLibDirs = mapOf(
        "iosArm64" to listOf(
            "$whisperCppDir/build-ios-device/src/Release-iphoneos",
            "$whisperCppDir/build-ios-device/ggml/src/Release-iphoneos",
            "$whisperCppDir/build-ios-device/ggml/src/ggml-blas/Release-iphoneos",
            "$whisperCppDir/build-ios-device/ggml/src/ggml-metal/Release-iphoneos"
        ),
        "iosSimulatorArm64" to listOf(
            "$whisperCppDir/build-ios-sim/src/Release-iphonesimulator",
            "$whisperCppDir/build-ios-sim/ggml/src/Release-iphonesimulator",
            "$whisperCppDir/build-ios-sim/ggml/src/ggml-blas/Release-iphonesimulator",
            "$whisperCppDir/build-ios-sim/ggml/src/ggml-metal/Release-iphonesimulator"
        )
    )

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        target.compilations.getByName("main") {
            cinterops.create("whisper") {
                defFile(project.file("src/nativeInterop/cinterop/whisper.def"))
                packageName("whispercinterop")
                compilerOpts("-I$whisperCppDir/include", "-I$whisperCppDir/ggml/include")
            }
        }
        target.binaries.all {
            val dirs = iosWhisperLibDirs.getValue(target.name)
            linkerOpts(dirs.map { "-L$it" })
            linkerOpts(
                "-lwhisper", "-lggml", "-lggml-cpu", "-lggml-blas", "-lggml-metal", "-lggml-base",
                "-framework", "Accelerate", "-framework", "Metal", "-framework", "MetalKit", "-framework", "Foundation"
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.0")
        }
        androidInstrumentedTest.dependencies {
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.test:runner:1.6.2")
            implementation(kotlin("test"))
        }
    }

    jvmToolchain(17)
}

android {
    namespace = "ai.healthcarepoc.voice"
    compileSdk = 36
    ndkVersion = "30.0.15729638"

    defaultConfig {
        applicationId = "ai.healthcarepoc.voice"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Restrict to arm64-v8a to keep whisper.cpp's native build fast; this matches
            // the only emulator/device ABI available in this environment. Extend the list
            // if broader device coverage is needed later.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
