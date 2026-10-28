package com.lightskip.app.service

data class SplashTapPoint(
    val x: Float,
    val y: Float
)

object SplashActionResolver {

    fun resolve(raw: String, screenWidth: Int, screenHeight: Int): SplashTapPoint? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val value = raw.trim()
        if (value.startsWith(RELATIVE_PREFIX, ignoreCase = true)) {
            return resolveRelative(value.substring(RELATIVE_PREFIX.length), screenWidth, screenHeight)
        }
        return resolveAbsolute(value)
    }

    private fun resolveRelative(raw: String, screenWidth: Int, screenHeight: Int): SplashTapPoint? {
        val parts = raw.split(',').map { it.trim().toFloatOrNull() ?: return null }
        if (parts.size != 2 && parts.size != 4) return null
        if (parts.any { it !in 0f..1f }) return null
        val left = parts[0]
        val top = parts[1]
        val right = if (parts.size == 4) parts[2] else left
        val bottom = if (parts.size == 4) parts[3] else top
        if (right < left || bottom < top) return null
        return SplashTapPoint(
            x = (left + right) * 0.5f * screenWidth,
            y = (top + bottom) * 0.5f * screenHeight
        )
    }

    private fun resolveAbsolute(raw: String): SplashTapPoint? {
        val parts = raw.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 4) return null
        val left = minOf(parts[0], parts[2])
        val top = minOf(parts[1], parts[3])
        val right = maxOf(parts[0], parts[2])
        val bottom = maxOf(parts[1], parts[3])
        if (right - left < 4 || bottom - top < 4) return null
        return SplashTapPoint(
            x = (left + right) * 0.5f,
            y = (top + bottom) * 0.5f
        )
    }

    private const val RELATIVE_PREFIX = "rel:"
}
