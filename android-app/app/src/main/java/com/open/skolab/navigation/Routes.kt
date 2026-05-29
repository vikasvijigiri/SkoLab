package com.open.skolab.navigation

/**
 * Routes — single source of truth for all NavHost route strings.
 *
 * Centralizing route strings here eliminates silent navigation failures caused
 * by typos in magic strings scattered across the codebase. All calls to
 * navController.navigate() and composable(route = ...) must use these constants.
 */
object Routes {
    // ── Top-level Auth & Onboarding ──────────────────────────────────────────
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val AUTH = "auth"
    const val PROFILE_SETUP = "profile_setup"

    // ── Main Tabs ─────────────────────────────────────────────────────────────
    const val DISCOVER = "discover"
    const val COLLABS = "collabs"
    const val AGENT = "agent"
    const val INDUSTRY = "industry"
    const val COLLABS_WS = "collabs_ws"

    // ── Detail Screens ────────────────────────────────────────────────────────
    const val PROFILE = "profile"
    const val PRO_WORKSPACE = "pro_workspace"
    const val LIBRARY = "library"
    const val METRICS = "metrics"
    const val PAPERS = "papers"
    const val SEARCH_PROFILES = "search_profiles"
    const val LOGIC_ENGINE = "logic_engine"
    const val DAILY_DISCOVERY = "daily_discovery"
    const val CHAT_LIST = "chat_list"
    const val CREATE_PROJECT = "create_project"

    // ── Parameterized Routes (use these to build route strings) ──────────────
    const val PAPER_DETAIL_PATTERN = "paper_detail/{paperId}"
    const val AUTHOR_DETAIL_PATTERN = "author_detail/{authorName}"
    const val READER_PATTERN = "reader/{title}/{url}"
    const val CHAT_PATTERN = "chat/{peerName}/{peerId}"
    const val COLAB_WORKSPACE_PATTERN = "colab_workspace/{projectName}"
    const val INVITE_MEMBER_PATTERN = "invite_member/{projectId}"
    const val CREATE_TASK_PATTERN = "create_task/{projectId}"
    const val EXTERNAL_INVITE_PATTERN = "external_invite/{collaboratorName}"

    fun paperDetail(id: String) = "paper_detail/$id"
    fun authorDetail(name: String) = "author_detail/$name"
    fun reader(title: String, url: String) = "reader/$title/$url"
    fun chat(peerName: String, peerId: String) = "chat/$peerName/$peerId"
    fun colabWorkspace(projectName: String) = "colab_workspace/$projectName"
    fun inviteMember(projectId: String) = "invite_member/$projectId"
    fun createTask(projectId: String) = "create_task/$projectId"
    fun externalInvite(collaboratorName: String) = "external_invite/$collaboratorName"

    /** All routes that should display the top bar + bottom nav dock. */
    val MAIN_TABS = setOf(DISCOVER, COLLABS, AGENT, INDUSTRY, COLLABS_WS)
}
