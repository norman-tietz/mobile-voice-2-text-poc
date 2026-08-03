# German Voice Transcription PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kotlin Multiplatform (Android + iOS) app that records German speech, transcribes it fully on-device with whisper.cpp, and shows the transcript growing in pause-delimited segments while recording continues.

**Architecture:** A `composeApp` module holds a Compose Multiplatform UI and shared (`commonMain`) orchestration logic (`TranscriptionSession`, `PauseDetector`) that is pure Kotlin and unit-tested with fakes. Platform-specific `expect`/`actual` implementations provide microphone capture (`AudioCapture`) and Whisper inference (`WhisperEngine`) — JNI/CMake on Android, Kotlin/Native cinterop on iOS, both wrapping the same vendored `whisper.cpp` C library and the same bundled German multilingual ggml model.

**Tech Stack:** Kotlin 2.4.10, Kotlin Multiplatform, Compose Multiplatform 1.11.1, Android Gradle Plugin 9.1.1, whisper.cpp (vendored as a git submodule, MIT-licensed), ggml multilingual `small` model (MIT-licensed, from `https://huggingface.co/ggerganov/whisper.cpp`).

## Global Constraints

- No network calls anywhere in the app — the whole point is on-device-only processing. Any dependency or code path that could make a network request is out of bounds.
- No logging of transcript text or raw audio content, in any build variant.
- German only — `language` is always passed as `"de"` to Whisper; no language selection UI.
- No persistence — the transcript lives only in in-memory UI state; nothing is written to disk or a database.
- Package/applicationId: `ai.healthcarepoc.voice` (matches the existing Gradle group).
- Android `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`. iOS deployment target 15.0.
- Model: multilingual ggml `small` (`ggml-small.bin`, ~466 MiB), not an `.en`-only variant.

---

### Task 1: Scaffold the Kotlin Multiplatform + Compose Multiplatform project

**Files:**
- Modify: `settings.gradle` → renamed `settings.gradle.kts`
- Modify: `build.gradle` (root) → renamed `build.gradle.kts`
- Modify: `gradle.properties`
- Create: `composeApp/build.gradle.kts`
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt`
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/MainActivity.kt`
- Create: `composeApp/src/androidMain/AndroidManifest.xml`
- Create: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/MainViewController.kt`
- Create: `iosApp/iosApp.xcodeproj/project.pbxproj` (via Xcode, see Step 6)
- Create: `iosApp/iosApp/iOSApp.swift`
- Create: `iosApp/iosApp/ContentView.swift`
- Delete: old root `src/` tree (`src/main`, `src/test`) — the project moves entirely into `composeApp`

**Interfaces:**
- Produces: `App()` composable (`ai.healthcarepoc.voice.App`, package `ai.healthcarepoc.voice`, no parameters) — the single shared UI entry point every later task's UI work extends.

- [ ] **Step 1: Remove the old plain-JVM project layout**

```bash
rm -rf src
```

Note: the project moves entirely to Kotlin DSL Gradle files (`.gradle.kts`) rather than the existing Groovy `.gradle` files, since Kotlin Multiplatform/Compose Multiplatform tooling and samples are written and tested against Kotlin DSL — this avoids fighting less-common Groovy-DSL edge cases in a KMP setup. `settings.gradle`/`build.gradle` are still untracked in git (never committed), so a plain `rm` is enough — there's nothing to unstage.

```bash
rm settings.gradle build.gradle
```

- [ ] **Step 2: Create root `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mobile-voice-2-text-poc"
include(":composeApp")
```

Replace `gradle.properties` (keep it, change its contents):

```properties
kotlin.code.style=official
kotlin.mpp.enableCInteropCommonization=true
android.useAndroidX=true
org.gradle.jvmargs=-Xmx4096m
```

The `kotlin.mpp.enableCInteropCommonization` line matters for Task 6: the iOS cinterop binding to whisper.cpp is declared per-target (`iosX64`/`iosArm64`/`iosSimulatorArm64`) but consumed from the shared intermediate `iosMain` source set, which requires cinterop commonization to unify the three per-target `whispercinterop` klibs into one the shared source set can see.

- [ ] **Step 3: Create root `build.gradle.kts`**

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.10" apply false
    kotlin("plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("com.android.application") version "9.1.1" apply false
}
```

- [ ] **Step 4: Create `composeApp/build.gradle.kts`**

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.application")
}

kotlin {
    androidTarget()

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
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
    }

    jvmToolchain(17)
}

android {
    namespace = "ai.healthcarepoc.voice"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.healthcarepoc.voice"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
```

- [ ] **Step 5: Create the shared `App.kt` placeholder UI**

```kotlin
package ai.healthcarepoc.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment

@Composable
fun App() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("German Voice Transcription PoC")
        }
    }
}
```

- [ ] **Step 6: Create the Android entry point**

`composeApp/src/androidMain/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <application
        android:label="Voice Transcription PoC"
        android:allowBackup="false">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/MainActivity.kt`:

```kotlin
package ai.healthcarepoc.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
```

Add the Activity Compose dependency needed by `MainActivity.kt` — in `composeApp/build.gradle.kts`, inside `kotlin { sourceSets { ... } }`, add:

```kotlin
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.0")
        }
```

- [ ] **Step 7: Create the iOS entry point**

`composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/MainViewController.kt`:

```kotlin
package ai.healthcarepoc.voice

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { App() }
```

Create a minimal Xcode project at `iosApp/` (via Xcode: File > New > Project > iOS > App, product name `iosApp`, organization identifier `ai.healthcarepoc`, interface: SwiftUI, no tests). Replace its generated `ContentView.swift` and `iOSApp.swift`:

`iosApp/iosApp/iOSApp.swift`:

```swift
import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

`iosApp/iosApp/ContentView.swift`:

```swift
import SwiftUI
import ComposeApp

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
```

In Xcode, add the built `composeApp` framework as a linked framework: Build Settings > Framework Search Paths, add
`$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`, and add a "Run Script" build phase running:

