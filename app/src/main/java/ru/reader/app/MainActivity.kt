package ru.reader.app

import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.text.LineBreaker
import android.text.Layout
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.reader.app.data.BookMeta
import ru.reader.app.data.BookParser
import ru.reader.app.data.LibraryRepository
import ru.reader.app.data.ParsedBook
import ru.reader.app.data.ReaderSettings
import ru.reader.app.data.ReaderTheme
import ru.reader.app.data.SettingsRepository
import ru.reader.app.data.TitleSanitizer
import ru.reader.app.data.UiPalette
import ru.reader.app.databinding.ActivityMainBinding
import ru.reader.app.ui.BookPageTransformer
import ru.reader.app.ui.PagePaginator
import kotlin.math.hypot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var libraryRepo: LibraryRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var parser: BookParser

    private var books: List<BookMeta> = emptyList()
    private var settings = ReaderSettings()
    private var draftSettings = ReaderSettings()
    private var currentBook: ParsedBook? = null
    private var currentMeta: BookMeta? = null
    private var pages: List<CharSequence> = emptyList()
    private var pageIndex = 0
    private var menuVisible = false
    private var needsRepaginate = false
    private var pagerSettling = false

    private val pageAdapter = ReaderPageAdapter()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateStatusBar()
            clockHandler.postDelayed(this, 30_000L)
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        lifecycleScope.launch {
            runCatching { importUri(uri) }
                .onFailure { e ->
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось добавить: ${e.message ?: "ошибка"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        libraryRepo.setBooksFolder(uri)
        updateFolderHint()
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { libraryRepo.scanBooksFolder() }
            refreshLibrary()
            Toast.makeText(
                this@MainActivity,
                if (n > 0) "Добавлено книг: $n" else "В папке книг не найдено",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val adapter = BookAdapter(
        onOpen = { openBook(it) },
        onDelete = { meta ->
            lifecycleScope.launch {
                libraryRepo.removeBook(meta.id)
                refreshLibrary()
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        libraryRepo = LibraryRepository(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        parser = BookParser(applicationContext)

        hideSystemUi()
        setupLibrary()
        setupReader()

        setupThemeOnboarding()

        lifecycleScope.launch {
            settings = settingsRepo.settings.first()
            draftSettings = settings
            applySettingsToControls(settings)
            applyUiPalette(settings.uiPalette)
            withContext(Dispatchers.IO) { libraryRepo.sanitizeLibrary() }
            refreshLibrary()
            updateFolderHint()
            if (!settings.themeOnboardingDone) {
                showThemeOnboarding()
            }
            intent?.data?.let { importUri(it) }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (settings.uiPalette == UiPalette.SYSTEM && settings.themeOnboardingDone) {
            applyUiPalette(UiPalette.SYSTEM)
        }
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun setupLibrary() {
        binding.booksList.layoutManager = LinearLayoutManager(this)
        binding.booksList.adapter = adapter
        binding.btnAppSettings.setOnClickListener { showAppSettings() }
        binding.addBook.setOnClickListener {
            filePicker.launch(
                arrayOf(
                    "application/epub+zip",
                    "application/x-fictionbook+xml",
                    "text/plain",
                    "text/xml",
                    "application/xml",
                    "*/*"
                )
            )
        }
        binding.btnPickFolder.setOnClickListener { showFolderDialogThenPick() }
        binding.btnScanFolder.setOnClickListener {
            if (libraryRepo.booksFolderUri == null) {
                showFolderDialogThenPick()
            } else {
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { libraryRepo.scanBooksFolder() }
                    refreshLibrary()
                    Toast.makeText(this@MainActivity, "Добавлено: $n", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun maybeAskFolder() {
        if (libraryRepo.booksFolderUri != null) return
        if (books.isNotEmpty()) return
        showFolderDialogThenPick()
    }

    private fun showFolderDialogThenPick() {
        AlertDialog.Builder(this)
            .setTitle("Выбор каталога для книг")
            .setMessage(
                "Выберите папку для хранения и автосканирования книг.\n\n" +
                    "Из‑за ограничений системы доступны не все каталоги — " +
                    "лучше создать отдельную папку (например Books).\n\n" +
                    "Позже можно сменить в библиотеке."
            )
            .setPositiveButton("OK") { _, _ -> folderPicker.launch(null) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun updateFolderHint() {
        val uri = libraryRepo.booksFolderUri
        binding.folderHint.text = if (uri != null) {
            "Папка выбрана · EPUB / FB2 / TXT"
        } else {
            "Выбери папку с книгами или добавь файл"
        }
    }

    private fun setupReader() {
        binding.pagePager.adapter = pageAdapter
        binding.pagePager.setPageTransformer(BookPageTransformer())
        binding.pagePager.offscreenPageLimit = 1
        binding.pagePager.post {
            (binding.pagePager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            setupPagerTapZones()
        }

        binding.themeLight.setOnClickListener {
            draftSettings = draftSettings.copy(theme = ReaderTheme.LIGHT)
            previewDraft()
        }
        binding.themeSepia.setOnClickListener {
            draftSettings = draftSettings.copy(theme = ReaderTheme.SEPIA)
            previewDraft()
        }
        binding.themeDark.setOnClickListener {
            draftSettings = draftSettings.copy(theme = ReaderTheme.DARK)
            previewDraft()
        }
        binding.fontSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
            draftSettings = draftSettings.copy(fontSizeSp = 14f + progress / 10f)
            needsRepaginate = true
            previewDraft()
        })
        binding.lineHeightSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
            draftSettings = draftSettings.copy(lineHeight = 1.2f + progress / 100f)
            needsRepaginate = true
            previewDraft()
        })
        binding.btnBackToLibrary.setOnClickListener {
            closeMenuAndSave()
            showLibrary()
        }

        binding.pagePager.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (currentBook != null && pages.isEmpty()) {
                paginateAndShow(keepPage = false)
            }
        }
    }

    private fun setupPagerTapZones() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val recycler = binding.pagePager.getChildAt(0) as? RecyclerView ?: return
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                    }
                    MotionEvent.ACTION_UP -> {
                        if (hypot(e.x - downX, e.y - downY) < slop) {
                            if (menuVisible) {
                                closeMenuAndSave()
                                return true
                            }
                            val w = rv.width.toFloat().coerceAtLeast(1f)
                            when {
                                e.x < w * 0.28f -> {
                                    goPage(-1)
                                    return true
                                }
                                e.x > w * 0.72f -> {
                                    goPage(1)
                                    return true
                                }
                                else -> {
                                    openMenu()
                                    return true
                                }
                            }
                        }
                    }
                }
                return false
            }
        })
    }

    private fun openMenu() {
        menuVisible = true
        draftSettings = settings
        needsRepaginate = false
        applySettingsToControls(draftSettings)
        binding.settingsPanel.visibility = View.VISIBLE
    }

    private fun closeMenuAndSave() {
        if (!menuVisible) return
        menuVisible = false
        binding.settingsPanel.visibility = View.GONE
        settings = draftSettings
        lifecycleScope.launch { settingsRepo.update { settings } }
        applyThemeColors(settings.theme)
        if (needsRepaginate) {
            needsRepaginate = false
            paginateAndShow(keepPage = true)
        } else {
            renderPage()
        }
    }

    private fun previewDraft() {
        applyThemeColors(draftSettings.theme)
        pageAdapter.applyStyle(draftSettings)
    }

    private suspend fun importUri(uri: Uri) {
        val name = libraryRepo.resolveDisplayName(uri)
        val fileName = libraryRepo.fileNameWithExt(uri)
        val format = libraryRepo.detectFormat(uri, fileName)
        val parsed = withContext(Dispatchers.IO) {
            runCatching { parser.parse(uri, format) }.getOrElse { e ->
                ParsedBook(
                    title = name,
                    author = "",
                    blocks = listOf(
                        ru.reader.app.data.TextBlock.Paragraph(
                            "Не удалось разобрать файл (${e.javaClass.simpleName})"
                        )
                    )
                )
            }
        }
        // Don't open huge broken dumps as "book" if parse returned only error and format wrong
        val meta = BookMeta(
            id = UUID.randomUUID().toString(),
            title = TitleSanitizer.clean(parsed.title.ifBlank { name }, name),
            author = parsed.author,
            format = format,
            uriString = uri.toString()
        )
        libraryRepo.addBook(meta)
        refreshLibrary()
        withContext(Dispatchers.Main) {
            runCatching { openParsed(meta, parsed) }
                .onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        "Книга в библиотеке, но открыть не удалось",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun openBook(meta: BookMeta) {
        lifecycleScope.launch {
            val parsed = runCatching {
                withContext(Dispatchers.IO) {
                    parser.parse(Uri.parse(meta.uriString), meta.format)
                }
            }.getOrNull()
            if (parsed == null) {
                Toast.makeText(this@MainActivity, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Refresh title/author from parse
            val updated = meta.copy(
                title = TitleSanitizer.clean(parsed.title.ifBlank { meta.title }, meta.title),
                author = parsed.author.ifBlank { meta.author },
                format = meta.format
            )
            libraryRepo.addBook(updated)
            openParsed(updated, parsed)
        }
    }

    private fun openParsed(meta: BookMeta, parsed: ParsedBook) {
        currentMeta = meta
        currentBook = parsed
        pageIndex = meta.lastPosition
        menuVisible = false
        binding.settingsPanel.visibility = View.GONE
        binding.libraryPane.visibility = View.GONE
        binding.addBook.visibility = View.GONE
        binding.readerPane.visibility = View.VISIBLE
        applyThemeColors(settings.theme)
        pageAdapter.applyStyle(settings)
        pages = emptyList()
        binding.pagePager.post { paginateAndShow(keepPage = true) }
    }

    private fun showLibrary() {
        currentBook = null
        currentMeta = null
        pages = emptyList()
        binding.readerPane.visibility = View.GONE
        binding.libraryPane.visibility = View.VISIBLE
        binding.addBook.visibility = View.VISIBLE
        lifecycleScope.launch { refreshLibrary() }
    }

    private fun paginateAndShow(keepPage: Boolean) {
        val book = currentBook ?: return
        val padL = (28 * resources.displayMetrics.density).toInt()
        val padR = padL
        val padT = (40 * resources.displayMetrics.density).toInt()
        val padB = (48 * resources.displayMetrics.density).toInt()
        val textW = binding.pagePager.width - padL - padR
        val textH = binding.pagePager.height - padT - padB
        if (textW <= 0 || textH <= 0) {
            binding.pagePager.post { paginateAndShow(keepPage) }
            return
        }
        val saved = if (keepPage) pageIndex else 0
        pages = PagePaginator.buildPages(
            book = book,
            settings = settings,
            widthPx = textW,
            heightPx = textH,
            density = resources.displayMetrics.scaledDensity
        )
        pageIndex = saved.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        pageAdapter.submit(pages)
        pageAdapter.applyStyle(settings)
        binding.pagePager.setCurrentItem(pageIndex, false)
        updateStatusBar()
    }

    private fun goPage(delta: Int) {
        if (pages.isEmpty() || pagerSettling) return
        val next = (pageIndex + delta).coerceIn(0, pages.lastIndex)
        if (next == pageIndex) return
        pageIndex = next
        binding.pagePager.setCurrentItem(next, true)
        updateStatusBar()
    }

    private fun renderPage() {
        if (pages.isEmpty()) return
        pageIndex = pageIndex.coerceIn(0, pages.lastIndex)
        if (binding.pagePager.currentItem != pageIndex) {
            binding.pagePager.setCurrentItem(pageIndex, false)
        }
        pageAdapter.applyStyle(settings)
        updateStatusBar()
        currentMeta?.let { meta ->
            lifecycleScope.launch { libraryRepo.updatePosition(meta.id, pageIndex) }
        }
    }

    private fun updateStatusBar() {
        if (pages.isEmpty()) {
            binding.pageStatus.text = ""
            return
        }
        val total = pages.size
        val current = pageIndex + 1
        binding.pageProgress.max = 1000
        binding.pageProgress.progress = ((current.toFloat() / total) * 1000).toInt()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val battery = batteryPercent()
        binding.pageStatus.text = "$current/$total  $time  $battery%"
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    private fun applyThemeColors(theme: ReaderTheme) {
        val (bg, fg, muted) = when (theme) {
            ReaderTheme.LIGHT -> Triple(0xFFF5F2EA.toInt(), 0xFF1A1A1A.toInt(), 0xFF888888.toInt())
            ReaderTheme.SEPIA -> Triple(0xFFF0E6D2.toInt(), 0xFF3B2F1E.toInt(), 0xFF8A7A62.toInt())
            ReaderTheme.DARK -> Triple(0xFF2F2F2F.toInt(), 0xFFE0E0E0.toInt(), 0xFF9A9A9A.toInt())
        }
        binding.readerPane.setBackgroundColor(bg)
        binding.pagePager.setBackgroundColor(bg)
        pageAdapter.setColors(bg, fg)
        pageAdapter.applyStyle(if (menuVisible) draftSettings else settings)
        binding.pageStatus.setTextColor(muted)
    }

    private fun applySettingsToControls(s: ReaderSettings) {
        binding.fontSeek.progress = ((s.fontSizeSp - 14f) * 10f).toInt().coerceIn(0, 140)
        binding.lineHeightSeek.progress = ((s.lineHeight - 1.2f) * 100f).toInt().coerceIn(0, 80)
    }

    private suspend fun refreshLibrary() {
        books = libraryRepo.books.first()
        adapter.submit(books)
        val empty = books.isEmpty()
        binding.emptyHint.visibility = if (empty) View.VISIBLE else View.GONE
        binding.booksList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showAppSettings() {
        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setItems(arrayOf("Цвета")) { _, which ->
                if (which == 0) showColorPaletteDialog()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun setupThemeOnboarding() {
        val pane = binding.themeOnboarding
        pane.themeOk.setOnClickListener {
            val palette = when (pane.themeRadioGroup.checkedRadioButtonId) {
                pane.themeOptLight.id -> UiPalette.LIGHT
                pane.themeOptDark.id -> UiPalette.DARK
                else -> UiPalette.SYSTEM
            }
            finishThemeOnboarding(palette)
        }
    }

    private fun showThemeOnboarding() {
        val pane = binding.themeOnboarding
        when (settings.uiPalette) {
            UiPalette.LIGHT -> pane.themeOptLight.isChecked = true
            UiPalette.DARK -> pane.themeOptDark.isChecked = true
            UiPalette.SYSTEM -> pane.themeOptSystem.isChecked = true
        }
        binding.addBook.visibility = View.GONE
        pane.root.visibility = View.VISIBLE
        pane.root.elevation = 24f
        pane.root.bringToFront()
    }

    private fun finishThemeOnboarding(palette: UiPalette) {
        val readerTheme = when {
            SettingsRepository.isNightUi(this, palette) -> ReaderTheme.DARK
            else -> ReaderTheme.LIGHT
        }
        settings = settings.copy(
            uiPalette = palette,
            theme = readerTheme,
            themeOnboardingDone = true
        )
        draftSettings = settings
        lifecycleScope.launch { settingsRepo.update { settings } }
        binding.themeOnboarding.root.visibility = View.GONE
        binding.addBook.visibility = View.VISIBLE
        applyUiPalette(palette)
        applyThemeColors(readerTheme)
        applySettingsToControls(settings)
    }

    private fun showColorPaletteDialog() {
        val labels = arrayOf(
            "Тёмная",
            "Светлая",
            "Системная — как на телефоне"
        )
        val selected = when (settings.uiPalette) {
            UiPalette.DARK -> 0
            UiPalette.LIGHT -> 1
            UiPalette.SYSTEM -> 2
        }
        AlertDialog.Builder(this)
            .setTitle("Цвета")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val palette = when (which) {
                    0 -> UiPalette.DARK
                    1 -> UiPalette.LIGHT
                    else -> UiPalette.SYSTEM
                }
                val readerTheme = if (SettingsRepository.isNightUi(this, palette)) {
                    ReaderTheme.DARK
                } else {
                    ReaderTheme.LIGHT
                }
                settings = settings.copy(uiPalette = palette, theme = readerTheme)
                draftSettings = settings
                lifecycleScope.launch { settingsRepo.update { settings } }
                applyUiPalette(palette)
                applyThemeColors(readerTheme)
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun applyUiPalette(palette: UiPalette) {
        val dark = SettingsRepository.isNightUi(this, palette)
        val bg = if (dark) 0xFF1C1C1E.toInt() else 0xFFF5F2EA.toInt()
        val text = if (dark) 0xFFF2F2F2.toInt() else 0xFF1A1A1A.toInt()
        val muted = if (dark) 0xFF9A9A9A.toInt() else 0xFF7A7A7A.toInt()
        val card = if (dark) 0xFF2C2C2E.toInt() else 0xFFE8E2D6.toInt()
        binding.libraryPane.setBackgroundColor(bg)
        binding.root.setBackgroundColor(bg)
        binding.libraryTitle.setTextColor(text)
        binding.folderHint.setTextColor(muted)
        binding.btnAppSettings.setColorFilter(text)
        binding.emptyHint.setTextColor(muted)
        adapter.setColors(text, muted, card)
        adapter.notifyDataSetChanged()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
}


private class ReaderPageAdapter : RecyclerView.Adapter<ReaderPageAdapter.VH>() {
    private var items: List<CharSequence> = emptyList()
    private var bg = 0xFFF5F2EA.toInt()
    private var fg = 0xFF1A1A1A.toInt()
    private var fontSp = 18f
    private var lineHeight = 1.45f

    fun submit(list: List<CharSequence>) {
        items = list
        notifyDataSetChanged()
    }

    fun setColors(background: Int, foreground: Int) {
        bg = background
        fg = foreground
        notifyDataSetChanged()
    }

    fun applyStyle(settings: ReaderSettings) {
        fontSp = settings.fontSizeSp
        lineHeight = settings.lineHeight
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reader_page, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.setBackgroundColor(bg)
        holder.text.setTextColor(fg)
        holder.text.textSize = fontSp
        holder.text.setLineSpacing(0f, lineHeight)
        if (Build.VERSION.SDK_INT >= 23) {
            holder.text.breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            holder.text.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_FULL
        }
        if (Build.VERSION.SDK_INT >= 26) {
            holder.text.justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
        }
        holder.text.text = items[position]
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.pageContent)
    }
}

private class BookAdapter(
    private val onOpen: (BookMeta) -> Unit,
    private val onDelete: (BookMeta) -> Unit
) : RecyclerView.Adapter<BookAdapter.VH>() {

    private var items: List<BookMeta> = emptyList()
    private var titleColor = 0xFFF2F2F2.toInt()
    private var metaColor = 0xFF9A9A9A.toInt()
    private var cardColor = 0xFF2C2C2E.toInt()

    fun setColors(title: Int, meta: Int, card: Int) {
        titleColor = title
        metaColor = meta
        cardColor = card
    }

    fun submit(list: List<BookMeta>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val safeTitle = TitleSanitizer.clean(item.title, "Книга")
        holder.title.text = safeTitle
        holder.title.setTextColor(titleColor)
        holder.meta.setTextColor(metaColor)
        holder.card.setBackgroundColor(cardColor)
        holder.meta.text = listOfNotNull(
            item.author.takeIf { it.isNotBlank() },
            item.format.name
        ).joinToString(" · ")
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.bookCard)
        val title: TextView = view.findViewById(R.id.bookTitle)
        val meta: TextView = view.findViewById(R.id.bookMeta)
        val delete: ImageButton = view.findViewById(R.id.btnDelete)
    }
}
