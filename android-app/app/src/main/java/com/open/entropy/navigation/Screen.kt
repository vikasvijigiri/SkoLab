package com.open.entropy.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed class Screen {
    @Serializable
    data object Discovery : Screen()
    
    @Serializable
    data object Vault : Screen()
    
    @Serializable
    data object LogicEngine : Screen()
    
    @Serializable
    data object Profile : Screen()
}
