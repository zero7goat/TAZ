package com.zero7goat.tung.core.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

/** The hosting activity, needed for BiometricPrompt. Null if there is none. */
tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
