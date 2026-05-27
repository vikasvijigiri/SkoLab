package com.open.skolab.data

import android.content.Context
import com.open.skolab.network.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

class ChatStorage(private val context: Context, private val userUid: String) {
    private val chatDir = File(context.filesDir, "chats_$userUid").apply { mkdirs() }

    fun getChatHistory(peerId: String): List<ChatMessage> {
        val cleanId = peerId.substringAfterLast("/")
        val file = File(chatDir, "chat_$cleanId.json")
        if (!file.exists()) return emptyList()
        return try {
            Json.decodeFromString(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChatHistory(peerId: String, messages: List<ChatMessage>) {
        val cleanId = peerId.substringAfterLast("/")
        val file = File(chatDir, "chat_$cleanId.json")
        try {
            val json = Json.encodeToString(messages)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLastMessage(peerId: String): ChatMessage? {
        return getChatHistory(peerId).lastOrNull()
    }

    fun deleteChatHistory(peerId: String) {
        val cleanId = peerId.substringAfterLast("/")
        val file = File(chatDir, "chat_$cleanId.json")
        if (file.exists()) {
            file.delete()
        }
    }

    fun getAgentChats(): List<String> {
        val files = chatDir.listFiles { _, name -> name.startsWith("chat_jarvis_agent_") && name.endsWith(".json") }
        return files?.sortedByDescending { it.lastModified() }?.map { it.name.removePrefix("chat_").removeSuffix(".json") } ?: emptyList()
    }

    fun getActiveChatPartners(): List<String> {
        val files = chatDir.listFiles { _, name -> name.startsWith("chat_") && name.endsWith(".json") }
        // Exclude jarvis agent session chats from connections list so they don't pollute Direct Messages
        return files?.filter { !it.name.startsWith("chat_jarvis_agent_") }?.map { it.name.removePrefix("chat_").removeSuffix(".json") } ?: emptyList()
    }
}

