package ru.reader.app.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Parses books into clean blocks: paragraphs, headings, images.
 */
class BookParser(private val context: Context) {

    fun parse(uri: Uri, format: BookFormat): ParsedBook {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (format) {
                    BookFormat.EPUB -> parseEpub(input)
                    BookFormat.FB2 -> parseFb2(input)
                    BookFormat.TXT -> parseTxt(input)
                }
            } ?: ParsedBook("Книга", "", listOf(TextBlock.Paragraph("Не удалось открыть файл")))
        }.getOrElse { e ->
            ParsedBook(
                title = "Книга",
                author = "",
                blocks = listOf(
                    TextBlock.Paragraph("Ошибка чтения: ${e.message ?: e.javaClass.simpleName}")
                )
            )
        }
    }

    private fun parseTxt(input: InputStream): ParsedBook {
        val text = cleanText(decodeBytes(input.readBytes()))
        val paragraphs = text
            .replace("\r\n", "\n")
            .split(Regex("\n{2,}"))
            .map { it.replace('\n', ' ').trim() }
            .filter { it.isNotEmpty() }
            .map { TextBlock.Paragraph(it) }
        val title = (paragraphs.firstOrNull() as? TextBlock.Paragraph)?.text?.take(60) ?: "Текст"
        return ParsedBook(title, "", paragraphs.ifEmpty { listOf(TextBlock.Paragraph("")) })
    }

    private fun parseFb2(input: InputStream): ParsedBook {
        val xml = decodeBytes(input.readBytes())
        val binaries = linkedMapOf<String, ByteArray>()
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true

        // Pass 1: binaries (usually at end)
        run {
            val p = factory.newPullParser()
            p.setInput(xml.reader())
            var inBinary = false
            var binId = ""
            val b64 = StringBuilder()
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val name = p.name?.substringAfterLast(':').orEmpty()
                when (event) {
                    XmlPullParser.START_TAG -> if (name == "binary") {
                        inBinary = true
                        binId = p.getAttributeValue(null, "id").orEmpty()
                        b64.clear()
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inBinary) {
                        b64.append(p.text.orEmpty())
                    }
                    XmlPullParser.END_TAG -> if (name == "binary" && inBinary) {
                        inBinary = false
                        if (binId.isNotBlank()) {
                            runCatching {
                                val cleaned = b64.toString().replace(Regex("\\s+"), "")
                                binaries[binId] = Base64.decode(cleaned, Base64.DEFAULT)
                            }
                        }
                    }
                }
                event = p.next()
            }
        }

        // Pass 2: text + image refs
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        var title = "Книга"
        var author = ""
        var coverId: String? = null
        val blocks = mutableListOf<TextBlock>()
        var inTitleInfo = false
        var inBody = false
        var inSectionTitle = false
        var inAuthor = false
        var inAnnotation = false
        var inCover = false
        var skipDepth = 0
        val textBuf = StringBuilder()

        fun local(): String = parser.name?.substringAfterLast(':').orEmpty()

        fun attrHref(): String? {
            for (i in 0 until parser.attributeCount) {
                val n = parser.getAttributeName(i)?.substringAfterLast(':').orEmpty()
                if (n.equals("href", true)) {
                    return parser.getAttributeValue(i)?.removePrefix("#")
                }
            }
            return null
        }

        fun flushParagraph() {
            val t = cleanText(textBuf.toString())
            textBuf.clear()
            if (t.isNotEmpty() && inBody && skipDepth == 0 && !inAnnotation) {
                blocks += TextBlock.Paragraph(t)
            }
        }

        fun addImage(id: String?) {
            if (id.isNullOrBlank()) return
            val bytes = binaries[id] ?: return
            if (inBody && skipDepth == 0) {
                blocks += TextBlock.Image(id, bytes)
            } else if (inCover || inTitleInfo) {
                coverId = id
            }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (local()) {
                        "title-info" -> inTitleInfo = true
                        "coverpage" -> inCover = true
                        "annotation" -> inAnnotation = true
                        "body" -> {
                            val bodyName = parser.getAttributeValue(null, "name")
                            val skipBody = !bodyName.isNullOrBlank() && !bodyName.equals("main", true)
                            inBody = !skipBody
                            if (skipBody) skipDepth++
                        }
                        "binary" -> skipDepth++ // already collected
                        "stylesheet" -> skipDepth++
                        "image" -> {
                            if (skipDepth == 0) addImage(attrHref())
                        }
                        "title" -> {
                            if ((inBody && skipDepth == 0) || inTitleInfo) {
                                textBuf.clear()
                                if (inBody) inSectionTitle = true
                            }
                        }
                        "author" -> if (inTitleInfo) inAuthor = true
                        "p", "v", "subtitle", "text-author", "cite" -> if (skipDepth == 0) textBuf.clear()
                        "empty-line" -> if (inBody && skipDepth == 0) {
                            blocks += TextBlock.Paragraph("")
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (skipDepth == 0 && !inAnnotation && !inCover) {
                        val t = parser.text
                        if (!t.isNullOrEmpty()) textBuf.append(t)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (local()) {
                        "title-info" -> inTitleInfo = false
                        "coverpage" -> inCover = false
                        "annotation" -> {
                            inAnnotation = false
                            textBuf.clear()
                        }
                        "body" -> {
                            if (skipDepth > 0 && !inBody) skipDepth--
                            inBody = false
                        }
                        "binary", "stylesheet" -> if (skipDepth > 0) skipDepth--
                        "title" -> {
                            val t = cleanText(textBuf.toString())
                            textBuf.clear()
                            if (inTitleInfo && t.isNotEmpty()) title = t
                            if (inSectionTitle) {
                                inSectionTitle = false
                                if (t.isNotEmpty()) blocks += TextBlock.Heading(t)
                            }
                        }
                        "book-title" -> {
                            val t = cleanText(textBuf.toString())
                            textBuf.clear()
                            if (t.isNotEmpty()) title = t
                        }
                        "first-name", "last-name", "middle-name", "nickname" -> {
                            if (inAuthor) {
                                val t = cleanText(textBuf.toString())
                                textBuf.clear()
                                if (t.isNotEmpty()) {
                                    author = listOf(author, t).filter { it.isNotBlank() }.joinToString(" ")
                                }
                            }
                        }
                        "author" -> inAuthor = false
                        "p", "v", "subtitle", "text-author", "cite" -> flushParagraph()
                    }
                }
            }
            event = parser.next()
        }

        val withCover = buildList {
            coverId?.let { id ->
                binaries[id]?.let { add(TextBlock.Image(id, it)) }
            }
            addAll(blocks)
        }

        return ParsedBook(
            title = title,
            author = author,
            blocks = withCover.ifEmpty { listOf(TextBlock.Paragraph("Пустая книга")) }
        )
    }

    private fun parseEpub(input: InputStream): ParsedBook {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name.trimStart('/')] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val containerXml = entries.entries
            .firstOrNull { it.key.equals("META-INF/container.xml", true) }
            ?.value?.let { decodeBytes(it) }
            ?: return ParsedBook("EPUB", "", listOf(TextBlock.Paragraph("Некорректный EPUB")))

        val opfPath = Regex("""full-path\s*=\s*"([^"]+)"""")
            .find(containerXml)?.groupValues?.get(1)?.trimStart('/')
            ?: return ParsedBook("EPUB", "", listOf(TextBlock.Paragraph("Не найден OPF")))

        val opfXml = entries[opfPath]?.let { decodeBytes(it) }
            ?: return ParsedBook("EPUB", "", listOf(TextBlock.Paragraph("OPF не найден")))
        val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")

        fun resolve(path: String): String {
            val clean = path.trimStart('/')
            return if (opfDir.isEmpty()) clean else "$opfDir/$clean".replace("//", "/")
        }

        val title = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", RegexOption.IGNORE_CASE)
            .find(opfXml)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.let { cleanText(it) }
            ?: "EPUB"
        val author = Regex("""<dc:creator[^>]*>(.*?)</dc:creator>""", RegexOption.IGNORE_CASE)
            .find(opfXml)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.let { cleanText(it) }
            .orEmpty()

        val manifest = mutableMapOf<String, String>()
        Regex(
            """<item\b[^>]*id\s*=\s*"([^"]+)"[^>]*href\s*=\s*"([^"]+)"[^>]*/?>""",
            setOf(RegexOption.IGNORE_CASE)
        ).findAll(opfXml).forEach { m -> manifest[m.groupValues[1]] = m.groupValues[2] }
        Regex(
            """<item\b[^>]*href\s*=\s*"([^"]+)"[^>]*id\s*=\s*"([^"]+)"[^>]*/?>""",
            setOf(RegexOption.IGNORE_CASE)
        ).findAll(opfXml).forEach { m -> manifest.putIfAbsent(m.groupValues[2], m.groupValues[1]) }

        val spineIds = Regex("""<itemref\b[^>]*idref\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(opfXml).map { it.groupValues[1] }.toList()

        val blocks = mutableListOf<TextBlock>()
        spineIds.forEach { id ->
            val href = manifest[id] ?: return@forEach
            val path = resolve(href)
            val bytes = entries[path] ?: entries.entries.firstOrNull {
                it.key.equals(path, true) || it.key.endsWith(href)
            }?.value ?: return@forEach
            blocks += htmlToBlocks(decodeBytes(bytes), entries, opfDir)
        }

        return ParsedBook(title, author, blocks.ifEmpty { listOf(TextBlock.Paragraph("Пустой EPUB")) })
    }

    private fun htmlToBlocks(
        html: String,
        entries: Map<String, ByteArray>,
        opfDir: String
    ): List<TextBlock> {
        val body = Regex("""(?is)<body[^>]*>(.*?)</body>""").find(html)?.groupValues?.get(1) ?: html
        val result = mutableListOf<TextBlock>()
        // images
        Regex("""(?is)<img[^>]+src\s*=\s*["']([^"']+)["'][^>]*>""").findAll(body).forEach { m ->
            val src = m.groupValues[1].trimStart('/')
            val path = if (opfDir.isEmpty()) src else "$opfDir/$src".replace("//", "/")
            val bytes = entries[path] ?: entries.entries.firstOrNull { it.key.endsWith(src) }?.value
            if (bytes != null) result += TextBlock.Image(src, bytes)
        }
        val cleaned = body
            .replace(Regex("""(?is)<script[^>]*>.*?</script>"""), "")
            .replace(Regex("""(?is)<style[^>]*>.*?</style>"""), "")
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(p|div|h[1-6]|li|tr)>"""), "\n\n")
            .replace(Regex("""(?i)<h[1-6][^>]*>"""), "\n\n§H§")
            .replace(Regex("""(?i)</h[1-6]>"""), "\n\n")
            .replace(Regex("""(?i)<img[^>]*>"""), "")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("""&#(\d+);""")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
            }

        cleaned.split(Regex("\n{2,}")).map { cleanText(it.replace('\n', ' ')) }
            .filter { it.isNotEmpty() }
            .forEach { chunk ->
                if (chunk.startsWith("§H§")) {
                    val t = chunk.removePrefix("§H§").trim()
                    if (t.isNotEmpty()) result += TextBlock.Heading(t)
                } else {
                    result += TextBlock.Paragraph(chunk)
                }
            }
        return result
    }

    private fun cleanText(raw: String): String {
        return raw
            .replace("\u00AD", "")
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
            .replace(Regex("[ \\t\\xA0]+"), " ")
            .trim()
    }

    private fun decodeBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, Charset.forName("Windows-1251")) }.getOrDefault(utf8)
    }

    private fun InputStream.readBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
