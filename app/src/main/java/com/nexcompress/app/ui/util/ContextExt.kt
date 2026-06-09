package com.nexcompress.app.ui.util

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/** Walks the context chain to find the hosting [ComponentActivity]. */
fun Context.findActivity(): ComponentActivity {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    error("No ComponentActivity found in the current context chain.")
}
