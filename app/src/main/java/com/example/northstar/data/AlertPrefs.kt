package com.example.northstar.data

import android.content.Context

/** Preference for showing phone message notifications on the dash (visual-first — the
 *  rider has no headset, so the dash card IS the alert). Off by default. */
object AlertPrefs {
    private const val PREFS = "alert_prefs"
    private const val KEY_MESSAGES = "messages_on_dash"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun messagesOnDash(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MESSAGES, false)

    fun setMessagesOnDash(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MESSAGES, on).apply()
    }
}