```bash
cd "$SRCROOT/.."
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

- [ ] **Step 8: Build and run on Android**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL. Install the resulting APK on an emulator/device and confirm the app launches showing "German Voice Transcription PoC".

- [ ] **Step 9: Build and run on iOS**

Open `iosApp/iosApp.xcodeproj` in Xcode, select a simulator, and run.
Expected: app launches showing "German Voice Transcription PoC".

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Scaffold Kotlin Multiplatform + Compose Multiplatform project (Android + iOS)"
```

---

### Task 2: Vendor whisper.cpp and bundle the German model

**Files:**
- Create: `.gitmodules` (via `git submodule add`)
- Create: `third_party/whisper.cpp/` (submodule checkout)
- Create: `composeApp/src/androidMain/assets/models/ggml-small.bin`
- Create: `iosApp/iosApp/Resources/ggml-small.bin` (added to Xcode as a bundle resource)
- Create: `scripts/download-model.sh`

**Interfaces:**
- Produces: model file available at a known relative path on each platform — Android via `AssetManager.open("models/ggml-small.bin")`, iOS via `NSBundle.mainBundle.pathForResource("ggml-small", ofType: "bin")`. Later tasks (5, 6) consume these exact paths.

- [ ] **Step 1: Add whisper.cpp as a git submodule**

```bash
git submodule add https://github.com/ggml-org/whisper.cpp.git third_party/whisper.cpp
git -C third_party/whisper.cpp rev-parse HEAD
```

Record the printed commit hash in the commit message in Step 5 — this pins the exact vendored version.

- [ ] **Step 2: Add a model download script**

`scripts/download-model.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
ANDROID_DEST="composeApp/src/androidMain/assets/models/ggml-small.bin"
IOS_DEST="iosApp/iosApp/Resources/ggml-small.bin"

mkdir -p "$(dirname "$ANDROID_DEST")" "$(dirname "$IOS_DEST")"

if [ ! -f "$ANDROID_DEST" ]; then
    curl -L "$MODEL_URL" -o "$ANDROID_DEST"
fi

cp "$ANDROID_DEST" "$IOS_DEST"

echo "Model ready at $ANDROID_DEST and $IOS_DEST"
```

```bash
chmod +x scripts/download-model.sh
```

- [ ] **Step 3: Run the download script**

Run: `./scripts/download-model.sh`
Expected: both destination files exist and are ~466 MB.

Verify: `ls -lh composeApp/src/androidMain/assets/models/ggml-small.bin iosApp/iosApp/Resources/ggml-small.bin`

- [ ] **Step 4: Add the iOS resource to the Xcode project**

