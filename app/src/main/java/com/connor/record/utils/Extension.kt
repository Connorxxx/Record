package com.connor.record.utils

import android.widget.Toast
import com.connor.record.App

fun String.showToast() {
    Toast.makeText(App.context, this, Toast.LENGTH_LONG).show()
}