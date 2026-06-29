package com.company.skolab.viewmodel

import androidx.lifecycle.ViewModel
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.company.skolab.model.GlobalResearcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest

class ResearcherViewModel : ViewModel() {
    private val db = FirebaseFirestore.getInstance()
    private val _selectedField = MutableStateFlow("All")
    val selectedField: StateFlow<String> = _selectedField

    // Real-time stream of researchers filtered by field
    @OptIn(ExperimentalCoroutinesApi::class)
    val researchers: Flow<List<GlobalResearcher>> = _selectedField.flatMapLatest { field ->
        callbackFlow {
            val query = if (field == "All") {
                db.collection("global_researchers")
                    .orderBy("innovation_score", Query.Direction.DESCENDING)
                    .limit(50)
            } else {
                db.collection("global_researchers")
                    .whereEqualTo("field_of_study", field)
                    .orderBy("innovation_score", Query.Direction.DESCENDING)
                    .limit(50)
            }

            val listener = query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("FirestoreError", "Error fetching researchers: ${error.message}")
                    trySend(emptyList()) // Send empty list instead of crashing
                    return@addSnapshotListener
                }
                val data = snapshot?.toObjects(GlobalResearcher::class.java) ?: emptyList()
                trySend(data)
            }
            awaitClose { listener.remove() }
        }
    }

    fun setField(field: String) {
        _selectedField.value = field
    }
}