In Xcode, right-click the `iosApp` group > "Add Files to iosApp..." > select `iosApp/iosApp/Resources/ggml-small.bin` > ensure "Copy items if needed" is unchecked (it's already in place) and the `iosApp` target's "Add to targets" checkbox is checked, so it's bundled into the app.

- [ ] **Step 5: Add `.gitignore` entries and commit**

Add to `.gitignore`:

```
composeApp/src/androidMain/assets/models/*.bin
iosApp/iosApp/Resources/*.bin
```

(The model is downloaded by the script, not committed — it's too large and MIT-licensed-but-external to vendor in git.)

```bash
git add .gitmodules third_party/whisper.cpp scripts/download-model.sh .gitignore
git commit -m "Vendor whisper.cpp submodule (pinned at <commit-hash-from-step-1>) and add model download script"
```

Replace `<commit-hash-from-step-1>` with the actual hash printed in Step 1.

---

### Task 3: Implement `PauseDetector` (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/PauseDetector.kt`
- Test: `composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/PauseDetectorTest.kt`

**Interfaces:**
- Produces: `class PauseDetector(sampleRateHz: Int, silenceThresholdRms: Float = 0.02f, minSilenceDurationMs: Int = 700)` with `fun accept(samples: FloatArray): Boolean` (returns `true` the moment a pause boundary is reached — i.e. trailing silence has lasted at least `minSilenceDurationMs`) and `fun reset()` (clears trailing-silence tracking after a boundary is consumed, called by `TranscriptionSession` in Task 4). Samples are expected in `[-1.0, 1.0]` float PCM.

- [ ] **Step 1: Write the failing tests**

```kotlin
package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PauseDetectorTest {

    private val sampleRate = 16_000

    private fun loudChunk(sizeMs: Int): FloatArray {
        val n = sampleRate * sizeMs / 1000
        return FloatArray(n) { i -> if (i % 2 == 0) 0.5f else -0.5f }
    }

    private fun silentChunk(sizeMs: Int): FloatArray {
        val n = sampleRate * sizeMs / 1000
        return FloatArray(n) { 0.0f }
    }

    @Test
    fun `does not signal a pause while speech is loud`() {
        val detector = PauseDetector(sampleRate, minSilenceDurationMs = 700)
        repeat(5) {
            assertFalse(detector.accept(loudChunk(100)))
        }
    }

    @Test
    fun `signals a pause once trailing silence exceeds the threshold duration`() {
        val detector = PauseDetector(sampleRate, minSilenceDurationMs = 700)
        assertFalse(detector.accept(loudChunk(100)))
        // Feed 600ms of silence: not yet a pause.
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        // 7th 100ms chunk of silence crosses the 700ms threshold.
        assertTrue(detector.accept(silentChunk(100)))
    }

    @Test
    fun `reset clears trailing silence tracking`() {
        val detector = PauseDetector(sampleRate, minSilenceDurationMs = 700)
        repeat(6) { detector.accept(silentChunk(100)) }
        detector.reset()
        // After reset, silence tracking starts over: one more 100ms chunk is not enough.
        assertFalse(detector.accept(silentChunk(100)))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:allTests --tests "ai.healthcarepoc.voice.PauseDetectorTest"`
Expected: FAIL — `PauseDetector` is unresolved.

- [ ] **Step 3: Implement `PauseDetector`**

```kotlin
package ai.healthcarepoc.voice

import kotlin.math.sqrt

class PauseDetector(
    private val sampleRateHz: Int,
    private val silenceThresholdRms: Float = 0.02f,
    private val minSilenceDurationMs: Int = 700
) {
    private var trailingSilenceMs: Int = 0

    fun accept(samples: FloatArray): Boolean {
        val rms = rms(samples)
        val chunkDurationMs = (samples.size * 1000) / sampleRateHz

        if (rms < silenceThresholdRms) {
            trailingSilenceMs += chunkDurationMs
        } else {
            trailingSilenceMs = 0
        }

        return trailingSilenceMs >= minSilenceDurationMs
    }

    fun reset() {
        trailingSilenceMs = 0
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) sumSquares += s.toDouble() * s.toDouble()
        return sqrt(sumSquares / samples.size).toFloat()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:allTests --tests "ai.healthcarepoc.voice.PauseDetectorTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/PauseDetector.kt composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/PauseDetectorTest.kt
git commit -m "Add PauseDetector: RMS-based trailing-silence detection"
```

---

### Task 4: Implement `TranscriptionSession` orchestrator (TDD)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/TranscriptionSession.kt`
- Test: `composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/TranscriptionSessionTest.kt`

**Interfaces:**
- Consumes: `PauseDetector` (Task 3) — `accept(FloatArray): Boolean`, `reset()`.
- Produces:
  - `interface Transcriber { fun transcribe(samples: FloatArray): String }` — the seam `WhisperEngine` (Tasks 5–6) implements later; this task only depends on the interface.
  - `class TranscriptionSession(private val transcriber: Transcriber, private val pauseDetector: PauseDetector)` with:
    - `val segments: List<String>` (read-only snapshot of finalized segments so far)
    - `fun acceptAudio(samples: FloatArray)` — feed one PCM buffer during recording.
    - `fun stop(): List<String>` — force-transcribes any pending buffer, returns the final full segment list.

- [ ] **Step 1: Write the failing tests**

```kotlin
package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeTranscriber(private val responses: MutableList<String>) : Transcriber {
    val callArgs = mutableListOf<FloatArray>()
    override fun transcribe(samples: FloatArray): String {
        callArgs.add(samples)
        return responses.removeAt(0)
    }
}

class TranscriptionSessionTest {

    private val sampleRate = 16_000

    private fun chunk(ms: Int, value: Float): FloatArray {
        val n = sampleRate * ms / 1000
        return FloatArray(n) { value }
    }

    @Test
    fun `no segments before any pause or stop`() {
        val transcriber = FakeTranscriber(mutableListOf())
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))

        assertEquals(emptyList(), session.segments)
    }

    @Test
    fun `finalizes a segment when a pause is detected`() {
        val transcriber = FakeTranscriber(mutableListOf("hallo welt"))
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))
        repeat(7) { session.acceptAudio(chunk(100, 0.0f)) }

        assertEquals(listOf("hallo welt"), session.segments)
    }

    @Test
    fun `starts a new empty buffer after a segment is finalized`() {
        val transcriber = FakeTranscriber(mutableListOf("erster satz"))
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))
        repeat(7) { session.acceptAudio(chunk(100, 0.0f)) }

        // First call's buffer should be just the loud chunk plus the silence up to the boundary,
        // not carry over into whatever comes next.
        assertEquals(1, transcriber.callArgs.size)
    }

    @Test
    fun `force-transcribes a pending buffer on stop even without a pause`() {
        val transcriber = FakeTranscriber(mutableListOf("letzter satz"))
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))
        val result = session.stop()

        assertEquals(listOf("letzter satz"), result)
        assertEquals(listOf("letzter satz"), session.segments)
    }

    @Test
    fun `stop with no pending audio produces no extra segment`() {
        val transcriber = FakeTranscriber(mutableListOf("hallo welt"))
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))
        repeat(7) { session.acceptAudio(chunk(100, 0.0f)) }
        val result = session.stop()

        assertEquals(listOf("hallo welt"), result)
        assertEquals(1, transcriber.callArgs.size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:allTests --tests "ai.healthcarepoc.voice.TranscriptionSessionTest"`
Expected: FAIL — `TranscriptionSession` and `Transcriber` are unresolved.

- [ ] **Step 3: Implement `Transcriber` interface and `TranscriptionSession`**

```kotlin
package ai.healthcarepoc.voice

interface Transcriber {
    fun transcribe(samples: FloatArray): String
}

class TranscriptionSession(
    private val transcriber: Transcriber,
    private val pauseDetector: PauseDetector
) {
    private val finalizedSegments = mutableListOf<String>()
    private val pendingSamples = mutableListOf<Float>()

    val segments: List<String> get() = finalizedSegments.toList()

    fun acceptAudio(samples: FloatArray) {
        pendingSamples.addAll(samples.toList())
        if (pauseDetector.accept(samples)) {
            finalizeSegment()
        }
    }

    fun stop(): List<String> {
        if (pendingSamples.isNotEmpty()) {
            finalizeSegment()
        }
        return segments
    }

    private fun finalizeSegment() {
        val text = transcriber.transcribe(pendingSamples.toFloatArray())
        finalizedSegments.add(text)
        pendingSamples.clear()
        pauseDetector.reset()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:allTests --tests "ai.healthcarepoc.voice.TranscriptionSessionTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/TranscriptionSession.kt composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/TranscriptionSessionTest.kt
git commit -m "Add TranscriptionSession: pause-triggered segment orchestration"
```

---

### Task 5: `WhisperEngine` — Android JNI binding to whisper.cpp

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/WhisperEngine.kt`
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/WhisperEngine.android.kt`
- Create: `composeApp/src/androidMain/cpp/whisper_jni.cpp`
- Create: `composeApp/src/androidMain/cpp/CMakeLists.txt`
- Modify: `composeApp/build.gradle.kts` (add `externalNativeBuild`)
- Test: `composeApp/src/androidInstrumentedTest/kotlin/ai/healthcarepoc/voice/WhisperEngineAndroidTest.kt`
- Test fixture: `composeApp/src/androidInstrumentedTest/assets/sample-de.wav`

**Interfaces:**
- Consumes: `Transcriber` (Task 4).
- Produces: `expect class WhisperEngine(modelPath: String) : Transcriber` with `actual override fun transcribe(samples: FloatArray): String` and `actual fun release()`. Task 6 provides the iOS `actual`; Task 10 (UI) constructs this with the platform's resolved model file path and calls `release()` when done.

- [ ] **Step 1: Declare the `expect` API in commonMain**

```kotlin
package ai.healthcarepoc.voice

expect class WhisperEngine(modelPath: String) : Transcriber {
    override fun transcribe(samples: FloatArray): String
    fun release()
}
```

- [ ] **Step 2: Write the CMake build for the JNI bridge**

`composeApp/src/androidMain/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni)

set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
add_subdirectory(${WHISPER_CPP_DIR} whisper_build)

add_library(whisper_jni SHARED whisper_jni.cpp)

find_library(log-lib log)

target_link_libraries(whisper_jni whisper ${log-lib} android)
```

- [ ] **Step 3: Write the JNI bridge**

`composeApp/src/androidMain/cpp/whisper_jni.cpp`:

```cpp
#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_ai_healthcarepoc_voice_WhisperEngine_nativeInit(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_healthcarepoc_voice_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray samples) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);

    jsize n = env->GetArrayLength(samples);
    std::vector<float> buffer(n);
    env->GetFloatArrayRegion(samples, 0, n, buffer.data());

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language = "de";
    wparams.translate = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;

    whisper_full(ctx, wparams, buffer.data(), static_cast<int>(buffer.size()));

    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(ctx, i);
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ai_healthcarepoc_voice_WhisperEngine_nativeRelease(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    whisper_free(ctx);
}
```

- [ ] **Step 4: Wire the CMake build into Gradle**

In `composeApp/build.gradle.kts`, inside the `android { }` block, add:

```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }
```

(Merge the `defaultConfig` block with the one already present from Task 1 rather than duplicating it.)

- [ ] **Step 5: Implement the Android `actual` class**

```kotlin
package ai.healthcarepoc.voice

actual class WhisperEngine actual constructor(modelPath: String) : Transcriber {
    private val handle: Long = nativeInit(modelPath).also {
        check(it != 0L) { "Failed to load Whisper model at $modelPath" }
    }

    actual override fun transcribe(samples: FloatArray): String {
        return nativeTranscribe(handle, samples)
    }

    actual fun release() {
        nativeRelease(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray): String
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
```

- [ ] **Step 6: Add an instrumented test with a real sample clip**

Record or obtain a short (2-5 second) German speech WAV, convert it to 16kHz mono 32-bit float raw PCM offline (e.g. `ffmpeg -i input.wav -ar 16000 -ac 1 -f f32le sample-de.pcm`), and place it at `composeApp/src/androidInstrumentedTest/assets/sample-de.wav` (kept as a normal WAV; the test below decodes it).

```kotlin
package ai.healthcarepoc.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class WhisperEngineAndroidTest {

    @Test
    fun transcribesGermanSampleToNonEmptyText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.cacheDir, "ggml-small.bin")
        context.assets.open("models/ggml-small.bin").use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }

        val engine = WhisperEngine(modelFile.absolutePath)
        val samples = readWavAsFloatMono16k(context.assets.open("sample-de.wav"))
        val text = engine.transcribe(samples)
        engine.release()

        assertTrue("expected non-empty transcript, got: '$text'", text.isNotBlank())
    }
}
```

Add a small WAV-reading helper used only by this test, `composeApp/src/androidInstrumentedTest/kotlin/ai/healthcarepoc/voice/WavReader.kt`:

```kotlin
package ai.healthcarepoc.voice

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun readWavAsFloatMono16k(input: InputStream): FloatArray {
    val bytes = input.readBytes()
    // Skip the 44-byte canonical WAV header; assumes a 16-bit PCM, 16kHz, mono WAV file.
    val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
    val sampleCount = (bytes.size - 44) / 2
    return FloatArray(sampleCount) { buffer.short / 32768.0f }
}
```

- [ ] **Step 7: Run the instrumented test**

Run: `./gradlew :composeApp:connectedAndroidTest --tests "ai.healthcarepoc.voice.WhisperEngineAndroidTest"` (requires a running emulator or connected device)
Expected: PASS, with the printed transcript containing recognizable German text.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/WhisperEngine.kt composeApp/src/androidMain composeApp/build.gradle.kts
git commit -m "Add Android WhisperEngine via JNI binding to whisper.cpp"
```

---

### Task 6: `WhisperEngine` — iOS cinterop binding to whisper.cpp

**Files:**
- Create: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/WhisperEngine.ios.kt`
- Create: `composeApp/src/nativeInterop/cinterop/whisper.def`
- Modify: `composeApp/build.gradle.kts` (add cinterop config per iOS target)
- Create: `scripts/build-whisper-ios.sh`

**Interfaces:**
- Consumes: `expect class WhisperEngine` (Task 5).
- Produces: the iOS `actual` half of `WhisperEngine`, satisfying the same `Transcriber` contract Task 5's Android half does.

- [ ] **Step 1: Build whisper.cpp static libraries for iOS**

`scripts/build-whisper-ios.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

cd third_party/whisper.cpp

cmake -B build-ios-device -G Xcode \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF
cmake --build build-ios-device --config Release

cmake -B build-ios-sim -G Xcode \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT=iphonesimulator \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF
cmake --build build-ios-sim --config Release

echo "Device lib:    third_party/whisper.cpp/build-ios-device/src/Release-iphoneos/libwhisper.a"
echo "Simulator lib: third_party/whisper.cpp/build-ios-sim/src/Release-iphonesimulator/libwhisper.a"
```

```bash
chmod +x scripts/build-whisper-ios.sh
./scripts/build-whisper-ios.sh
```

Run: `ls third_party/whisper.cpp/build-ios-device/src/Release-iphoneos/libwhisper.a third_party/whisper.cpp/build-ios-sim/src/Release-iphonesimulator/libwhisper.a`
Expected: both files exist. If CMake places the library at a different path on your machine (layouts vary slightly across whisper.cpp versions), adjust the `libraryPaths` in Step 2 to match the actual output path.

- [ ] **Step 2: Write the cinterop definition**

`composeApp/src/nativeInterop/cinterop/whisper.def`:

```
headers = whisper.h
headerFilter = whisper.h
```

(No `compilerOpts` here — header include paths are set as absolute paths in Gradle below, rather than as relative paths in this file, since relative-path resolution in `.def` files is easy to get subtly wrong and an absolute path removes the ambiguity.)

In `composeApp/build.gradle.kts`, inside the `kotlin { }` block, replace the iOS targets loop from Task 1 with per-target cinterop configuration:

```kotlin
    val whisperCppDir = "$rootDir/third_party/whisper.cpp"

    val iosLibDir = mapOf(
        "iosArm64" to "$whisperCppDir/build-ios-device/src/Release-iphoneos",
        "iosSimulatorArm64" to "$whisperCppDir/build-ios-sim/src/Release-iphonesimulator",
        "iosX64" to "$whisperCppDir/build-ios-sim/src/Release-iphonesimulator"
    )

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
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
            linkerOpts("-L${iosLibDir[target.name]}", "-lwhisper")
        }
    }
```

- [ ] **Step 3: Implement the iOS `actual` class**

```kotlin
package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import whispercinterop.WHISPER_SAMPLING_GREEDY
import whispercinterop.whisper_context_default_params
import whispercinterop.whisper_full
import whispercinterop.whisper_full_default_params
import whispercinterop.whisper_full_get_segment_text
import whispercinterop.whisper_full_n_segments
import whispercinterop.whisper_free
import whispercinterop.whisper_init_from_file_with_params

@OptIn(ExperimentalForeignApi::class)
actual class WhisperEngine actual constructor(modelPath: String) : Transcriber {

    private val ctx = memScoped {
        whisper_init_from_file_with_params(modelPath, whisper_context_default_params())
            ?: error("Failed to load Whisper model at $modelPath")
    }

    actual override fun transcribe(samples: FloatArray): String = memScoped {
        var params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY)
        params.language = "de".cstr.ptr
        params.translate = false
        params.print_progress = false
        params.print_realtime = false

        samples.usePinned { pinned ->
            whisper_full(ctx, params.readValue(), pinned.addressOf(0), samples.size)
        }

        val segmentCount = whisper_full_n_segments(ctx)
        buildString {
            for (i in 0 until segmentCount) {
                append(whisper_full_get_segment_text(ctx, i)?.toKString())
            }
        }
    }

    actual fun release() {
        whisper_free(ctx)
    }
}
```

Note: the exact generated Kotlin binding shape for the `whisper_full_params` struct (value type accessed via `.readValue()`/`.ptr`, or a different cinterop pattern for setting the `language` C-string field) depends on the cinterop tool's output for this specific whisper.cpp header version — if this doesn't compile as-is, inspect the generated bindings (Android Studio: "Go to definition" on `whisper_full_default_params`) and adjust the field-access syntax to match; the C-level call sequence (default params → set `language`/`translate`/`print_*` → `whisper_full` → loop `whisper_full_n_segments`/`whisper_full_get_segment_text`) is what must be preserved.

- [ ] **Step 4: Build for iOS**

Run: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL. Fix any cinterop field-access mismatches per the note in Step 3 until this passes.

- [ ] **Step 5: Manually verify on iOS simulator**

Temporarily call `WhisperEngine(modelPath).transcribe(...)` with a short hardcoded silence buffer from `MainViewController()` (or a debug button), run on the simulator, and confirm it returns without crashing. Remove the temporary call once confirmed — Task 10 wires up the real call path.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/iosMain composeApp/src/nativeInterop composeApp/build.gradle.kts scripts/build-whisper-ios.sh
git commit -m "Add iOS WhisperEngine via Kotlin/Native cinterop binding to whisper.cpp"
```

---

### Task 7: `AudioCapture` — Android microphone capture

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/AudioCapture.kt`
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/AudioCapture.android.kt`

**Interfaces:**
- Produces: `expect class AudioCapture { fun start(onSamples: (FloatArray) -> Unit); fun stop() }`. `onSamples` is invoked repeatedly with ~100ms of 16kHz mono float PCM while capture is running. Task 10 wires this into `TranscriptionSession.acceptAudio`.

- [ ] **Step 1: Declare the `expect` API**

```kotlin
package ai.healthcarepoc.voice

expect class AudioCapture {
    fun start(onSamples: (FloatArray) -> Unit)
    fun stop()
}
```

- [ ] **Step 2: Implement the Android `actual` class**

```kotlin
package ai.healthcarepoc.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

actual class AudioCapture {
    private val sampleRate = 16_000
    private var record: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    @SuppressLint("MissingPermission")
    actual fun start(onSamples: (FloatArray) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, sampleRate / 5) // ~100ms floor

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        record = audioRecord
        isRecording = true
        audioRecord.startRecording()

        recordingThread = thread {
            val shortBuffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    val floatBuffer = FloatArray(read) { i -> shortBuffer[i] / 32768.0f }
                    onSamples(floatBuffer)
                }
            }
        }
    }

    actual fun stop() {
        isRecording = false
        recordingThread?.join()
        recordingThread = null
        record?.stop()
        record?.release()
        record = null
    }
}
```

- [ ] **Step 3: Verify it compiles and records**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

Manual check: temporarily call `AudioCapture().start { samples -> Log.d("AudioCapture", "got ${samples.size} samples") }` from `MainActivity` after requesting `RECORD_AUDIO` permission at runtime (a plain `ActivityCompat.requestPermissions` call is enough for this manual check; Task 9 builds the real permission UI flow), run on a device, speak, and confirm log lines appear. Remove the temporary call afterward.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/AudioCapture.kt composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/AudioCapture.android.kt
git commit -m "Add Android AudioCapture via AudioRecord"
```

