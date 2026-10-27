package com.cruzzingt.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Tab(val web: WebView, val incognito: Boolean) {
    @Volatile var host: String? = null
    @Volatile var url = ""
    var title = ""
    var progress = 0
    val blocked = AtomicInteger()
    val isHome get() = url.isEmpty() || url.startsWith("cruzzingt:")
}

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private val tabs = ArrayList<Tab>()
    private var cur = 0
    private lateinit var container: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var shieldIcon: ImageView
    private lateinit var shieldCount: TextView
    private lateinit var star: ImageButton
    private lateinit var tabsCount: TextView
    private lateinit var topBar: View
    private val ui = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("url")?.let { tab().web.loadUrl(it) }
    }

    private fun tab() = tabs[cur]
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        store = Store(this)
        container = findViewById(R.id.container)
        urlBar = findViewById(R.id.url_bar)
        progress = findViewById(R.id.progress)
        shieldIcon = findViewById(R.id.shield_icon)
        shieldCount = findViewById(R.id.shield_count)
        star = findViewById(R.id.btn_star)
        tabsCount = findViewById(R.id.tabs_count)
        topBar = findViewById(R.id.top_bar)

        urlBar.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) {
                go(urlBar.text.toString())
                urlBar.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
                true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, f -> if (f) urlBar.post { urlBar.selectAll() } }
        findViewById<View>(R.id.shield_btn).setOnClickListener { showShield() }
        findViewById<View>(R.id.btn_reload).setOnClickListener { tab().web.reload() }
        findViewById<View>(R.id.btn_back).setOnClickListener { if (tab().web.canGoBack()) tab().web.goBack() }
        findViewById<View>(R.id.btn_forward).setOnClickListener { if (tab().web.canGoForward()) tab().web.goForward() }
        findViewById<View>(R.id.btn_home).setOnClickListener { loadHome(tab()) }
        findViewById<View>(R.id.btn_tabs).setOnClickListener { showTabs() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMenu(it) }
        star.setOnClickListener {
            val t = tab()
            if (!t.isHome) {
                val on = store.toggleBookmark(t.title, t.url)
                Toast.makeText(this, if (on) "Agregado a favoritos" else "Quitado de favoritos", Toast.LENGTH_SHORT).show()
                updateStar()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    tab().web.canGoBack() -> tab().web.goBack()
                    tabs.size > 1 -> closeTab(cur)
                    else -> finish()
                }
            }
        })

        // Auto-actualización al iniciar (además del trabajo periódico cada 24 h)
        lifecycleScope.launch(Dispatchers.IO) { ListUpdater.updateAll(applicationContext) }

        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)   // solo Android 7-9, para descargas
        }

        newTab(intent?.data?.toString(), false)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        i.data?.let { newTab(it.toString(), false) }
    }

    override fun onPause() {
        super.onPause()
        store.totalBlocked += AdBlock.session.getAndSet(0)
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    // ---------- pestañas ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?, inc: Boolean) {
        val w = WebView(this)
        val t = Tab(w, inc)
        if (inc && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(w, "incognito")   // cookies y almacenamiento aislados
        }
        w.setBackgroundColor(color(R.color.cz_bg))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (inc) { cacheMode = WebSettings.LOAD_NO_CACHE; saveFormData = false }
        }
        w.setDownloadListener { dl, ua, cd, mime, _ ->
            try {
                val name = URLUtil.guessFileName(dl, cd, mime)
                val req = DownloadManager.Request(Uri.parse(dl)).setMimeType(mime)
                    .addRequestHeader("User-Agent", ua)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(dl) ?: "")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Descargando $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo descargar", Toast.LENGTH_SHORT).show()
            }
        }
        w.webViewClient = client(t)
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { t.progress = p; if (tabs.getOrNull(cur) === t) refreshUi() }
            override fun onReceivedTitle(v: WebView, title: String?) { t.title = title ?: "" }
        }
        tabs.add(t)
        select(tabs.size - 1)
        if (url == null) loadHome(t) else w.loadUrl(url)
    }

    private fun select(i: Int) {
        cur = i
        container.removeAllViews()
        container.addView(tab().web, ViewGroup.LayoutParams(-1, -1))
        refreshUi()
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        container.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) newTab(null, false)
        else select(if (i < cur) cur - 1 else minOf(cur, tabs.size - 1))
        if (tabs.none { it.incognito } && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try { ProfileStore.getInstance().deleteProfile("incognito") } catch (_: Throwable) {}
        }
    }

    // ---------- cliente WebView + motor de bloqueo ----------
    private fun client(t: Tab) = object : WebViewClient() {
        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
            if (r.isForMainFrame) return null
            val ph = t.host
            if (ph != null && store.shieldOff(ph)) return null
            val url = r.url.toString()
            if (!url.startsWith("http")) return null
            val type = AdBlock.typeOf(url, r.requestHeaders["Accept"])
            if (AdBlock.shouldBlock(url, ph, type)) {
                t.blocked.incrementAndGet()
                AdBlock.session.incrementAndGet()
                if (!pending.getAndSet(true)) ui.postDelayed({ pending.set(false); if (tabs.getOrNull(cur) === t) updateShield() }, 300)
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
            t.url = url
            t.host = Uri.parse(url).host
            t.blocked.set(0)
            cosmetic(v, t)
            if (tabs.getOrNull(cur) === t) refreshUi()
        }

        override fun onPageCommitVisible(v: WebView, url: String) = cosmetic(v, t)

        override fun onPageFinished(v: WebView, url: String) {
            cosmetic(v, t)
            if (!t.incognito && url.startsWith("http")) store.addHistory(v.title ?: "", url)
            if (tabs.getOrNull(cur) === t) refreshUi()
        }

        override fun doUpdateVisitedHistory(v: WebView, url: String, reload: Boolean) {
            t.url = url
            t.host = Uri.parse(url).host
            if (tabs.getOrNull(cur) === t) refreshUi()
        }

        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
            val s = r.url.scheme ?: return false
            if (s in listOf("http", "https", "about", "data", "blob", "cruzzingt", "file")) return false
            try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}
            return true
        }
    }

    /** Bloqueo cosmético: inyecta CSS que oculta .ads, .ad-banner, .sponsored y selectores genéricos de EasyList. */
    private fun cosmetic(v: WebView, t: Tab) {
        val h = t.host ?: return
        if (store.shieldOff(h)) return
        v.evaluateJavascript(AdBlock.st.js, null)
    }

    // ---------- UI ----------
    private fun refreshUi() {
        val t = tab()
        topBar.setBackgroundColor(color(if (t.incognito) R.color.cz_incog else R.color.cz_surface))
        urlBar.hint = if (t.incognito) "Incógnito · buscar o escribir" else "Buscar o escribir dirección"
        if (!urlBar.hasFocus()) {
            urlBar.setText(if (t.isHome) "" else t.url.removePrefix("https://").removePrefix("http://").removeSuffix("/"))
        }
        progress.progress = t.progress
        progress.visibility = if (t.progress in 1..99) View.VISIBLE else View.INVISIBLE
        tabsCount.text = tabs.size.toString()
        updateShield()
        updateStar()
    }

    private fun updateShield() {
        val t = tab()
        val on = t.host?.let { !store.shieldOff(it) } ?: true
        val c = color(if (on) R.color.cz_primary else R.color.cz_off)
        shieldIcon.setColorFilter(c)
        shieldCount.setTextColor(c)
        shieldCount.text = t.blocked.get().let { if (it > 999) "999+" else it.toString() }
    }

    private fun updateStar() {
        val t = tab()
        val on = !t.isHome && store.isBookmarked(t.url)
        star.setColorFilter(color(if (on) R.color.cz_primary else R.color.cz_muted))
    }

    private fun go(q: String) {
        val s = q.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(' ') || !s.contains('.') -> "https://search.brave.com/search?q=" + Uri.encode(s)
            else -> "https://$s"
        }
        tab().web.loadUrl(url)
    }

    private fun showShield() {
        val t = tab()
        val host = t.host ?: return Toast.makeText(this, "Abre un sitio para usar Escudos", Toast.LENGTH_SHORT).show()
        val v = layoutInflater.inflate(R.layout.dialog_shield, null)
        val d = BottomSheetDialog(this)
        v.findViewById<TextView>(R.id.sh_host).text = host
        v.findViewById<TextView>(R.id.sh_page).text = t.blocked.get().toString()
        v.findViewById<TextView>(R.id.sh_total).text = "${store.totalBlocked + AdBlock.session.get()} en total · ${AdBlock.st.rules} reglas activas"
        val sw = v.findViewById<MaterialSwitch>(R.id.sh_switch)
        sw.isChecked = !store.shieldOff(host)
        sw.setOnCheckedChangeListener { _, on ->
            store.setShield(host, on)
            t.web.reload()
            updateShield()
            d.dismiss()
        }
        d.setContentView(v)
        d.show()
    }

    private fun showTabs() {
        val d = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_tabs, null)
        val box = v.findViewById<LinearLayout>(R.id.tabs_list)
        fun fill() {
            box.removeAllViews()
            tabs.forEachIndexed { i, t ->
                val r = layoutInflater.inflate(R.layout.item_tab, box, false)
                r.findViewById<TextView>(R.id.tab_title).apply {
                    text = (if (t.incognito) "🕶  " else "") + t.title.ifBlank { t.host ?: "Nueva pestaña" }
                    alpha = if (i == cur) 1f else 0.6f
                }
                r.findViewById<View>(R.id.tab_close).setOnClickListener { closeTab(i); fill() }
                r.setOnClickListener { select(i); d.dismiss() }
                box.addView(r)
            }
        }
        fill()
        v.findViewById<View>(R.id.new_tab).setOnClickListener { newTab(null, false); d.dismiss() }
        v.findViewById<View>(R.id.new_inc).setOnClickListener { newTab(null, true); d.dismiss() }
        d.setContentView(v)
        d.show()
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        listOf("Nueva pestaña", "Nueva pestaña incógnito", "Favoritos", "Historial", "Actualizar listas ahora")
            .forEachIndexed { i, s -> m.menu.add(0, i, i, s) }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> newTab(null, false)
                1 -> newTab(null, true)
                2 -> picker.launch(Intent(this, EntriesActivity::class.java).putExtra("key", "bookmarks"))
                3 -> picker.launch(Intent(this, EntriesActivity::class.java).putExtra("key", "history"))
                4 -> lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "Actualizando listas…", Toast.LENGTH_SHORT).show()
                    val ok = withContext(Dispatchers.IO) { ListUpdater.updateAll(applicationContext) }
                    Toast.makeText(this@MainActivity, if (ok) "Listas actualizadas" else "Sin cambios o sin conexión", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        m.show()
    }

    // ---------- página de inicio ----------
    private fun loadHome(t: Tab) {
        val inc = t.incognito
        val sub = if (inc) "Incógnito. No se guarda historial; al cerrar las pestañas se borra la sesión."
        else "Navegación sin anuncios ni rastreadores."
        val html = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
            html,body{height:100%;margin:0;background:${if (inc) "#04060C" else "#0A1020"};color:#E7EDF9;font-family:sans-serif}
            body{display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:0 32px}
            svg{width:72px;height:72px;fill:#3B82F6}
            h1{font-weight:300;letter-spacing:.04em;font-size:34px;margin:18px 0 6px}
            p{color:#8190AD;font-size:15px;max-width:300px;line-height:1.5;margin:0}
        </style></head><body>
        <svg viewBox="0 0 24 24"><path d="M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z"/></svg>
        <h1>cruzzingt</h1><p>$sub</p></body></html>"""
        t.url = ""
        t.host = null
        t.title = ""
        t.web.loadDataWithBaseURL("cruzzingt://home", html, "text/html", "utf-8", null)
        if (tabs.getOrNull(cur) === t) refreshUi()
    }
}
