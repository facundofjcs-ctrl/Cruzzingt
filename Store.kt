package com.cruzzingt.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Entry(val title: String, val url: String, val time: Long = System.currentTimeMillis())

class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("cz_store", Context.MODE_PRIVATE)

    private fun read(key: String): MutableList<Entry> {
        val a = JSONArray(sp.getString(key, "[]"))
        return MutableList(a.length()) { i -> a.getJSONObject(i).let { Entry(it.getString("t"), it.getString("u"), it.optLong("d")) } }
    }

    private fun write(key: String, l: List<Entry>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url).put("d", it.time)) }
        sp.edit().putString(key, a.toString()).apply()
    }

    fun history() = read("history")
    fun bookmarks() = read("bookmarks")
    fun clearHistory() = sp.edit().remove("history").apply()
    fun remove(key: String, url: String) = write(key, read(key).filter { it.url != url })

    fun addHistory(title: String, url: String) {
        val l = read("history")
        if (l.firstOrNull()?.url == url) l.removeAt(0)
        l.add(0, Entry(title.ifBlank { url }, url))
        write("history", l.take(1000))
    }

    fun isBookmarked(url: String) = read("bookmarks").any { it.url == url }

    fun toggleBookmark(title: String, url: String): Boolean {
        val l = read("bookmarks")
        val i = l.indexOfFirst { it.url == url }
        return if (i >= 0) { l.removeAt(i); write("bookmarks", l); false }
        else { l.add(0, Entry(title.ifBlank { url }, url)); write("bookmarks", l); true }
    }

    private fun key(h: String) = h.lowercase().removePrefix("www.")
    fun shieldOff(host: String) = sp.getStringSet("off", emptySet())!!.contains(key(host))
    fun setShield(host: String, on: Boolean) {
        val s = HashSet(sp.getStringSet("off", emptySet())!!)
        if (on) s.remove(key(host)) else s.add(key(host))
        sp.edit().putStringSet("off", s).apply()
    }

    var totalBlocked: Long
        get() = sp.getLong("total", 0)
        set(v) { sp.edit().putLong("total", v).apply() }
}