---

### Task 8: `AudioCapture` — iOS microphone capture

**Files:**
- Create: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/AudioCapture.ios.kt`
- Modify: `iosApp/iosApp/Info.plist` (microphone usage description)

**Interfaces:**
- Consumes: `expect class AudioCapture` (Task 7).
- Produces: the iOS `actual` half of `AudioCapture`, same contract as the Android half.

- [ ] **Step 1: Add the microphone usage description**

In `iosApp/iosApp/Info.plist`, add:

```xml
<key>NSMicrophoneUsageDescription</key>
<string>This app needs microphone access to transcribe your speech.</string>
```

- [ ] **Step 2: Implement the iOS `actual` class**

```kotlin
package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSMakeRange

@OptIn(ExperimentalForeignApi::class)
actual class AudioCapture {
    private val engine = AVAudioEngine()

    actual fun start(onSamples: (FloatArray) -> Unit) {
        val inputNode = engine.inputNode
        val format = inputNode.outputFormatForBus(0u)

        inputNode.installTapOnBus(
            bus = 0u,
            bufferSize = (format.sampleRate / 10).toUInt(), // ~100ms
            format = format
        ) { buffer: AVAudioPCMBuffer?, _ ->
            val channelData = buffer?.floatChannelData?.get(0) ?: return@installTapOnBus
            val frameLength = buffer.frameLength.toInt()
            val samples = FloatArray(frameLength)
            for (i in 0 until frameLength) {
                samples[i] = channelData[i]
            }
            onSamples(samples)
        }

        engine.prepare()
        engine.startAndReturnError(null)
    }

    actual fun stop() {
        engine.inputNode.removeTapOnBus(0u)
        engine.stop()
    }
}
```

Note: `AVAudioEngine`'s native input format is usually 44.1kHz or 48kHz, not the 16kHz Whisper expects — this task captures at the device's native rate. Resampling to 16kHz is handled in Task 10 where `AudioCapture` output is wired into `TranscriptionSession`, since that's where the "what sample rate does the rest of the pipeline require" contract is enforced end-to-end.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

Manual check: temporarily call `AudioCapture().start { samples -> println("got ${samples.size} samples") }` from `MainViewController()`, run on the simulator (grant mic permission when prompted; simulators use the host Mac's mic), speak into your Mac's mic, and confirm console output appears. Remove the temporary call afterward.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/AudioCapture.ios.kt iosApp/iosApp/Info.plist
git commit -m "Add iOS AudioCapture via AVAudioEngine"
```

