package com.company.skolab.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.company.skolab.model.SparkMessage
import com.company.skolab.model.SparkSession
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SparkUiState(
    val isOnline: Boolean = false,
    val activeSession: SparkSession? = null,
    val incomingRequests: List<SparkSession> = emptyList(),
    val chatMessages: List<SparkMessage> = emptyList(),
    val isBroadcasting: Boolean = false,
    val myCurrentBroadcastId: String? = null,
    val walletTokens: Int = 120,
    val rating: Float = 4.9f
)

class SparkViewModel : ViewModel() {

    private val db = FirebaseFirestore.getInstance()
    private val _uiState = MutableStateFlow(SparkUiState())
    val uiState: StateFlow<SparkUiState> = _uiState.asStateFlow()

    private var incomingListener: ListenerRegistration? = null
    private var activeSessionListener: ListenerRegistration? = null
    private var chatMessagesListener: ListenerRegistration? = null
    private var timerJob: Job? = null

    // Clean up listeners on clear
    override fun onCleared() {
        super.onCleared()
        incomingListener?.remove()
        activeSessionListener?.remove()
        chatMessagesListener?.remove()
        timerJob?.cancel()
    }

    /**
     * Toggles the user's online state to receive incoming helper requests.
     */
    fun toggleOnline(userId: String, userName: String, focus: String, online: Boolean) {
        _uiState.update { it.copy(isOnline = online) }
        
        // Update status in Firestore
        if (userId.isNotEmpty()) {
            val statusMap = mapOf(
                "userId" to userId,
                "name" to userName,
                "focus" to focus,
                "isOnline" to online,
                "lastActive" to System.currentTimeMillis()
            )
            db.collection("spark_users").document(userId).set(statusMap)
        }

        if (online) {
            startListeningForIncomingRequests(focus)
        } else {
            stopListeningForIncomingRequests()
        }
    }

