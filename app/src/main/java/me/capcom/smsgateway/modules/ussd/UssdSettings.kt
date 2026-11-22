package me.capcom.smsgateway.modules.ussd

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Réglages USSD, dans les préférences par défaut (préfixe "ussd.").
 * Volontairement NON exportés par /settings : le PIN ne doit jamais sortir du téléphone.
 */
class UssdSettings(
    private val preferences: SharedPreferences,
) {
    /** Saisi dans Paramètres > USSD. */
    val pin: String?
        get() = preferences.getString(PIN_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Ids déjà composés, conservés après redémarrage : un id connu n'est jamais recomposé. */
    fun wasDialed(id: String): Boolean = dialedIds().contains(id)

    fun markDialed(id: String) {
        val ids = (dialedIds() + id).takeLast(MAX_DIALED_IDS)
        // commit() et non apply() : l'id doit être écrit AVANT de composer le code.
        preferences.edit(commit = true) { putString(DIALED_IDS_KEY, ids.joinToString(",")) }
    }

    private fun dialedIds(): List<String> =
        preferences.getString(DIALED_IDS_KEY, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    companion object {
        const val PIN_KEY = "ussd.pin"
        private const val DIALED_IDS_KEY = "ussd.dialed_ids"
        private const val MAX_DIALED_IDS = 500
    }
}
