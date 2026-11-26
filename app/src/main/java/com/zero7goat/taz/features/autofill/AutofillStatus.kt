package com.zero7goat.taz.features.autofill

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** True when TAZ is the device's autofill service. Re-checked whenever the screen comes back. */
@Composable
fun rememberAutofillEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isAutofillEnabled(context)) }
    LifecycleResumeEffect(context) {
        enabled = isAutofillEnabled(context)
        onPauseOrDispose { }
    }
    return enabled
}

fun isAutofillEnabled(context: Context): Boolean =
    try {
        context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
    } catch (e: Exception) {
        false
    }

/**
 * Opens the system screen to choose TAZ as the autofill service. When TAZ already is the
 * service, Android's own settings are opened instead (that request would do nothing).
 * Returns false if the device has neither.
 */
fun openAutofillSettings(context: Context): Boolean {
    val request = if (isAutofillEnabled(context)) {
        Intent(Settings.ACTION_SETTINGS)
    } else {
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:" + context.packageName))
    }
    return try {
        context.startActivity(request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
