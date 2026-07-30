package ru.reader.app.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LibraryRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("library", Context.MODE_PRIVATE)

    private val _books = MutableStateFlow(decode(prefs.getString("books_json", "").orEmpty()))
    val books: Flow<List<BookMeta>> = _books.asStateFlow()

    var booksFolderUri: String?
        get() = prefs.getString("books_folder_uri", null)
        set(value) = prefs.edit().putString("books_folder_uri", value).apply()

    suspend fun addBook(book: BookMeta) {
        val list = _books.value.toMutableList()
        list.removeAll { it.id == book.id || it.uriString == book.uriString }
        list.add(0, book)
        persist(list)
    }

    suspend fun removeBook(id: String) {
        persist(_books.value.filterNot { it.id == id })
    }

    suspend fun updatePosition(id: String, position: Int) {
        persist(_books.value.map {
            if (it.id == id) it.copy(lastPosition = position) else it
        })
    }

    fun resolveDisplayName(uri: Uri): String {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
        return name?.substringBeforeLast('.') ?: "Книга"
    }

    fun fileNameWithExt(uri: Uri): String {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: "book.txt"
    }

    fun detectFormat(uri: Uri, displayName: String): BookFormat {
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        val lower = displayName.lowercase()
        when {
            mime.contains("epub") || lower.endsWith(".epub") -> return BookFormat.EPUB
            mime.contains("fictionbook") || lower.endsWith(".fb2") || lower.endsWith(".fb2.zip") ->
                return BookFormat.FB2
        }
        // Sniff content — many pickers give text/plain or */* for FB2
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(1024)
                val n = input.read(buf).coerceAtLeast(0)
                if (n >= 2 && buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte()) {
                    return BookFormat.EPUB
                }
                val head = String(buf, 0, n, Charsets.UTF_8)
                if (head.contains("FictionBook", ignoreCase = true) ||
                    head.contains("<fb2", ignoreCase = true) ||
                    head.contains("fictionbook/2.0", ignoreCase = true)
                ) {
                    return BookFormat.FB2
                }
            }
        }
        return when {
            mime.contains("xml") -> BookFormat.FB2
            else -> BookFormat.TXT
        }
    }

    /** Fix broken entries (XML-as-title, wrong format) without blocking UI on open. */
    suspend fun sanitizeLibrary() {
        val fixed = _books.value.map { book ->
            val fallback = runCatching {
                resolveDisplayName(Uri.parse(book.uriString))
            }.getOrDefault("Книга")
            val title = TitleSanitizer.clean(book.title, fallback)
            val format = if (book.format == BookFormat.TXT) {
                runCatching {
                    detectFormat(Uri.parse(book.uriString), fileNameWithExt(Uri.parse(book.uriString)))
                }.getOrDefault(book.format)
            } else book.format
            book.copy(title = title, format = format)
        }
        if (fixed != _books.value) persist(fixed)
    }

    fun setBooksFolder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
        }
        booksFolderUri = uri.toString()
    }

    /** Scan chosen folder for books and merge into library. */
    suspend fun scanBooksFolder(): Int {
        val folder = booksFolderUri?.let { Uri.parse(it) } ?: return 0
        val root = DocumentFile.fromTreeUri(context, folder) ?: return 0
        var added = 0
        val existing = _books.value.map { it.uriString }.toHashSet()
        fun walk(dir: DocumentFile) {
            dir.listFiles().forEach { file ->
                if (file.isDirectory) {
                    walk(file)
                    return@forEach
                }
                val name = file.name ?: return@forEach
                val lower = name.lowercase()
                if (!lower.endsWith(".fb2") && !lower.endsWith(".epub") && !lower.endsWith(".txt")) {
                    return@forEach
                }
                val uri = file.uri.toString()
                if (uri in existing) return@forEach
                val format = when {
                    lower.endsWith(".epub") -> BookFormat.EPUB
                    lower.endsWith(".fb2") -> BookFormat.FB2
                    else -> BookFormat.TXT
                }
                val meta = BookMeta(
                    id = UUID.randomUUID().toString(),
                    title = name.substringBeforeLast('.'),
                    format = format,
                    uriString = uri
                )
                existing += uri
                val list = _books.value.toMutableList()
                list.add(0, meta)
                persist(list)
                added++
            }
        }
        walk(root)
        return added
    }

    private fun persist(list: List<BookMeta>) {
        prefs.edit().putString("books_json", encode(list)).apply()
        _books.value = list
    }

    private fun encode(books: List<BookMeta>): String {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("author", b.author)
                    .put("format", b.format.name)
                    .put("uriString", b.uriString)
                    .put("addedAt", b.addedAt)
                    .put("lastPosition", b.lastPosition)
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String): List<BookMeta> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        BookMeta(
                            id = o.getString("id"),
                            title = o.getString("title"),
                            author = o.optString("author"),
                            format = BookFormat.valueOf(o.getString("format")),
                            uriString = o.getString("uriString"),
                            addedAt = o.optLong("addedAt", 0L),
                            lastPosition = o.optInt("lastPosition", 0)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
