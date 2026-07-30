package ru.reader.app.ui

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max

/**
 * Soft book-like page turn: slight 3D rotate + parallax slide.
 */
class BookPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val width = page.width.toFloat().coerceAtLeast(1f)
        page.cameraDistance = width * 12f

        when {
            position < -1f || position > 1f -> {
                page.alpha = 0f
            }
            position <= 0f -> {
                // Current page turning away to the left
                page.alpha = 1f
                page.pivotX = width
                page.pivotY = page.height / 2f
                page.translationX = 0f
                page.rotationY = 22f * position
                page.translationZ = position * 8f
                page.scaleX = 1f
                page.scaleY = 1f
            }
            else -> {
                // Incoming page from the right
                page.alpha = 1f
                page.pivotX = 0f
                page.pivotY = page.height / 2f
                page.translationX = -width * position * 0.08f
                page.rotationY = 22f * position
                val scale = 0.96f + (1f - abs(position)) * 0.04f
                page.scaleX = scale
                page.scaleY = scale
                page.translationZ = -abs(position) * 8f
            }
        }

        // Soft shadow feel via alpha at edges
        val edge = max(0f, 1f - abs(position) * 0.15f)
        page.alpha = edge
    }
}