    /**
     * Listens for pending spark requests matching the user's research focus area.
     */
    private fun startListeningForIncomingRequests(userFocus: String) {
        stopListeningForIncomingRequests()
        
        incomingListener = db.collection("spark_sessions")
            .whereEqualTo("status", "PENDING")
            .limit(10)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("SparkViewModel", "Error listening for incoming sessions", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val requests = snapshot.toObjects(SparkSession::class.java)
                    // Optional filter: only show requests matching user's field or generic tags
                    val filteredRequests = requests.filter { request ->
                        request.seekerId != _uiState.value.activeSession?.seekerId &&
                        (userFocus.isBlank() || request.tags.isEmpty() || request.tags.any { tag ->
                            tag.contains(userFocus, ignoreCase = true) || userFocus.contains(tag, ignoreCase = true)
                        })
                    }
                    _uiState.update { it.copy(incomingRequests = filteredRequests) }
                }
            }
    }

    private fun stopListeningForIncomingRequests() {
        incomingListener?.remove()
        incomingListener = null
        _uiState.update { it.copy(incomingRequests = emptyList()) }
    }

    /**
     * Emits a new Spark request ("Hails a ride").
     */
    fun hailHelper(
        userId: String,
        userName: String,
        topic: String,
        bounty: String,
        tags: List<String>
    ) {
        if (userId.isEmpty()) return

        val docRef = db.collection("spark_sessions").document()
        val session = SparkSession(
            id = docRef.id,
            seekerId = userId,
            seekerName = userName,
            topic = topic,
            bounty = bounty,
            tags = tags,
            status = "PENDING",
            createdAt = System.currentTimeMillis(),
            timerRemainingSeconds = 600
        )

        _uiState.update {
            it.copy(
                isBroadcasting = true,
                myCurrentBroadcastId = docRef.id
            )
        }

        docRef.set(session)
            .addOnSuccessListener {
                listenToActiveSession(docRef.id)
            }
            .addOnFailureListener { e ->
                Log.e("SparkViewModel", "Failed to create spark session", e)
                _uiState.update { it.copy(isBroadcasting = false, myCurrentBroadcastId = null) }
            }
    }

    /**
     * Accepts a pending Spark request ("Picks up a ride").
     */
    fun acceptSpark(sessionId: String, helperId: String, helperName: String) {
        val docRef = db.collection("spark_sessions").document(sessionId)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            val currentStatus = snapshot.getString("status")
            if (currentStatus == "PENDING") {
                transaction.update(docRef, "helperId", helperId)
                transaction.update(docRef, "helperName", helperName)
                transaction.update(docRef, "status", "ACTIVE")
                transaction.update(docRef, "acceptedAt", System.currentTimeMillis())
            }
        }.addOnSuccessListener {
            listenToActiveSession(sessionId)
        }.addOnFailureListener { e ->
            Log.e("SparkViewModel", "Failed to accept spark session", e)
        }
    }

    /**
     * Listens to the state of the active Spark session.
     */
    fun listenToActiveSession(sessionId: String) {
        activeSessionListener?.remove()
        
        activeSessionListener = db.collection("spark_sessions").document(sessionId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("SparkViewModel", "Error listening to active session", error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val session = snapshot.toObject(SparkSession::class.java)
                    if (session != null) {
                        _uiState.update { 
                            it.copy(
                                activeSession = session,
                                isBroadcasting = session.status == "PENDING"
                            )
                        }

                        if (session.status == "ACTIVE") {
                            startTimer()
                            listenToChatMessages(sessionId)
                        } else if (session.status == "RESOLVED" || session.status == "CANCELLED") {
                            stopTimer()
                            chatMessagesListener?.remove()
                        }
                    }
                } else {
                    // Session deleted or doesn't exist
                    _uiState.update { 
                        it.copy(
                            activeSession = null,
                            isBroadcasting = false,
                            myCurrentBroadcastId = null
                        )
                    }
                    stopTimer()
                }
            }
    }

    /**
     * Sends a message inside the active Spark session.
     */
    fun sendChatMessage(text: String, senderId: String, senderName: String) {
        val session = _uiState.value.activeSession ?: return
        val docRef = db.collection("spark_sessions")
            .document(session.id)
            .collection("messages")
            .document()

        val msg = SparkMessage(
            id = docRef.id,
            senderId = senderId,
            senderName = senderName,
            messageText = text,
            timestamp = System.currentTimeMillis()
        )

        docRef.set(msg)
    }

    /**
     * Updates the content of the collaborative scratchpad.
     */
    fun updateScratchpad(content: String) {
        val session = _uiState.value.activeSession ?: return
        db.collection("spark_sessions")
            .document(session.id)
            .update("scratchpadContent", content)
    }

    /**
     * Resolves the active Spark session (Seeker pays helper).
     */
    fun resolveSpark() {
        val session = _uiState.value.activeSession ?: return
        db.collection("spark_sessions")
            .document(session.id)
            .update("status", "RESOLVED")

        // Deduct tokens local simulation
        if (session.bounty.contains("Tokens")) {
            val amount = session.bounty.filter { it.isDigit() }.toIntOrNull() ?: 10
            _uiState.update { it.copy(walletTokens = (it.walletTokens - amount).coerceAtLeast(0)) }
        }
    }

    /**
     * Extends the countdown timer by 3 minutes.
     */
    fun extendTimer() {
        val session = _uiState.value.activeSession ?: return
        val newTime = session.timerRemainingSeconds + 180
        db.collection("spark_sessions")
            .document(session.id)
            .update("timerRemainingSeconds", newTime)
    }

    /**
     * Cancels / exits the active Spark session.
     */
    fun cancelSession() {
        val session = _uiState.value.activeSession ?: return
        db.collection("spark_sessions")
            .document(session.id)
            .update("status", "CANCELLED")

        _uiState.update { 
            it.copy(
                activeSession = null,
                isBroadcasting = false,
                myCurrentBroadcastId = null
            )
        }
        stopTimer()
    }

    /**
     * Clear current session from state (after completion page view).
     */
    fun clearSessionState() {
        activeSessionListener?.remove()
        activeSessionListener = null
        chatMessagesListener?.remove()
        chatMessagesListener = null
        _uiState.update { 
            it.copy(
                activeSession = null,
                isBroadcasting = false,
                myCurrentBroadcastId = null,
                chatMessages = emptyList()
            )
        }
        stopTimer()
    }

    /**
     * Listens for chat messages inside the session.
     */
    private fun listenToChatMessages(sessionId: String) {
        chatMessagesListener?.remove()
        chatMessagesListener = db.collection("spark_sessions")
            .document(sessionId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("SparkViewModel", "Error listening to messages", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val msgs = snapshot.toObjects(SparkMessage::class.java)
                    _uiState.update { it.copy(chatMessages = msgs) }
                }
            }
    }

    /**
     * Starts the countdown timer.
     */
    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val session = _uiState.value.activeSession ?: break
                if (session.status == "ACTIVE") {
                    val remaining = session.timerRemainingSeconds - 1
                    if (remaining <= 0) {
                        db.collection("spark_sessions")
                            .document(session.id)
                            .update(
                                "timerRemainingSeconds", 0,
                                "status", "RESOLVED"
                            )
                        break
                    } else {
                        // Local update to avoid excessive Firestore writes, sync write every 5 seconds
                        if (remaining % 5 == 0) {
                            db.collection("spark_sessions")
                                .document(session.id)
                                .update("timerRemainingSeconds", remaining)
                        } else {
                            _uiState.update { 
                                it.copy(activeSession = session.copy(timerRemainingSeconds = remaining))
                            }
                        }
                    }
                } else {
                    break
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }
}
