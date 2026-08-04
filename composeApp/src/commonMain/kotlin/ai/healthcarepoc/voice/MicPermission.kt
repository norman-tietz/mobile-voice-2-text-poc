package ai.healthcarepoc.voice

enum class PermissionStatus { GRANTED, DENIED }

expect class MicPermission(context: ApplicationContext) {
    fun status(): PermissionStatus
    suspend fun request(): PermissionStatus
    fun openAppSettings()
}