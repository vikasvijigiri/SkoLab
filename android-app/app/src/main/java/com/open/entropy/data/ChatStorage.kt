package com.open.entropy.data

import android.content.Context
import com.open.entropy.network.ChatMessage
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

    fun getActiveChatPartners(): List<String> {
        val files = chatDir.listFiles { _, name -> name.startsWith("chat_") && name.endsWith(".json") }
        return files?.map { it.name.removePrefix("chat_").removeSuffix(".json") } ?: emptyList()
    }
}
