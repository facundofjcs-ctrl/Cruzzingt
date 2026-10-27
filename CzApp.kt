package com.cruzzingt.browser

import android.app.Application

class CzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread { AdBlock.load(this) }.start()   // carga la última lista guardada
        ListUpdater.schedule(this)              // y actualiza cada 24 h
    }
}
