package ru.reader.app.data

enum class ReaderTheme {
    LIGHT,
    SEPIA,
    DARK
}

/** App chrome: dark / light / follow system. */
enum class UiPalette {
    DARK,
    LIGHT,
    SYSTEM
}

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val uiPalette: UiPalette = UiPalette.SYSTEM,
    val fontSizeSp: Float = 18f,
    val lineHeight: Float = 1.45f,
    val themeOnboardingDone: Boolean = false
)

data class BookMeta(
    val id: String,
    val title: String,
    val author: String = "",
    val format: BookFormat,
    val uriString: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPosition: Int = 0
)

enum class BookFormat { EPUB, FB2, TXT }

data class ParsedBook(
    val title: String,
    val author: String,
    val blocks: List<TextBlock>
)

sealed class TextBlock {
    data class Heading(val text: String) : TextBlock()
    data class Paragraph(val text: String) : TextBlock()
    data class Image(val id: String, val bytes: ByteArray) : TextBlock() {
        override fun equals(other: Any?): Boolean =
            other is Image && id == other.id && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
    }
}

object TitleSanitizer {
    fun clean(raw: String, fallback: String): String {
        val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        if (firstLine.isEmpty()) return fallback.take(80)
        val lower = firstLine.lowercase()
        if (firstLine.startsWith("<?xml") ||
            lower.contains("<fictionbook") ||
            lower.contains("<html") ||
            lower.contains("<!doctype") ||
            firstLine.length > 200 && firstLine.contains('<')
        ) {
            return fallback.take(80)
        }
        return firstLine.take(80)
    }
}
