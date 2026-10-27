package com.cruzzingt.browser

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Lista de historial o favoritos. Toque = abrir, mantener presionado = eliminar. */
class EntriesActivity : AppCompatActivity() {
    private fun c(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val key = intent.getStringExtra("key") ?: "history"
        val store = Store(this)
        val data = if (key == "history") store.history() else store.bookmarks()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c(R.color.cz_bg)) }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(c(R.color.cz_surface)); setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        head.addView(TextView(this).apply {
            text = if (key == "history") "Historial" else "Favoritos"
            textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(c(R.color.cz_text))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)

        val list = ListView(this).apply { divider = null }
        val empty = TextView(this).apply {
            text = "Nada por aquí todavía"; setTextColor(c(R.color.cz_muted)); gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0)
        }
        val adapter = object : ArrayAdapter<Entry>(this, android.R.layout.simple_list_item_2, android.R.id.text1, data) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val v = super.getView(pos, cv, parent)
                val e = getItem(pos)!!
                v.findViewById<TextView>(android.R.id.text1).apply { text = e.title; setTextColor(c(R.color.cz_text)); maxLines = 1 }
                v.findViewById<TextView>(android.R.id.text2).apply { text = e.url; setTextColor(c(R.color.cz_muted)); maxLines = 1 }
                v.setPadding(dp(20), dp(10), dp(20), dp(10))
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, i, _ ->
            setResult(RESULT_OK, Intent().putExtra("url", adapter.getItem(i)!!.url)); finish()
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            val e = adapter.getItem(i)!!
            store.remove(key, e.url); adapter.remove(e)
            Toast.makeText(this, "Eliminado", Toast.LENGTH_SHORT).show(); true
        }
        if (key == "history") {
            head.addView(TextView(this).apply {
                text = "Borrar todo"; setTextColor(c(R.color.cz_primary_light)); setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { store.clearHistory(); adapter.clear() }
            })
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        if (data.isEmpty()) root.addView(empty, 1)
        setContentView(root)
    }
}