---

### Task 9: Microphone permission handling

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/MicPermission.kt`
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.kt`
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.android.kt`
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/MicPermission.android.kt`
- Create: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.ios.kt`
- Create: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/MicPermission.ios.kt`

**Interfaces:**
- Produces: `expect class ApplicationContext` — an opaque per-platform handle (Android: a type alias for `Activity`; iOS: an empty marker class), used by both `MicPermission` and `ModelPathProvider` (Task 10) so their constructors match across platforms. `expect class MicPermission(context: ApplicationContext) { fun status(): PermissionStatus; suspend fun request(): PermissionStatus; fun openAppSettings() }` and `enum class PermissionStatus { GRANTED, DENIED }` (both in `commonMain`). Task 10's UI calls `status()`/`request()` before starting `AudioCapture`, and calls `openAppSettings()` from the denied-state UI per the design doc's requirement.

- [ ] **Step 1: Declare `ApplicationContext` and the shared `MicPermission` API**

`composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.kt`:

```kotlin
package ai.healthcarepoc.voice

expect class ApplicationContext
```

`composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/MicPermission.kt`:

```kotlin
package ai.healthcarepoc.voice

enum class PermissionStatus { GRANTED, DENIED }

expect class MicPermission(context: ApplicationContext) {
    fun status(): PermissionStatus
    suspend fun request(): PermissionStatus
    fun openAppSettings()
}
```

`composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.android.kt`:

```kotlin
package ai.healthcarepoc.voice

import android.app.Activity

actual typealias ApplicationContext = Activity
```

`composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.ios.kt`:

```kotlin
package ai.healthcarepoc.voice

actual class ApplicationContext
```

- [ ] **Step 2: Implement the Android `actual` class**

```kotlin
package ai.healthcarepoc.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine

actual class MicPermission actual constructor(private val activity: ApplicationContext) {

    actual fun status(): PermissionStatus {
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }

    actual suspend fun request(): PermissionStatus = suspendCancellableCoroutine { continuation ->
        PermissionRequestBridge.pendingContinuation = continuation
        activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PermissionRequestBridge.REQUEST_CODE)
    }

    actual fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }
}

object PermissionRequestBridge {
    const val REQUEST_CODE = 4321
    var pendingContinuation: kotlinx.coroutines.CancellableContinuation<PermissionStatus>? = null

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        pendingContinuation?.resumeWith(
            Result.success(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
        )
        pendingContinuation = null
    }
}
```

Add the coroutines dependency to `composeApp/build.gradle.kts`'s `commonMain.dependencies`:

```kotlin
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
```

Wire the callback into `MainActivity.kt` (from Task 1):

```kotlin
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionRequestBridge.onRequestPermissionsResult(requestCode, grantResults)
    }
```

- [ ] **Step 3: Implement the iOS `actual` class**

```kotlin
package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

@OptIn(ExperimentalForeignApi::class)
actual class MicPermission actual constructor(private val context: ApplicationContext) {

    actual fun status(): PermissionStatus {
        val granted = AVAudioApplication.sharedInstance().recordPermission == AVAudioApplicationRecordPermissionGranted
        return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }

    actual suspend fun request(): PermissionStatus = suspendCancellableCoroutine { continuation ->
        AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted ->
            continuation.resumeWith(
                Result.success(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            )
        }
    }

    actual fun openAppSettings() {
        val url = NSURL(string = UIApplicationOpenSettingsURLString)
        UIApplication.sharedApplication.openURL(url)
    }
}
```

