package ai.healthcarepoc.voice

expect class ModelPathProvider(context: ApplicationContext) {
    fun resolveModelPath(): String
    // The clinical-context fine-tune (scripts/build-clinical-model.sh). Same ggml format as
    // resolveModelPath()'s stock model - only the weights differ.
    fun resolveClinicalModelPath(): String
    fun resolveVadModelPath(): String
}
