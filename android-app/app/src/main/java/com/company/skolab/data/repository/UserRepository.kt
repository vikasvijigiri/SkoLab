package com.company.skolab.data.repository

import com.company.skolab.model.SkoLabUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class UserRepository {
    private val db = FirebaseFirestore.getInstance()

    /**
     * Replaces the tight-coupled UI snapshot listener with a cold Flow
     * emitting immutable state, avoiding manual UI lifecycle management.
     */
    fun getGlobalUsersPresence(): Flow<Map<String, SkoLabUser>> = callbackFlow {
        val listener = db.collection("users").addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val userMap = mutableMapOf<String, SkoLabUser>()
                for (doc in snapshot.documents) {
                    val uid = doc.id
                    val isOnline = doc.getBoolean("isOnline") ?: false
                    val lastActive = doc.getLong("lastActive") ?: 0L
                    userMap[uid] = SkoLabUser(
                        uid = uid,
                        name = doc.getString("name") ?: "",
                        isOnline = isOnline,
                        lastActive = lastActive
                    )
                }
                // Emit new immutable state to ViewModel
                trySend(userMap)
            }
        }
        
        // Clean up when the collector (ViewModel) cancels the flow
        awaitClose { listener.remove() }
    }
}