Note: `AVAudioApplication`'s permission API is the modern (iOS 17+) replacement for the older `AVAudioSession.requestRecordPermission`; given the design's iOS 15.0 deployment target from the Global Constraints, if the build fails because `AVAudioApplication` isn't available at that deployment target, fall back to `AVAudioSession.sharedInstance().requestRecordPermission { granted -> ... }` and `AVAudioSession.sharedInstance().recordPermission`, which is the pre-iOS-17 equivalent API with the same semantics.

- [ ] **Step 4: Verify it compiles on both platforms**

Run: `./gradlew :composeApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/MicPermission.kt composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.kt composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.android.kt composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/MicPermission.android.kt composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/ApplicationContext.ios.kt composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/MicPermission.ios.kt composeApp/build.gradle.kts composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/MainActivity.kt
git commit -m "Add cross-platform mic permission handling with settings deep-link"
```

---

### Task 10: Wire it all together in the Compose Multiplatform UI

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt`
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/ResampleTo16k.kt`
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/AppLifecycleObserver.kt`
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/AppLifecycleObserver.android.kt`
- Create: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/AppLifecycleObserver.ios.kt`
- Modify: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/MainActivity.kt`
- Modify: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/MainViewController.kt`
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.kt` (`expect`/`actual` — resolves the bundled model to a usable file path per platform)
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.android.kt`
- Create: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.ios.kt`

**Interfaces:**
- Consumes: `TranscriptionSession`/`Transcriber` (Task 4), `WhisperEngine` (Tasks 5–6), `AudioCapture` (Tasks 7–8), `MicPermission`/`PermissionStatus`/`ApplicationContext` (Task 9).
- Produces: the final `App(...)` composable — nothing downstream depends on this; it's the top of the dependency graph. Also produces `expect class AppLifecycleObserver(onBackground: () -> Unit) { fun start(); fun stop() }`, used only within this task's `App.kt` to satisfy the design doc's "stop recording when backgrounded" requirement.

- [ ] **Step 1: Add a shared resampling helper**

`AudioCapture` delivers samples at each platform's native rate (Android: 16kHz already, per Task 7's `AudioRecord` config; iOS: the device's native rate, per Task 8's note). Add a single shared linear-resampler used by both platforms' wiring so `TranscriptionSession` always receives 16kHz audio regardless of source:

```kotlin
package ai.healthcarepoc.voice

fun resampleTo16k(samples: FloatArray, sourceSampleRateHz: Int): FloatArray {
    if (sourceSampleRateHz == 16_000) return samples
    val ratio = 16_000.0 / sourceSampleRateHz
    val outputSize = (samples.size * ratio).toInt()
    return FloatArray(outputSize) { i ->
        val sourceIndex = (i / ratio)
        val lower = sourceIndex.toInt().coerceIn(0, samples.size - 1)
        val upper = (lower + 1).coerceIn(0, samples.size - 1)
        val frac = (sourceIndex - lower).toFloat()
        samples[lower] * (1 - frac) + samples[upper] * frac
    }
}
```

- [ ] **Step 2: Add `ModelPathProvider`**

```kotlin
package ai.healthcarepoc.voice

expect class ModelPathProvider(context: ApplicationContext) {
    fun resolveModelPath(): String
}
```

Android `actual` (copies the bundled asset to a real file path on first use, since `whisper_init_from_file_with_params` needs a filesystem path, not an asset stream; `ApplicationContext` is a type alias for `Activity` from Task 9, which is itself a `Context`):

```kotlin
package ai.healthcarepoc.voice

import java.io.File

actual class ModelPathProvider actual constructor(private val context: ApplicationContext) {
    actual fun resolveModelPath(): String {
        val dest = File(context.filesDir, "ggml-small.bin")
        if (!dest.exists()) {
            context.assets.open("models/ggml-small.bin").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest.absolutePath
    }
}
```

iOS `actual` (the `context` parameter is unused — iOS resolves the bundled resource directly via `NSBundle`, but the parameter must be present to match the `expect` constructor):

```kotlin
package ai.healthcarepoc.voice

import platform.Foundation.NSBundle

actual class ModelPathProvider actual constructor(context: ApplicationContext) {
    actual fun resolveModelPath(): String {
        return NSBundle.mainBundle.pathForResource("ggml-small", ofType = "bin")
            ?: error("ggml-small.bin not found in app bundle")
    }
}
```

- [ ] **Step 3: Add `AppLifecycleObserver` for stop-on-background**

The design doc requires recording to stop automatically if the app leaves the foreground. Declare a shared `expect` API and implement it per platform using each OS's own lifecycle notification, so `App.kt` (Step 4) can call `stopRecording()` when it fires.

```kotlin
package ai.healthcarepoc.voice

expect class AppLifecycleObserver(onBackground: () -> Unit) {
    fun start()
    fun stop()
}
```

Android `actual` — `MainActivity.onPause()` (updated in Step 5) invokes a bridge object, since there is no `Activity` reference available inside `AppLifecycleObserver` itself:

```kotlin
package ai.healthcarepoc.voice

actual class AppLifecycleObserver actual constructor(private val onBackground: () -> Unit) {
    actual fun start() {
        AppLifecycleBridge.onBackgroundCallback = onBackground
    }

    actual fun stop() {
        AppLifecycleBridge.onBackgroundCallback = null
    }
}

object AppLifecycleBridge {
    var onBackgroundCallback: (() -> Unit)? = null
}
```

iOS `actual` — observes `UIApplicationDidEnterBackgroundNotification` directly:

```kotlin
package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.darwin.NSObjectProtocol

@OptIn(ExperimentalForeignApi::class)
actual class AppLifecycleObserver actual constructor(private val onBackground: () -> Unit) {
    private var observer: NSObjectProtocol? = null

    actual fun start() {
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ -> onBackground() }
    }

    actual fun stop() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
    }
}
```

- [ ] **Step 4: Rewrite `App.kt` with the full recording UI**

