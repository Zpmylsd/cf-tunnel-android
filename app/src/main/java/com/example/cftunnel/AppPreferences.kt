package com.example.cftunnel

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cftunnel_prefs", Context.MODE_PRIVATE)

    var tunnelToken: String
        get() = prefs.getString("TUNNEL_TOKEN", "") ?: ""
        set(value) = prefs.edit().putString("TUNNEL_TOKEN", value).apply()

    var autoStartEnabled: Boolean
        get() = prefs.getBoolean("AUTO_START", false)
        set(value) = prefs.edit().putBoolean("AUTO_START", value).apply()
}
