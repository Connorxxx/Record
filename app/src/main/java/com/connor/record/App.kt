package com.connor.record

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context

class App : Application() {

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
        var isRunning = false;
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }


}