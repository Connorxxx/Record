package com.connor.record.utils

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.connor.record.App

fun String.showToast() {
    Toast.makeText(App.context, this, Toast.LENGTH_LONG).show()
}

inline fun <reified T> startService(context: Context, block: Intent.() -> Unit) {
    val intent = Intent(context, T::class.java)
    intent.block()
    context.startService(intent)
}