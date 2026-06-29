package com.company.skolab.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The 9-dimensional intelligence extracted by the Research Intelligence Agent
 * after reading the FULL paper text (PDF when available).
 *
 * Maps 1:1 to the backend /analyze_paper response schema.
 */
@Serializable
data class PaperIntelligence(
    /** One-sentence plain-English summary (≤35 words). */
    @SerialName("tldr")           val tldr: String = "",

    /** 4–6 key discoveries with actual numbers, bold terms, LaTeX formulas. */
    @SerialName("key_findings")   val keyFindings: List<String> = emptyList(),

    /** Specific algorithms/methods/statistical tests used in this paper. */
    @SerialName("techniques")     val techniques: List<String> = emptyList(),

    /** Named tools, frameworks, datasets, instruments used. */
    @SerialName("tools_and_software") val toolsAndSoftware: List<String> = emptyList(),

    /** Prerequisite scientific concepts (what the reader needs to know). */
    @SerialName("core_concepts")  val coreConcepts: List<String> = emptyList(),

    /** Key mathematical expressions in LaTeX $$...$$ format. */
    @SerialName("formulas")       val formulas: List<String> = emptyList(),

    /** Honest limitations — assumptions, constraints, things not tested. */
    @SerialName("limitations")    val limitations: List<String> = emptyList(),

    /** Concrete real-world application explanation (2–4 sentences). */
    @SerialName("real_world_impact") val realWorldImpact: String = "",

    /** What experiments or extensions naturally follow. */
    @SerialName("future_directions") val futureDirections: List<String> = emptyList(),

    /**
     * Confidence level based on how much paper text was available:
     * "High" = full PDF read | "Medium" = abstract only | "Low" = title only
     */
    @SerialName("confidence")     val confidence: String = "Medium",

    /**
     * Source of the text used: "full_text_oa", "full_text_arxiv",
     * "full_text_unpaywall", "full_text_s2", or "abstract_only".
     */
    @SerialName("text_source")    val textSource: String = "abstract_only"
) {
    val isFullText: Boolean get() = textSource.startsWith("full_text")
    val confidenceColor: String get() = when (confidence) {
        "High"   -> "teal"
        "Medium" -> "amber"
        else     -> "muted"
    }
}
