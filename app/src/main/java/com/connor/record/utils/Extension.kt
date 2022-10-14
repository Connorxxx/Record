package com.connor.record.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.connor.record.App

fun String.showToast() {
    Toast.makeText(App.context, this, Toast.LENGTH_LONG).show()
}

inline fun <reified T> startService(context: Context, block: Intent.() -> Unit) {
    val intent = Intent(context, T::class.java)
    intent.block()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
}