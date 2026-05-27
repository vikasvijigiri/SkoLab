package com.open.skolab.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

object InviteIntentManager {

    /**
     * Generates a pre-filled email intent to invite an external researcher to the platform.
     * @param context The Android context used to launch the intent.
     * @param researcherName The name of the researcher being invited.
     * @param researcherEmail Optional. The target email if known, otherwise blank for the user to fill in.
     */
    fun sendCollabEmailInvite(context: Context, researcherName: String, researcherEmail: String = "") {
        val subject = "Collaboration Inquiry via SkoLab"
        val body = """
            Dear Dr. $researcherName,

            I have been reading your recent research and am very interested in exploring potential synergies. 
            I'm currently working on a related project on SkoLab, a platform for academic collaboration and research intelligence.

            I would love for you to join my workspace so we can discuss potential collaboration. 
            You can access the platform and connect with my profile here: https://skolab.app/join

            Looking forward to hearing from you.

            Best regards,
            [Your Name]
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:") // Only email apps should handle this
            if (researcherEmail.isNotBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(researcherEmail))
            }
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            context.startActivity(Intent.createChooser(intent, "Send Invite via..."))
        } catch (e: Exception) {
            android.util.Log.e("InviteIntentManager", "No email app found", e)
        }
    }
}