```kotlin
package ai.healthcarepoc.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    data object Recording : UiState
    data object PermissionDenied : UiState
    data class Error(val message: String) : UiState
}

@Composable
fun App(
    audioCapture: AudioCapture,
    micPermission: MicPermission,
    modelPathProvider: ModelPathProvider,
    nativeSampleRateHz: () -> Int
) {
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var transcript by remember { mutableStateOf(listOf<String>()) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

    // Loading the model can fail (missing/corrupt bundled file); session stays null
    // and an error state is shown instead of letting the app crash on first use.
    val session = remember {
        runCatching {
            val engine = WhisperEngine(modelPathProvider.resolveModelPath())
            TranscriptionSession(engine, PauseDetector(sampleRateHz = 16_000))
        }.onFailure { e ->
            uiState = UiState.Error(e.message ?: "Failed to load speech model")
        }.getOrNull()
    }

    fun startRecording() {
        val activeSession = session ?: return
        uiState = UiState.Recording
        audioCapture.start { samples ->
            val resampled = resampleTo16k(samples, nativeSampleRateHz())
            activeSession.acceptAudio(resampled)
            transcript = activeSession.segments
        }
    }

    fun stopRecording() {
        val activeSession = session ?: return
        audioCapture.stop()
        transcript = activeSession.stop()
        uiState = UiState.Idle
    }

    // The design doc requires recording to stop automatically if the app is backgrounded.
    val lifecycleObserver = remember {
        AppLifecycleObserver(onBackground = {
            if (uiState == UiState.Recording) stopRecording()
        })
    }
    DisposableEffect(Unit) {
        lifecycleObserver.start()
        onDispose { lifecycleObserver.stop() }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val state = uiState) {
                is UiState.PermissionDenied -> {
                    Text("Microphone permission is required to record.")
                    Button(onClick = { micPermission.openAppSettings() }) {
                        Text("Open Settings")
                    }
                }
                is UiState.Error -> {
                    Text("Error: ${state.message}")
                }
                else -> {
                    Button(onClick = {
                        if (uiState == UiState.Recording) {
                            stopRecording()
                        } else {
                            scope.launch {
                                when (micPermission.status()) {
                                    PermissionStatus.GRANTED -> startRecording()
                                    PermissionStatus.DENIED -> {
                                        when (micPermission.request()) {
                                            PermissionStatus.GRANTED -> startRecording()
                                            PermissionStatus.DENIED -> uiState = UiState.PermissionDenied
                                        }
                                    }
                                }
                            }
                        }
                    }) {
                        Text(if (uiState == UiState.Recording) "Stop" else "Record")
                    }
                }
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                transcript.forEach { segment -> Text(segment) }
            }
        }
    }
}
```

Note: setting `uiState` inside the `remember { runCatching { ... } }` block on the failure path is a composition-time side effect, which is not strictly idiomatic Compose (the usual pattern would thread this through `LaunchedEffect`); it's used here for brevity since it only runs once per composition and this is a PoC. If it causes a "state read/write during composition" warning or recomposition issue in practice, move the `runCatching` call into a `LaunchedEffect(Unit)` that sets both a nullable `session` state variable and `uiState` instead of computing `session` directly in `remember`.

- [ ] **Step 5: Update `MainActivity.kt` to construct real dependencies**

```kotlin
package ai.healthcarepoc.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(
                audioCapture = AudioCapture(),
                micPermission = MicPermission(this),
                modelPathProvider = ModelPathProvider(this),
                nativeSampleRateHz = { 16_000 }
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionRequestBridge.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onPause() {
        super.onPause()
        AppLifecycleBridge.onBackgroundCallback?.invoke()
    }
}
```

Both `MicPermission(this)` and `ModelPathProvider(this)` pass the `Activity` itself, since `ApplicationContext` (Task 9) is a type alias for `Activity` and `Activity` is also a valid `Context` for `ModelPathProvider`'s `filesDir`/`assets` use.

- [ ] **Step 6: Update `MainViewController.kt` to construct real dependencies**

```kotlin
package ai.healthcarepoc.voice

import androidx.compose.ui.window.ComposeUIViewController
import platform.AVFAudio.AVAudioSession
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    App(
        audioCapture = AudioCapture(),
        micPermission = MicPermission(ApplicationContext()),
        modelPathProvider = ModelPathProvider(ApplicationContext()),
        nativeSampleRateHz = { AVAudioSession.sharedInstance().sampleRate.toInt() }
    )
}
```

- [ ] **Step 7: Build both platforms**

Run: `./gradlew :composeApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src iosApp
git commit -m "Wire AudioCapture, WhisperEngine, and MicPermission into the recording UI"
```

---

### Task 11: End-to-end manual verification (success criteria)

**Files:** none — this task is verification, not code.

- [ ] **Step 1: Prepare test audio**

Record (or ask a colleague to record) 3-5 short German audio clips on a phone: at least one general-conversation clip and at least one clip using medical terminology (e.g. drug names, anatomical terms relevant to the healthcare context motivating this PoC). Keep them short (10-30 seconds each) so segments are easy to eyeball against what was actually said.

- [ ] **Step 2: Run on a real Android device**

Install the app (`./gradlew :composeApp:installDebug`) on a real Android device (not just an emulator, to get realistic mic input and CPU performance), grant mic permission, tap Record, speak one of the test clips' content live (or play it near the mic), and confirm: the transcript grows in segments while still recording (segments appear after pauses, not only after Stop), and the German text is subjectively close to what was said.

- [ ] **Step 3: Run on a real iOS device**

Build and run the `iosApp` target on a real iPhone via Xcode, repeat the same check as Step 2.

- [ ] **Step 4: Record findings**

Note, informally (e.g. in the PR description or a follow-up message to the team, not a new doc): whether segment latency (time from pause to text appearing) felt acceptable, whether general German speech transcribed well, and how medical terms fared specifically — this last point directly informs whether the `small` model is sufficient or whether `medium` should be tried next, per the design doc's noted fallback.

No commit for this task — it's a manual verification gate confirming the design doc's stated success criteria are met.
