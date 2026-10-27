package com.zero7goat.tung.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricAvailability { AVAILABLE, NOT_ENROLLED, NO_HARDWARE, UNAVAILABLE }

/**
 * Thin wrapper over AndroidX BiometricPrompt (strong biometrics only). Biometrics are a
 * convenience on top of the passcode, never a replacement: the passcode always works, so
 * losing or changing a fingerprint can never lock the user out.
 *
 * No cryptographic object is bound to the prompt: it only decides whether to show the app.
 */
object BiometricAuth {

    fun availability(context: Context): BiometricAvailability =
        when (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            else -> BiometricAvailability.UNAVAILABLE
        }

    /**
     * Shows the system biometric prompt. Calls [onSuccess] after a successful scan, or [onFailure]
     * if the prompt ends without one (cancelled, "Use passcode", too many tries, no hardware).
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .build()
        prompt.authenticate(info)
    }
}
