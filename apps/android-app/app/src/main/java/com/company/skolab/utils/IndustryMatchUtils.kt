package com.company.skolab.utils

import com.company.skolab.model.IndustryOpportunity
import java.text.SimpleDateFormat
import java.util.Locale

object IndustryMatchUtils {

    /**
     * Computes a real match score (52–99) between the user's research focus and
     * an opportunity, using token-overlap across title (40%), description (25%),
     * skills (25%), and tags (10%). Deterministic — same inputs always produce
     * the same score, and it changes meaningfully as focus or opportunity changes.
     */
    fun computeMatchScore(userFocus: String, opp: IndustryOpportunity): Int {
        if (userFocus.isBlank()) return 65

        val focusTokens = tokenize(userFocus).toSet()
        if (focusTokens.isEmpty()) return 65

        val titleTokens  = tokenize(opp.title)
        val descTokens   = tokenize(opp.description)
        val skillTokens  = opp.requiredSkills.flatMap { tokenize(it) }
        val tagTokens    = opp.tags.flatMap { tokenize(it) }

        fun matchRatio(tokens: List<String>): Float {
            if (tokens.isEmpty()) return 0f
            val matches = tokens.count { t ->
                focusTokens.any { f -> t.contains(f) || f.contains(t) }
            }
            return matches.toFloat() / maxOf(tokens.size, focusTokens.size).toFloat()
        }

        val composite = matchRatio(titleTokens)  * 40f +
                        matchRatio(descTokens)   * 25f +
                        matchRatio(skillTokens)  * 25f +
                        matchRatio(tagTokens)    * 10f

        // Map [0..100] → [52..99] so even a weak match looks plausible
        return (52 + composite * 0.47f).toInt().coerceIn(52, 99)
    }

    /**
     * Returns the number of days until the deadline string, or null if the
     * deadline is open/rolling/unparseable. Negative values mean past due.
     */
    fun daysUntilDeadline(deadlineStr: String): Int? {
        val trimmed = deadlineStr.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.equals("Open Now", ignoreCase = true) ||
            trimmed.equals("Rolling", ignoreCase = true)  ||
            trimmed.equals("Ongoing", ignoreCase = true)  ||
            trimmed.equals("TBD", ignoreCase = true)) return null

        val formats = listOf(
            "MMMM d, yyyy", "MMM d, yyyy", "MMMM yyyy", "MMM yyyy",
            "yyyy-MM-dd", "d MMM yyyy", "d MMMM yyyy",
            "MM/dd/yyyy", "dd/MM/yyyy", "MM-dd-yyyy"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                val date = sdf.parse(trimmed) ?: continue
                val diffMs = date.time - System.currentTimeMillis()
                return (diffMs / (1000L * 60L * 60L * 24L)).toInt()
            } catch (_: Exception) { }
        }
        return null
    }

    /**
     * Returns a human-readable urgency label and whether it's urgent (<= 14 days).
     * Returns null if deadline is open/unparseable.
     */
    fun deadlineLabel(deadlineStr: String): Pair<String, DeadlineUrgency>? {
        val days = daysUntilDeadline(deadlineStr) ?: return null
        return when {
            days < 0       -> Pair("Expired", DeadlineUrgency.EXPIRED)
            days == 0      -> Pair("Closes today", DeadlineUrgency.CRITICAL)
            days == 1      -> Pair("1 day left", DeadlineUrgency.CRITICAL)
            days <= 7      -> Pair("$days days left", DeadlineUrgency.CRITICAL)
            days <= 14     -> Pair("$days days left", DeadlineUrgency.URGENT)
            days <= 30     -> Pair("$days days left", DeadlineUrgency.MODERATE)
            else           -> Pair(deadlineStr, DeadlineUrgency.OPEN)
        }
    }

    enum class DeadlineUrgency { CRITICAL, URGENT, MODERATE, OPEN, EXPIRED }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[\\s,;/\\-_+()\\[\\]\"'.:]+"))
            .filter { it.length > 2 && it.all { c -> c.isLetter() } }
}
