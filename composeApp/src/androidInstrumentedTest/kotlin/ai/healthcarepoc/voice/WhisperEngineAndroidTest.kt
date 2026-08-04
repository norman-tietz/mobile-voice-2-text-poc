package ai.healthcarepoc.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class WhisperEngineAndroidTest {

    @Test
    fun transcribesGermanSampleToNonEmptyText() {
        // The model ships as an asset of the app under test, so it's read via targetContext.
        // sample-de.wav is a test-only fixture packaged in this androidTest APK's own assets,
        // so it must be read via the instrumentation (test) context, not targetContext.
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val modelFile = File(targetContext.cacheDir, "ggml-small.bin")
        targetContext.assets.open("models/ggml-small.bin").use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }

        val engine = WhisperEngine(modelFile.absolutePath)
        val samples = readWavAsFloatMono16k(testContext.assets.open("sample-de.wav"))
        val text = engine.transcribe(samples)
        engine.release()

        assertTrue("expected non-empty transcript, got: '$text'", text.isNotBlank())
    }
}