package com.zero7goat.taz.features.passwords.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PasswordGeneratorTest {

    private val symbols = "!@#$%^&*()-_=+[]{};:,.?"

    @Test
    fun honoursEveryLengthFrom8To64() {
        for (length in 8..64) {
            assertEquals(length, PasswordGenerator.generate(GeneratorOptions(length = length)).length)
        }
    }

    @Test
    fun alwaysContainsEverySelectedSet() {
        repeat(300) {
            val password = PasswordGenerator.generate(GeneratorOptions(length = 8))
            assertTrue(password.any { it in 'A'..'Z' })
            assertTrue(password.any { it in 'a'..'z' })
            assertTrue(password.any { it in '0'..'9' })
            assertTrue(password.any { it in symbols })
        }
    }

    @Test
    fun usesOnlySelectedSets() {
        repeat(200) {
            val digitsOnly = PasswordGenerator.generate(
                GeneratorOptions(length = 16, uppercase = false, lowercase = false, symbols = false)
            )
            assertTrue(digitsOnly.all { it in '0'..'9' })

            val lettersOnly = PasswordGenerator.generate(
                GeneratorOptions(length = 16, digits = false, symbols = false)
            )
            assertTrue(lettersOnly.all { it in 'A'..'Z' || it in 'a'..'z' })
        }
    }

    @Test
    fun successiveResultsDiffer() {
        val results = (1..50).map { PasswordGenerator.generate(GeneratorOptions(length = 24)) }.toSet()
        assertTrue(results.size > 45)
    }

    @Test
    fun rejectsNoCharacterSet() {
        val options = GeneratorOptions(uppercase = false, lowercase = false, digits = false, symbols = false)
        assertFalse(options.isValid)
        try {
            PasswordGenerator.generate(options)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsLengthsOutsideTheSupportedRange() {
        assertFalse(GeneratorOptions(length = 7).isValid)
        assertFalse(GeneratorOptions(length = 65).isValid)
        assertTrue(GeneratorOptions(length = 8).isValid)
        assertTrue(GeneratorOptions(length = 64).isValid)
    }
}
