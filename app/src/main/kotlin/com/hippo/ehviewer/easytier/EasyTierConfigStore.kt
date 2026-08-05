package com.hippo.ehviewer.easytier

import android.content.Context

class EasyTierConfigStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadToml(): String = prefs.getString(KEY_TOML, null) ?: EasyTierTomlCodec.defaultToml()

    fun loadUiState(): EasyTierConfigUiState = EasyTierTomlCodec.parseConfig(loadToml())

    fun saveUiState(config: EasyTierConfigUiState) {
        prefs.edit()
            .putString(KEY_TOML, EasyTierTomlCodec.build(config))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "easytier_preferences"
        private const val KEY_TOML = "toml_config_string"
    }
}
