package com.cruzzingt.browser

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private val TYPES = setOf("script", "image", "stylesheet", "xmlhttprequest", "subdocument", "font", "media", "ping", "other")
private val TOKEN = Regex("[a-z0-9]+")
private val CSS_OK = Regex("^[a-zA-Z0-9_\\-#.\\[\\]=\"'*^\$~| >+]{2,120}\$")

/** Una regla de red de Adblock Plus ya convertida (soporta ||, |, *, ^, $third-party y tipos). */
class Rule(val pat: String, val third: Boolean?, val types: Set<String>?, val whole: Boolean) {
    private val rx: Regex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try { compile(pat) } catch (e: Exception) { Regex("(?!x)x") }
    }

    fun ok(u: String, isThird: Boolean, type: String): Boolean {
        if (third != null && third != isThird) return false
        if (types != null && !(type in types || (type == "other" && ("xmlhttprequest" in types || "ping" in types)))) return false
        return whole || rx.containsMatchIn(u)
    }

    private fun compile(p0: String): Regex {
        var p = p0
        val sb = StringBuilder()
        if (p.startsWith("||")) { sb.append("^[a-z0-9.+-]+://([^/]*\\.)?"); p = p.substring(2) }
        else if (p.startsWith("|")) { sb.append('^'); p = p.substring(1) }
        val end = p.endsWith("|")
        if (end) p = p.dropLast(1)
        for (c in p) when {
            c == '*' -> sb.append(".*")
            c == '^' -> sb.append("(?:[^\\w.%-]|\$)")
            c.isLetterOrDigit() -> sb.append(c)
            else -> sb.append('\\').append(c)
        }
        if (end) sb.append('$')
        return Regex(sb.toString())
    }
}

/** Índice por host y por token para no recorrer 70k reglas en cada petición. */
class Index {
    private val hosts = HashMap<String, ArrayList<Rule>>()
    private val toks = HashMap<String, ArrayList<Rule>>()
    private val generic = ArrayList<Rule>()

    fun add(p: String, third: Boolean?, types: Set<String>?) {
        if (p.startsWith("||")) {
            val host = p.substring(2).takeWhile { it.isLetterOrDigit() || it == '.' || it == '-' }
            val rest = p.substring(2 + host.length)
            if (host.contains('.') && (rest.isEmpty() || rest[0] == '^' || rest[0] == '/' || rest[0] == ':')) {
                hosts.getOrPut(host) { ArrayList() }.add(Rule(p, third, types, rest.isEmpty() || rest == "^"))
                return
            }
        }
        val a = p.startsWith("|")
        val e = p.endsWith("|")
        var best = ""
        for (m in TOKEN.findAll(p)) {
            val s = m.range.first
            val en = m.range.last + 1
            val l = if (s == 0) a else p[s - 1] != '*'
            val r = if (en == p.length) e else p[en] != '*'
            if (l && r && m.value.length >= 3 && m.value.length > best.length) best = m.value
        }
        val rule = Rule(p, third, types, false)
        if (best.isEmpty()) generic.add(rule) else toks.getOrPut(best) { ArrayList() }.add(rule)
    }

    fun match(u: String, host: String, third: Boolean, type: String): Boolean {
        var h = host
        while (h.contains('.')) {
            hosts[h]?.let { l -> if (l.any { it.ok(u, third, type) }) return true }
            h = h.substringAfter('.')
        }
        var n = 0
        for (m in TOKEN.findAll(u)) {
            if (++n > 80) break
            toks[m.value]?.let { l -> if (l.any { it.ok(u, third, type) }) return true }
        }
        return generic.any { it.ok(u, third, type) }
    }
}

class State(val block: Index, val allow: Index, val hosts: HashSet<String>, val js: String, val rules: Int)

object AdBlock {
    val session = AtomicLong()
    @Volatile var st: State = build(emptyList(), emptyList())

    private const val BASE_CSS = ".ads,.ad-banner,.sponsored,#ads,#ad-banner,#sponsored,.advert,.advertisement," +
        "[class*=\"ad-banner\"],[id*=\"ad-banner\"],[class*=\"sponsored-\"],ins.adsbygoogle,[data-ad-slot],[aria-label=\"Advertisement\"]"

