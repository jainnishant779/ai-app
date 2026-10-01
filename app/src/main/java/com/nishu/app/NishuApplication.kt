package com.nishu.app

import android.app.Application

class NishuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
