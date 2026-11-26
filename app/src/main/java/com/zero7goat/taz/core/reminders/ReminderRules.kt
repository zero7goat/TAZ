package com.zero7goat.taz.core.reminders

import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.fields.FieldType
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** Something with an expiry date coming up. [key] makes sure it is announced once. */
data class ExpiringItem(val key: String, val name: String, val label: String, val date: LocalDate, val whenText: String)

/** Which reminders are due. Pure Kotlin, unit tested. */
object ReminderRules {

    private val expiryWords = Regex("expir|valid until|valid thru|renew|exp\\.?\\b", RegexOption.IGNORE_CASE)
    private val monthYear = Regex("^\\s*(\\d{1,2})\\s*/\\s*(\\d{2}|\\d{4})\\s*$")

    /** Reads "2027-03-31", "03/27" or "3/2027" as a date (month/year means the month's last day). */
    fun parseExpiry(value: String): LocalDate? {
        CustomFields.parseDate(value.trim())?.let { return it }
        val m = monthYear.matchEntire(value) ?: return null
        val month = m.groupValues[1].toInt()
        if (month !in 1..12) return null
        val year = m.groupValues[2].toInt().let { if (it < 100) 2000 + it else it }
        return YearMonth.of(year, month).atEndOfMonth()
    }

    /**
     * Date (or month/year) fields labelled like an expiry date that fall within the next [days]
     * days, including today. [days] 0 turns this off.
     */
    fun expiring(logins: List<CredentialDetail>, now: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<ExpiringItem> {
        if (days <= 0) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val last = today.plusDays(days.toLong())
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        return logins.flatMap { login ->
            login.fields
                .filter { (it.type == FieldType.DATE || it.type == FieldType.TEXT) && expiryWords.containsMatchIn(it.label) }
                .mapNotNull { field ->
                    val date = parseExpiry(field.value) ?: return@mapNotNull null
                    if (date.isBefore(today) || date.isAfter(last)) return@mapNotNull null
                    val inDays = ChronoUnit.DAYS.between(today, date)
                    ExpiringItem(
                        key = "exp:${login.id}:${field.id}:$date",
                        name = if (login.isLocked) "A locked item" else login.serviceName,
                        label = field.label.ifBlank { "Expiry" }.replaceFirstChar { it.uppercase() },
                        date = date,
                        whenText = when (inDays) {
                            0L -> "today"
                            1L -> "tomorrow"
                            else -> "on ${date.format(format)} (in $inDays days)"
                        }
                    )
                }
        }.sortedBy { it.date }
    }

    /** Names of logins whose password was last changed more than [months] months ago. 0 = off. */
    fun oldPasswords(logins: List<CredentialDetail>, now: Long, months: Int): List<String> {
        if (months <= 0) return emptyList()
        val cutoff = now - months * 30L * 24 * 60 * 60 * 1000
        return logins
            .filter { it.category == Categories.LOGIN.key && it.password.isNotEmpty() }
            .filter { (it.history.firstOrNull()?.replacedAt ?: it.createdAt) < cutoff }
            .map { if (it.isLocked) "a locked login" else it.serviceName }
            .sorted()
    }

    /** At most one "old passwords" reminder a month. */
    fun oldPasswordsKey(names: List<String>, now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "old:" + YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)) + ":" + names.size
}
