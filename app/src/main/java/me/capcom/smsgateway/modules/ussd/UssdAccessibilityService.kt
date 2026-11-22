package me.capcom.smsgateway.modules.ussd

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.koin.android.ext.android.inject

/**
 * Lit les boîtes de dialogue USSD du téléphone (paquet com.android.phone, cf.
 * res/xml/ussd_accessibility_service.xml) et y répond pour la session en cours.
 * Ne fait rien quand aucune session ne tourne : il ne touche jamais aux USSD composés à la main.
 *
 * À activer une fois sur le téléphone : Paramètres Android > Accessibilité > SMS Gateway USSD.
 */
class UssdAccessibilityService : AccessibilityService() {
    private val ussdService: UssdService by inject()

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ussdService.current == null) return
        val root = ussdWindowRoot(event) ?: return

        val screen = Screen()
        readScreen(root, screen)
        if (screen.buttons.isEmpty()) return // pas une boîte de dialogue

        val text = screen.texts.joinToString("\n").trim()
        Log.d(TAG, "Écran USSD (${root.packageName}) : $text")

        when (val action = ussdService.onScreen(text, screen.inputs.isNotEmpty())) {
            UssdAction.Ignore -> Unit
            is UssdAction.Reply -> {
                setText(screen.inputs.first(), action.text)
                clickButton(screen.buttons, SEND_LABELS, preferLast = true)
            }
            UssdAction.Dismiss -> clickButton(screen.buttons, DISMISS_LABELS, preferLast = false)
        }
    }

    /**
     * Racine de la fenêtre qui a produit l'événement, seulement si elle appartient au paquet USSD.
     * Surtout pas rootInActiveWindow : entre deux écrans USSD, la fenêtre active redevient
     * brièvement l'application au premier plan (SMS Gateway elle-même), qui serait lue comme un écran.
     */
    private fun ussdWindowRoot(event: AccessibilityEvent): AccessibilityNodeInfo? {
        val eventPackage = event.packageName?.toString() ?: return null
        if (eventPackage != USSD_PACKAGE) return null

        var node = event.source ?: return null
        while (true) {
            node = node.parent ?: break
        }
        return node.takeIf { it.packageName?.toString() == USSD_PACKAGE }
    }

    private class Screen {
        val texts = mutableListOf<String>()
        val inputs = mutableListOf<AccessibilityNodeInfo>()
        val buttons = mutableListOf<AccessibilityNodeInfo>()
    }

    /** Parcourt l'arbre : textes affichés, champs de saisie, boutons. Le contenu des champs n'est jamais lu. */
    private fun readScreen(node: AccessibilityNodeInfo, screen: Screen) {
        val className = node.className?.toString() ?: ""
        when {
            className.endsWith("EditText") -> screen.inputs.add(node)
            className.endsWith("Button") -> screen.buttons.add(node)
            !node.text.isNullOrBlank() -> screen.texts.add(node.text.toString())
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { readScreen(it, screen) }
        }
    }

    private fun setText(input: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            Log.w(TAG, "Saisie refusée par le champ USSD")
        }
    }

    /**
     * Clique le bouton dont le libellé est dans [labels] ; sinon le dernier (bouton positif d'une
     * boîte Android) ou le premier selon [preferLast].
     */
    private fun clickButton(buttons: List<AccessibilityNodeInfo>, labels: Set<String>, preferLast: Boolean) {
        val button = buttons.firstOrNull { it.text?.toString()?.trim()?.lowercase() in labels }
            ?: if (preferLast) buttons.last() else buttons.first()
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.w(TAG, "Clic refusé sur « ${button.text} »")
        }
    }

    companion object {
        private const val TAG = "UssdAccessibility"

        /** Paquet qui affiche les boîtes USSD ; doit rester égal à packageNames de ussd_accessibility_service.xml. */
        private const val USSD_PACKAGE = "com.android.phone"

        /** Lu par UssdService avant de composer. */
        @Volatile
        var isConnected: Boolean = false
            private set

        private val SEND_LABELS = setOf("envoyer", "send", "répondre", "reply", "valider", "ok")
        private val DISMISS_LABELS = setOf("ok", "fermer", "close", "annuler", "cancel")
    }
}
