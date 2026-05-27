package com.open.skolab.state

import com.open.skolab.network.AuthorResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActiveResearcherState {
    private val _activeAuthor = MutableStateFlow<AuthorResponse?>(null)
    val activeAuthor: StateFlow<AuthorResponse?> = _activeAuthor.asStateFlow()

    fun setActiveAuthor(author: AuthorResponse?) {
        _activeAuthor.value = author
    }

    fun clear() {
        _activeAuthor.value = null
    }
}
