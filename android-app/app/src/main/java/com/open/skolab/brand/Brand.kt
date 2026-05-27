package com.open.skolab.brand

import android.content.Context
import com.open.skolab.R

/**
 * Single entry point for SkoLab user-facing brand strings.
 * Prefer these over hardcoded names in Compose screens.
 */
object Brand {
    fun name(context: Context): String = context.getString(R.string.brand_name)
    fun tagline(context: Context): String = context.getString(R.string.brand_tagline)
    fun footer(context: Context): String = context.getString(R.string.brand_footer)
    fun intelLabel(context: Context): String = context.getString(R.string.brand_intel_label)
    fun indexLabel(context: Context): String = context.getString(R.string.brand_index_label)
    fun websiteUrl(context: Context): String = context.getString(R.string.brand_website_url)
    fun contactEmail(context: Context): String = context.getString(R.string.brand_contact_email)
}
