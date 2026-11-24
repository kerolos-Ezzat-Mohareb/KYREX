package com.videograb

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        var cachedUrl: String? = null
        var cachedMeta: VideoMeta? = null
    }

    private var bgC = 0
    private var cardC = 0
    private var textC = 0
    private var mutedC = 0
    private var accentC = 0
    private var strokeC = 0

    private lateinit var input: EditText
    private lateinit var resultBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var bar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var fetchJob: Job? = null
    private var currentUrl: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.load(this)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) {
            bgC = 0xFF0B0B14.toInt()
            cardC = 0xFF171725.toInt()
            textC = 0xFFF7F5FF.toInt()
            mutedC = 0xFFA6A1B8.toInt()
            accentC = 0xFF9B6BFF.toInt()
            strokeC = 0xFF302943.toInt()
        } else {
            bgC = 0xFFF7F5FC.toInt()
            cardC = 0xFFFFFFFF.toInt()
            textC = 0xFF201A2E.toInt()
            mutedC = 0xFF777083.toInt()
            accentC = 0xFF7042D8.toInt()
            strokeC = 0xFFE8E1F2.toInt()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        window.statusBarColor = bgC
        window.navigationBarColor = bgC
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
            layoutDirection = if (L.ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(bgC)
        }

        // Brand header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(this).apply {
            text = "▶"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFB56BFF.toInt(), 0xFF6840D8.toInt())
            ).apply { cornerRadius = dp(16).toFloat() }
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), 0, 0, 0)
            addView(tv("KERYX", 21f, textC, true))
            addView(tv("VIDEO DOWNLOADER", 9f, mutedC, true))
        }
        header.addView(brandText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val lang = tv(if (L.ar) "EN" else "ع", 14f, accentC, true).apply {
            gravity = Gravity.CENTER
            background = shape(cardC, 14, strokeC)
            setPadding(dp(15), dp(10), dp(15), dp(10))
            setOnClickListener {
                L.save(this@MainActivity, !L.ar)
                recreate()
            }
        }
        header.addView(lang)
        root.addView(header, lp())

        // Hero
        root.addView(
            tv(
                L.t("Your videos. Your way.", "فيديوهاتك، بطريقتك."),
                27f, textC, true
            ), lp(30)
        )
        root.addView(
            tv(
                L.t("Save videos and audio in the quality you choose.",
                    "احفظ الفيديوهات والصوت بالجودة اللي تختارها."),
                14f, mutedC
            ), lp(7)
        )

        // Link entry card
        val entryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cardC, 22, strokeC)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        entryCard.addView(tv(L.t("VIDEO LINK", "رابط الفيديو"), 11f, mutedC, true))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = L.t("Paste a video link here", "الصق رابط الفيديو هنا")
            setHintTextColor(mutedC)
            setTextColor(textC)
            textSize = 14f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = shape(bgC, 14, strokeC)
            setPadding(dp(13), dp(13), dp(13), dp(13))
        }
        val paste = tv(L.t("Paste", "لصق"), 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = shape(accentC, 14)
            setPadding(dp(17), dp(14), dp(17), dp(14))
            setOnClickListener { pasteFromClipboard() }
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(paste, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        entryCard.addView(row, lp(10))
        entryCard.addView(
            tv(L.t("A link from your favorite platform works here.",
                "حط رابط الفيديو من المنصة اللي بتحبها هنا."), 11f, mutedC),
            lp(9)
        )
        root.addView(entryCard, lp(24))

        // Status / progress
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(
                if (night) 0xFF211A32.toInt() else 0xFFF0EAFB.toInt(),
                18, if (night) 0xFF45345F.toInt() else 0xFFE2D6F7.toInt()
            )
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val statusHeading = tv(L.t("READY WHEN YOU ARE", "جاهز لما تكون"), 10f, accentC, true)
        statusCard.addView(statusHeading)
        statusText = tv(
            L.t("Paste a link to get started.", "الصق رابط وابدأ التحميل."),
            13f, textC
        )
        statusCard.addView(statusText, lp(5))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accentC)
            indeterminateTintList = ColorStateList.valueOf(accentC)
            visibility = View.GONE
        }
        statusCard.addView(bar, lp(10))
        root.addView(statusCard, lp(12))

        // Small feature row
        val features = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val featureLabels = listOf(
            L.t("Fast", "سريع") to "⚡",
            L.t("Your quality", "جودتك") to "✦",
            L.t("Video & audio", "فيديو وصوت") to "♫"
        )
        featureLabels.forEachIndexed { index, item ->
            val feature = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(tv(item.second, 19f, accentC, true))
                addView(tv(item.first, 11f, mutedC, true), lp(3))
            }
            features.addView(feature, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (index < featureLabels.lastIndex) {
                val divider = View(this).apply { setBackgroundColor(strokeC) }
                features.addView(divider, LinearLayout.LayoutParams(dp(1), dp(30)))
            }
        }
        root.addView(features, lp(22))

        resultBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultBox, lp(8))

        val update = tv(
            L.t("↻  Update download engine", "↻  تحديث محرك التحميل"),
            13f, accentC, true
        ).apply {
            gravity = Gravity.CENTER
            background = shape(cardC, 16, strokeC)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { updateEngine() }
        }
        root.addView(update, lp(22))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgC)
            isFillViewport = true
            clipToPadding = false
            addView(root)
        }
        setContentView(scroll)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val u = extractUrl(s?.toString())
                if (u != null && u != currentUrl) {
                    val r = Runnable { fetch(u) }
                    pending = r
                    handler.postDelayed(r, 600)
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    Status.text.collect {
                        statusText.text = it.ifEmpty {
                            L.t(
                                "Paste a video link or share it from any app.",
                                "الصق رابط فيديو أو شاركه من أي تطبيق."
                            )
                        }
                    }
                }
                launch {
                    Status.progress.collect { p ->
                        when (p) {
                            -1 -> bar.visibility = View.GONE
                            -2 -> {
                                bar.visibility = View.VISIBLE
                                bar.isIndeterminate = true
                            }
                            else -> {
                                bar.visibility = View.VISIBLE
                                bar.isIndeterminate = false
                                bar.progress = p
                            }
                        }
                    }
                }
            }
        }

        val meta = cachedMeta
        if (meta != null) {
            currentUrl = cachedUrl
            input.setText(cachedUrl ?: "")
            render(meta)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val u = extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))
            i.action = null
            if (u != null) {
                fetch(u)
                input.setText(u)
            } else {
                Toast.makeText(this, L.t("No link found", "مفيش رابط"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip
        val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else null
        val u = extractUrl(t)
        if (u == null) {
            Toast.makeText(this, L.t("No link in clipboard", "مفيش رابط في الحافظة"), Toast.LENGTH_SHORT).show()
        } else {
            fetch(u)
            input.setText(u)
        }
    }

    private fun fetch(url: String) {
        pending?.let { handler.removeCallbacks(it) }
        fetchJob?.cancel()
        currentUrl = url
        resultBox.removeAllViews()
        Status.set(L.t("Analyzing link…", "جاري تحليل الرابط…"), -2)
        fetchJob = lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { InfoFetcher.fetch(applicationContext, url) }
            }
            r.onSuccess { meta ->
                cachedUrl = url
                cachedMeta = meta
                render(meta)
                Status.set(L.t("Choose a quality to download", "اختر الجودة للتحميل"))
            }.onFailure { e ->
                Status.set(cleanError(e))
            }
        }
    }

    private fun render(meta: VideoMeta) {
        resultBox.removeAllViews()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cardC, 20, strokeC)
            setPadding(dp(12), dp(12), dp(12), dp(16))
        }
        if (meta.thumb.isNotEmpty()) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = shape(strokeC, 14)
                clipToOutline = true
            }
            card.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(190)))
            loadThumb(meta.thumb, img)
        }
        card.addView(tv(meta.title, 16f, textC, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, lp(12))
        val sub = listOf(meta.uploader, fmtDuration(meta.duration))
            .filter { it.isNotEmpty() }.joinToString("  •  ")
        if (sub.isNotEmpty()) card.addView(tv(sub, 13f, mutedC), lp(4))
        resultBox.addView(card, lp(16))

        if (meta.video.isNotEmpty()) {
            resultBox.addView(tv(L.t("Video", "فيديو"), 13f, mutedC, true), lp(20))
            for (o in meta.video) {
                resultBox.addView(
                    optionRow("${o.height}p", "MP4", fmtSize(o.sizeBytes, o.approx)) {
                        startDownload(meta, o)
                    }, lp(8)
                )
            }
        }
        resultBox.addView(tv(L.t("Audio", "صوت"), 13f, mutedC, true), lp(20))
        for (o in meta.audio) {
            val (title, sub2) = when (o.type) {
                "mp3_320" -> "MP3 · 320 kbps" to L.t("Converted · plays everywhere", "محوّل · يشتغل على كل الأجهزة")
                "mp3_128" -> "MP3 · 128 kbps" to L.t("Smaller file", "حجم أصغر")
                "m4a" -> "M4A · AAC" to L.t("Original quality, no re-encode", "الجودة الأصلية بدون تحويل")
                else -> "OPUS" to L.t("Best quality for its size", "أفضل جودة بالنسبة للحجم")
            }
            resultBox.addView(
                optionRow(title, sub2, fmtSize(o.sizeBytes, o.approx)) { startDownload(meta, o) },
                lp(8)
            )
        }
    }

    private fun optionRow(title: String, sub: String, size: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(cardC, 16, strokeC)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(title, 16f, textC, true))
        texts.addView(tv(sub, 12f, mutedC))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tv(size, 14f, accentC, true))
        return row
    }

    private fun startDownload(meta: VideoMeta, opt: Option) {
        val url = currentUrl ?: return
        DownloadService.start(this, url, opt, meta.title)
        Toast.makeText(this, L.t("Download started", "بدأ التحميل"), Toast.LENGTH_SHORT).show()
    }

    private fun loadThumb(url: String, img: ImageView) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) img.setImageBitmap(bmp) else img.visibility = View.GONE
        }
    }

    private fun updateEngine() {
        Status.set(L.t("Updating engine…", "جاري تحديث المحرك…"), -2)
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    DownloadService.ensureInit(applicationContext)
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                    L.t("Engine updated", "تم تحديث المحرك")
                } catch (e: Exception) {
                    L.t("Update failed: ", "فشل التحديث: ") + (e.message ?: "")
                }
            }
            Status.set(msg)
        }
    }
}
