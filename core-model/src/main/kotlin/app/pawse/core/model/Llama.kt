package app.pawse.core.model

import android.os.Build
import java.io.File

/**
 * llama.cpp through JNI: load a GGUF, write one reply, free it. Not thread-safe;
 * [PawseModel] holds the only lock. Touching this object loads the native library,
 * so [llamaRuns] is checked first.
 */
internal object Llama {
    init {
        System.loadLibrary("pawse_llama")
    }

    external fun load(path: String, threads: Int, contextSize: Int): Long

    /** UTF-8 in and out. Null when the prompt does not fit or decoding failed. */
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topK: Int): ByteArray?

    external fun free(handle: Long)
}

/**
 * The native library is built for ARMv8.2 with dot product and half floats, so it
 * is only loaded on a core that has both. Anything older would be too slow anyway.
 */
internal fun llamaRuns(): Boolean = "arm64-v8a" in Build.SUPPORTED_ABIS && runCatching {
    val features = File("/proc/cpuinfo").useLines { lines -> lines.firstOrNull { it.startsWith("Features") }.orEmpty() }
        .substringAfter(':').trim().split(Regex("\\s+")).toSet()
    features.containsAll(listOf("asimddp", "fphp", "asimdhp"))
}.getOrDefault(false)
