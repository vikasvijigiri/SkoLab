package com.company.skolab.utils

import com.company.skolab.model.IndustryOpportunity
import com.company.skolab.model.OpportunityType
import com.company.skolab.utils.IndustryMatchUtils.DeadlineUrgency
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class IndustryMatchUtilsTest {

    // ── computeMatchScore ────────────────────────────────────────────────────

    private fun opp(
        title: String = "",
        description: String = "",
        skills: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ) = IndustryOpportunity(
        id = "test",
        type = OpportunityType.JOB,
        title = title,
        companyOrFunder = "Test Org",
        description = description,
        requiredSkills = skills,
        tags = tags
    )

    @Test
    fun `blank userFocus returns default 65`() {
        val score = IndustryMatchUtils.computeMatchScore("", opp(title = "Machine Learning Researcher"))
        assertEquals(65, score)
    }

    @Test
    fun `exact focus match in title gives high score`() {
        val score = IndustryMatchUtils.computeMatchScore(
            "machine learning",
            opp(title = "Machine Learning Research Scientist")
        )
        assertTrue("Score $score should be >= 75", score >= 75)
    }

    @Test
    fun `completely unrelated focus gives low score`() {
        val score = IndustryMatchUtils.computeMatchScore(
            "marine biology oceanography",
            opp(title = "Quantum Computing Hardware Engineer")
        )
        assertTrue("Score $score should be < 70", score < 70)
    }

    @Test
    fun `score is always in range 52-99`() {
        val cases = listOf(
            Pair("", ""),
            Pair("nlp transformers bert", "Computer Vision Deep Learning"),
            Pair("genomics bioinformatics", "Genomics Bioinformatics Pipeline Engineer"),
            Pair("x", "x")
        )
        for ((focus, title) in cases) {
            val score = IndustryMatchUtils.computeMatchScore(focus, opp(title = title))
            assertTrue("Score $score out of range for focus='$focus', title='$title'",
                score in 52..99)
        }
    }

    @Test
    fun `skills field contributes to score`() {
        val withSkills = IndustryMatchUtils.computeMatchScore(
            "pytorch deep learning",
            opp(title = "Research Intern", skills = listOf("PyTorch", "Deep Learning", "Python"))
        )
        val withoutSkills = IndustryMatchUtils.computeMatchScore(
            "pytorch deep learning",
            opp(title = "Research Intern")
        )
        assertTrue("Skills should improve score: withSkills=$withSkills, withoutSkills=$withoutSkills",
            withSkills >= withoutSkills)
    }

    @Test
    fun `same inputs always produce same score (deterministic)`() {
        val focus = "natural language processing"
        val opportunity = opp(
            title = "NLP Research Scientist",
            description = "Research on natural language understanding and generation",
            skills = listOf("NLP", "Python", "Transformers")
        )
        val score1 = IndustryMatchUtils.computeMatchScore(focus, opportunity)
        val score2 = IndustryMatchUtils.computeMatchScore(focus, opportunity)
        assertEquals("Score should be deterministic", score1, score2)
    }

    // ── daysUntilDeadline ────────────────────────────────────────────────────

    private fun dateInDays(days: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, days)
        return SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(cal.time)
    }

    @Test
    fun `blank deadline returns null`() {
        assertNull(IndustryMatchUtils.daysUntilDeadline(""))
    }

    @Test
    fun `rolling deadline returns null`() {
        assertNull(IndustryMatchUtils.daysUntilDeadline("Rolling"))
        assertNull(IndustryMatchUtils.daysUntilDeadline("Open Now"))
        assertNull(IndustryMatchUtils.daysUntilDeadline("Ongoing"))
        assertNull(IndustryMatchUtils.daysUntilDeadline("TBD"))
    }

    @Test
    fun `future deadline returns positive days`() {
        val days = IndustryMatchUtils.daysUntilDeadline(dateInDays(30))
        assertNotNull(days)
        assertTrue("Expected ~30 days, got $days", days!! in 28..32)
    }

    @Test
    fun `past deadline returns negative days`() {
        val days = IndustryMatchUtils.daysUntilDeadline(dateInDays(-5))
        assertNotNull(days)
        assertTrue("Expected negative, got $days", days!! < 0)
    }

    @Test
    fun `yyyy-MM-dd format is parsed`() {
        assertNotNull(IndustryMatchUtils.daysUntilDeadline(dateInDays(10)))
    }

    @Test
    fun `month name format is parsed`() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 20)
        val formatted = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(cal.time)
        val days = IndustryMatchUtils.daysUntilDeadline(formatted)
        assertNotNull("Could not parse '$formatted'", days)
        assertTrue("Expected ~20 days, got $days", days!! in 18..22)
    }

    @Test
    fun `unparseable string returns null`() {
        assertNull(IndustryMatchUtils.daysUntilDeadline("Next semester"))
        assertNull(IndustryMatchUtils.daysUntilDeadline("ASAP"))
    }

    // ── deadlineLabel ────────────────────────────────────────────────────────

    @Test
    fun `critical urgency for 3 days left`() {
        val result = IndustryMatchUtils.deadlineLabel(dateInDays(3))
        assertNotNull(result)
        assertEquals(DeadlineUrgency.CRITICAL, result!!.second)
        assertEquals("3 days left", result.first)
    }

    @Test
    fun `urgent urgency for 10 days left`() {
        val result = IndustryMatchUtils.deadlineLabel(dateInDays(10))
        assertNotNull(result)
        assertEquals(DeadlineUrgency.URGENT, result!!.second)
    }

    @Test
    fun `moderate urgency for 20 days left`() {
        val result = IndustryMatchUtils.deadlineLabel(dateInDays(20))
        assertNotNull(result)
        assertEquals(DeadlineUrgency.MODERATE, result!!.second)
    }

    @Test
    fun `open urgency for 60 days left`() {
        val result = IndustryMatchUtils.deadlineLabel(dateInDays(60))
        assertNotNull(result)
        assertEquals(DeadlineUrgency.OPEN, result!!.second)
    }

    @Test
    fun `expired urgency for past deadline`() {
        val result = IndustryMatchUtils.deadlineLabel(dateInDays(-3))
        assertNotNull(result)
        assertEquals(DeadlineUrgency.EXPIRED, result!!.second)
        assertEquals("Expired", result.first)
    }

    @Test
    fun `closes today label for 0 days`() {
        // Use today's date
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())
        val result = IndustryMatchUtils.deadlineLabel(today)
        assertNotNull(result)
        assertEquals(DeadlineUrgency.CRITICAL, result!!.second)
        assertEquals("Closes today", result.first)
    }
}
