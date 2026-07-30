package ru.reader.app.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import ru.reader.app.data.ParsedBook
import ru.reader.app.data.ReaderSettings
import ru.reader.app.data.TextBlock

object PagePaginator {

    private const val WINDOW = 6000
    private const val INDENT = "\u2003\u2003" // 2 em-spaces — works with justification

    fun buildPages(
        book: ParsedBook,
        settings: ReaderSettings,
        widthPx: Int,
        heightPx: Int,
        density: Float
    ): List<CharSequence> {
        if (widthPx <= 0 || heightPx <= 0) return listOf("")

        return runCatching {
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = settings.fontSizeSp * density
                typeface = Typeface.SERIF
                color = 0xFF000000.toInt()
                if (Build.VERSION.SDK_INT >= 23) {
                    // help hyphenation / metrics
                }
            }

            val full = buildBookSpannable(book, settings.fontSizeSp, widthPx, density)
            val spacingMult = settings.lineHeight
            val pages = mutableListOf<CharSequence>()
            var start = 0
            val total = full.length
            var guard = 0
            val maxPages = 50_000

            while (start < total && guard++ < maxPages) {
                val windowEnd = (start + WINDOW).coerceAtMost(total)
                val slice = full.subSequence(start, windowEnd)
                val layout = makeLayout(slice, paint, widthPx, spacingMult, 0f)
                var lastLine = -1
                for (i in 0 until layout.lineCount) {
                    if (layout.getLineBottom(i) > heightPx) break
                    lastLine = i
                }
                var end = if (lastLine < 0) {
                    (start + 1).coerceAtMost(total)
                } else {
                    start + layout.getLineEnd(lastLine)
                }
                if (end <= start) end = (start + 1).coerceAtMost(total)
                pages += full.subSequence(start, end)
                start = end
                while (start < total && full[start] == '\n') start++
            }
            pages.ifEmpty { listOf("") }
        }.getOrElse {
            listOf("Не удалось разбить книгу на страницы.\nПопробуй другой файл.")
        }
    }

    private fun buildBookSpannable(
        book: ParsedBook,
        fontSizeSp: Float,
        widthPx: Int,
        density: Float
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        book.blocks.forEach { block ->
            when (block) {
                is TextBlock.Heading -> {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    val start = sb.length
                    sb.append(block.text)
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, 0)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
                    sb.setSpan(RelativeSizeSpan(1.12f), start, sb.length, 0)
                    sb.append("\n\n")
                }
                is TextBlock.Paragraph -> {
                    if (block.text.isEmpty()) {
                        sb.append("\n")
                        return@forEach
                    }
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append("\n")
                    val start = sb.length
                    sb.append(INDENT)
                    sb.append(block.text)
                    sb.append("\n")
                    // Keep a tiny first-line visual indent via leading margin of 0 for wrapped lines
                    // Em-space indent already in text for justification-friendly layout
                }
                is TextBlock.Image -> {
                    if (sb.isNotEmpty()) sb.append("\n")
                    val bmp = runCatching {
                        BitmapFactory.decodeByteArray(block.bytes, 0, block.bytes.size)
                    }.getOrNull()
                    if (bmp != null) {
                        val maxW = widthPx
                        val maxH = (widthPx * 1.35f).toInt().coerceAtLeast(1)
                        val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height, 1f)
                        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                        if (scaled !== bmp) bmp.recycle()
                        val start = sb.length
                        sb.append("￼") // object replacement char
                        sb.setSpan(
                            ImageSpan(scaled, ImageSpan.ALIGN_CENTER),
                            start,
                            sb.length,
                            0
                        )
                        sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, 0)
                        sb.append("\n\n")
                    }
                }
            }
        }
        return sb
    }

    private fun makeLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        spacingMult: Float,
        spacingAdd: Float
    ): StaticLayout {
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(spacingAdd, spacingMult)
                .setIncludePad(false)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL, spacingMult, spacingAdd, false)
        }
    }
}
