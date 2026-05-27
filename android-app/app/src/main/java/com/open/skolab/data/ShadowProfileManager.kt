package com.open.skolab.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Handles the creation of "Shadow Profiles" for researchers who are not yet on the platform.
 * By storing these pings, we can instantly connect them with users once they claim their profile.
 */
class ShadowProfileManager {

    private val db = FirebaseFirestore.getInstance()

    /**
     * Registers a user's interest in connecting with an external researcher.
     * 
     * @param currentUserId The ID of the user requesting the connection.
     * @param researcherOpenAlexId The OpenAlex ID of the researcher being pinged.
     * @param researcherName The name of the researcher.
     * @return true if successful, false otherwise.
     */
    suspend fun pingShadowProfile(
        currentUserId: String,
        researcherOpenAlexId: String,
        researcherName: String
    ): Boolean {
        return try {
            val shadowRef = db.collection("shadow_profiles").document(researcherOpenAlexId)
            
            // We use set with merge=true to either create the document or append to existing
            // A subcollection is typically better to store multiple requests over time.
            val requestData = hashMapOf(
                "requestingUserId" to currentUserId,
                "timestamp" to System.currentTimeMillis()
            )

            // Ensure the main document exists with basic metadata
            shadowRef.set(
                hashMapOf(
                    "researcherName" to researcherName,
                    "openAlexId" to researcherOpenAlexId,
                    "lastPinged" to System.currentTimeMillis()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()

            // Save the specific connection request inside a subcollection
            shadowRef.collection("pending_connections")
                .document(currentUserId)
                .set(requestData)
                .await()

            true
        } catch (e: Exception) {
            android.util.Log.e("ShadowProfile", "Failed to ping shadow profile", e)
            false
        }
    }
}