    private const val DEFAULT = """
||doubleclick.net^
||googlesyndication.com^
||googleadservices.com^
||adservice.google.com^
||google-analytics.com^
||scorecardresearch.com^
||taboola.com^
||outbrain.com^
||adnxs.com^
||criteo.com^
||amazon-adsystem.com^
||moatads.com^
/adserver.
/banner_ads/
"""

    fun load(ctx: Context) {
        val d = ListUpdater.dir(ctx)
        st = build(listOf(File(d, "easylist.txt"), File(d, "easyprivacy.txt")), listOf(File(d, "peterlowe.txt")))
    }

    private fun build(lists: List<File>, hostFiles: List<File>): State {
        val block = Index(); val allow = Index()
        val hosts = HashSet<String>(); val sels = LinkedHashSet<String>()
        var n = 0

        fun line(raw: String) {
            val l = raw.trim()
            if (l.isEmpty() || l[0] == '!' || l[0] == '[') return
            val ci = l.indexOf("##")
            if (ci >= 0) {
                if (ci == 0 && sels.size < 1500) { val s = l.substring(2); if (CSS_OK.matches(s)) sels.add(s) }
                return
            }
            if (l.contains("#@#") || l.contains("#?#") || l.contains("#\$#") || l.contains("#%#")) return
            var p = l.lowercase()
            var exc = false
            if (p.startsWith("@@")) { exc = true; p = p.substring(2) }
            var third: Boolean? = null
            var types: HashSet<String>? = null
            val d = p.lastIndexOf('$')
            if (d >= 0) {
                val opts = p.substring(d + 1).split(',')
                p = p.substring(0, d)
                for (o in opts) when {
                    o == "third-party" || o == "3p" -> third = true
                    o == "~third-party" || o == "1p" -> third = false
                    o in TYPES -> { if (types == null) types = HashSet(); types.add(o) }
                    o == "important" || o == "match-case" -> {}
                    else -> return   // opciones no soportadas: se ignora la regla para no romper sitios
                }
            }
            if (p.length < 4 || (p.startsWith("/") && p.endsWith("/"))) return
            (if (exc) allow else block).add(p, third, types)
            n++
        }

        val existing = lists.filter { it.exists() }
        if (existing.isEmpty()) DEFAULT.lines().forEach(::line)
        else existing.forEach { f -> f.bufferedReader().useLines { s -> s.forEach(::line) } }

        for (f in hostFiles.filter { it.exists() }) f.bufferedReader().useLines { s ->
            s.forEach { raw ->
                val t = raw.trim()
                if (t.isEmpty() || t[0] == '#') return@forEach
                val parts = t.split(Regex("\\s+"))
                val dom = parts.last().lowercase()
                if (dom.contains('.') && dom != "localhost") { hosts.add(dom); n++ }
            }
        }

        val css = StringBuilder(BASE_CSS).append("{display:none!important}")
        sels.forEach { css.append(it).append("{display:none!important}") }
        val js = "(function(){if(document.getElementById('__cz'))return;var s=document.createElement('style');" +
            "s.id='__cz';s.textContent=" + JSONObject.quote(css.toString()) +
            ";(document.head||document.documentElement).appendChild(s);})();"
        return State(block, allow, hosts, js, n)
    }

    private fun base(h: String): String {
        val p = h.split('.')
        if (p.size <= 2) return h
        return if (p[p.size - 1].length == 2 && p[p.size - 2].length <= 3) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
    }

    fun typeOf(url: String, accept: String?): String {
        val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "js", "mjs" -> "script"
            "css" -> "stylesheet"
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif" -> "image"
            "woff", "woff2", "ttf", "otf" -> "font"
            "mp4", "webm", "mp3", "m4a" -> "media"
            "html", "htm" -> "subdocument"
            else -> when {
                accept?.contains("image") == true -> "image"
                accept?.contains("text/css") == true -> "stylesheet"
                else -> "other"
            }
        }
    }

    fun shouldBlock(url: String, pageHost: String?, type: String): Boolean {
        val s = st
        val u = url.lowercase()
        val host = u.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').substringAfterLast('@')
        if (host.isEmpty()) return false
        val third = pageHost != null && base(host) != base(pageHost.lowercase())
        var h = host
        var hit = false
        while (h.contains('.')) { if (h in s.hosts) { hit = true; break }; h = h.substringAfter('.') }
        if (!hit) hit = s.block.match(u, host, third, type)
        return hit && !s.allow.match(u, host, third, type)
    }
}
