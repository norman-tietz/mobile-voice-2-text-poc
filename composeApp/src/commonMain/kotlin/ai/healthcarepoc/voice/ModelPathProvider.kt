package ai.healthcarepoc.voice

expect class ModelPathProvider(context: ApplicationContext) {
    fun resolveModelPath(): String
    fun resolveVadModelPath(): String
}
