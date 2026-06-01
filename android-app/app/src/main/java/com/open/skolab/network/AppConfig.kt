package com.open.skolab.network

/**
 * AppConfig — single source of truth for all client-side configuration constants.
 *
 * Nothing in this file is environment-specific (IPs, ports, etc.).
 * These are stable protocol constants and service identifiers that MUST
 * match the values configured on the backend (app/config.py / .env).
 */
object AppConfig {
    /** mDNS service type — must match backend MDNS_SERVICE_TYPE env var. */
    const val MDNS_SERVICE_TYPE = "_http._tcp"

    /**
     * mDNS service name — must match backend MDNS_SERVICE_NAME env var.
     * The Android NsdManager finds the backend by this name on the LAN.
     */
    const val MDNS_SERVICE_NAME = "SkoLabBackend"

    /**
     * How long (ms) the app will wait for mDNS discovery before showing
     * an "offline / backend not found" state to the user.
     */
    const val DISCOVERY_TIMEOUT_MS = 10_000L

    /**
     * Fallback academic mailto identifier for external OpenAlex requests.
     */
    const val OPENALEX_MAILTO = "support@skolab.open"
}
