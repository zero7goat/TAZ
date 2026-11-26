package com.zero7goat.taz.features.passwords.presentation

import android.content.Context
import com.zero7goat.taz.features.passwords.domain.PassphraseGenerator

/** The passphrase word list shipped in assets/words.txt, read once per process. */
object WordList {
    @Volatile
    private var generator: PassphraseGenerator? = null

    fun generator(context: Context): PassphraseGenerator =
        generator ?: synchronized(this) {
            generator ?: PassphraseGenerator(
                context.applicationContext.assets.open("words.txt").bufferedReader().use { it.readLines() }
            ).also { generator = it }
        }
}
