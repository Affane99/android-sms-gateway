package me.capcom.smsgateway.modules.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent
import java.text.Normalizer
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Id déjà composé dont l'état a été perdu (redémarrage) : le serveur doit se fier au SMS. */
class UssdAlreadyDialedException(id: String) :
    Exception("La session $id a déjà été composée ; son état est inconnu, se fier au SMS de l'opérateur.")

/**
 * Exécute les sessions USSD une par une (le téléphone ne peut en tenir qu'une à la fois).
 *
 * Le service ne CONFIRME aucun transfert : il compose, répond aux menus et rapporte ce que
 * l'opérateur a affiché. La preuve d'un transfert reste le SMS de l'opérateur, analysé par le serveur.
 * Un ussd:failed (délai dépassé surtout) ne prouve donc PAS que l'argent n'est pas parti.
 */
class UssdService(
    private val context: Context,
    private val settings: UssdSettings,
    private val webHooksService: WebHooksService,
) {
    private val sessions = ConcurrentHashMap<String, UssdSession>()
    private val queue = Channel<UssdSession>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Session en cours, lue par le service d'accessibilité. */
    @Volatile
    var current: UssdSession? = null
        private set

    /** Dernier écran traité : les événements d'accessibilité arrivent plusieurs fois pour le même écran. */
    @Volatile
    private var lastScreen: String? = null

    init {
        scope.launch {
            for (session in queue) execute(session)
        }
    }

    // ── API ──────────────────────────────────────────────────────────────────

    @Synchronized
    fun enqueue(request: UssdRequest): UssdSession {
        val id = validateRequest(request)

        // Idempotence : même id, même session. Jamais de seconde composition.
        sessions[id]?.let { return it }
        if (settings.wasDialed(id)) throw UssdAlreadyDialedException(id)

        val session = UssdSession(
            id = id,
            code = request.code!!.trim(),
            steps = request.steps ?: emptyList(),
            simNumber = request.simNumber,
            timeoutSeconds = request.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS,
        )
        sessions[id] = session
        purgeFinishedSessions()
        queue.trySend(session)
        return session
    }

    fun get(id: String): UssdSession? = sessions[id]

    // ── Écrans (appelé par UssdAccessibilityService) ─────────────────────────

    /**
     * Décide quoi faire de l'écran affiché. [hasInput] : l'écran attend une saisie (menu).
     * Règles : une étape par écran de saisie, et seulement si l'écran contient le texte attendu ;
     * la session réussit si toutes les étapes sont faites ET qu'un écran sans saisie (message final) suit.
     */
    fun onScreen(text: String, hasInput: Boolean): UssdAction {
        val session = current ?: return UssdAction.Ignore
        if (session.state != UssdState.Running) return UssdAction.Ignore
        if (text.isBlank() || text == lastScreen) return UssdAction.Ignore
        if (isProgressScreen(text)) return UssdAction.Ignore

        lastScreen = text
        session.screens.add(text)
        val remaining = session.steps.size - session.stepsDone

        return when {
            hasInput && remaining > 0 -> {
                val step = session.steps[session.stepsDone]
                if (!screenMatches(text, step.expect)) {
                    finish(
                        session, UssdState.Failed, finalMessage = text,
                        error = "Écran inattendu à l'étape ${session.stepsDone + 1} (attendu : « ${step.expect} »). Rien n'a été saisi."
                    )
                    return UssdAction.Dismiss
                }
                session.stepsDone++
                UssdAction.Reply(step.answer!!.replace(PIN_PLACEHOLDER, settings.pin ?: ""))
            }

            hasInput -> {
                finish(session, UssdState.Failed, finalMessage = text, error = "Menu inattendu : plus aucune étape prévue.")
                UssdAction.Dismiss
            }

            remaining > 0 -> {
                finish(
                    session, UssdState.Failed, finalMessage = text,
                    error = "Session terminée par l'opérateur à l'étape ${session.stepsDone + 1}."
                )
                UssdAction.Dismiss
            }

            else -> {
                finish(session, UssdState.Completed, finalMessage = text)
                UssdAction.Dismiss
            }
        }
    }

    // ── Exécution ────────────────────────────────────────────────────────────

    private suspend fun execute(session: UssdSession) {
        val problem = checkReadiness(session)
        if (problem != null) {
            finish(session, UssdState.Failed, error = problem)
            return
        }

        session.state = UssdState.Running
        session.startedAt = Date()
        lastScreen = null
        current = session

        try {
            settings.markDialed(session.id)
            withContext(Dispatchers.Main) { dial(session) }

            val ended = withTimeoutOrNull(session.timeoutSeconds * 1000L) { session.done.await() }
            if (ended == null) {
                finish(
                    session, UssdState.Failed,
                    error = "Délai dépassé (${session.timeoutSeconds} s) : se fier au SMS de l'opérateur."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session ${session.id} en échec", e)
            finish(session, UssdState.Failed, error = e.message ?: e.javaClass.simpleName)
        } finally {
            current = null
        }
    }

    private fun dial(session: UssdSession) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(session.code)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        phoneAccountFor(session.simNumber)?.let {
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
        }
        // Lancement depuis l'arrière-plan autorisé : le service d'accessibilité actif en exempte l'application.
        context.startActivity(intent)
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE vérifiée dans checkReadiness
    private fun phoneAccountFor(simNumber: Int?): PhoneAccountHandle? {
        if (simNumber == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val telecom = context.getSystemService(TelecomManager::class.java)
        return telecom.callCapablePhoneAccounts.getOrNull(simNumber - 1)
            ?: throw IllegalArgumentException("SIM $simNumber introuvable sur ce téléphone.")
    }

    @Synchronized
    private fun finish(
        session: UssdSession,
        state: UssdState,
        finalMessage: String? = null,
        error: String? = null,
    ) {
        if (session.isFinished) return

        session.state = state
        session.finalMessage = finalMessage
        session.error = error
        session.finishedAt = Date()
        session.done.complete(Unit)

        val event = if (state == UssdState.Completed) WebHookEvent.UssdCompleted else WebHookEvent.UssdFailed
        val payload = session.toResponse()
        // Hors du fil principal : emit() interroge la base des webhooks.
        scope.launch {
            try {
                webHooksService.emit(context, event, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Webhook ${event.value} non émis pour ${session.id}", e)
            }
        }
    }

    // ── Validations ──────────────────────────────────────────────────────────

    /** Renvoie l'id de la session. IllegalArgumentException → HTTP 400. */
    private fun validateRequest(request: UssdRequest): String {
        val code = request.code?.trim()
        require(!code.isNullOrEmpty()) { "code est obligatoire." }
        require(CODE_REGEX.matches(code)) { "code invalide : chiffres, * et # uniquement, terminé par #." }

        val id = request.id?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        require(ID_REGEX.matches(id)) { "id invalide : 1 à 64 caractères parmi A-Z, a-z, 0-9, - et _." }

        val steps = request.steps ?: emptyList()
        require(steps.size <= MAX_STEPS) { "Au plus $MAX_STEPS étapes." }
        steps.forEachIndexed { index, step ->
            val answer = step.answer
            require(!answer.isNullOrBlank()) { "steps[$index].answer est vide." }
            require(answer.length <= MAX_ANSWER_LENGTH) { "steps[$index].answer dépasse $MAX_ANSWER_LENGTH caractères." }
            step.expect?.let { require(it.isNotBlank()) { "steps[$index].expect est vide : l'omettre ou le remplir." } }
        }
        if (steps.any { it.answer!!.contains(PIN_PLACEHOLDER) }) {
            require(settings.pin != null) { "Code PIN non réglé sur le téléphone (Paramètres > USSD)." }
        }

        request.simNumber?.let { require(it >= 1) { "simNumber commence à 1." } }
        request.timeoutSeconds?.let {
            require(it in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
                "timeoutSeconds entre $MIN_TIMEOUT_SECONDS et $MAX_TIMEOUT_SECONDS."
            }
        }
        return id
    }

    /** Ce qui empêcherait la session d'aboutir, vérifié juste avant de composer. */
    private fun checkReadiness(session: UssdSession): String? {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return "Permission CALL_PHONE refusée sur le téléphone."
        }
        if (session.simNumber != null && !hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            return "Permission READ_PHONE_STATE refusée : impossible de choisir la SIM."
        }
        if (!UssdAccessibilityService.isConnected) {
            return "Service d'accessibilité USSD désactivé (Paramètres Android > Accessibilité)."
        }
        return null
    }

    /** L'écran contient-il le texte attendu ? Casse, accents et espaces (retours à la ligne compris) ignorés. */
    private fun screenMatches(screen: String, expect: String?): Boolean =
        expect == null || normalize(screen).contains(normalize(expect))

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
            .lowercase()
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Boîte « Code USSD en cours… » affichée entre deux écrans : ce n'est pas une réponse de l'opérateur. */
    private fun isProgressScreen(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("ussd") && PROGRESS_WORDS.any { lower.contains(it) }
    }

    /** Garde la mémoire bornée : les sessions terminées les plus anciennes sont oubliées. */
    private fun purgeFinishedSessions() {
        if (sessions.size <= MAX_SESSIONS_IN_MEMORY) return
        sessions.values
            .filter { it.isFinished }
            .sortedBy { it.createdAt }
            .take(sessions.size - MAX_SESSIONS_IN_MEMORY)
            .forEach { sessions.remove(it.id) }
    }

    companion object {
        private const val TAG = "UssdService"

        const val PIN_PLACEHOLDER = "{PIN}"

        private const val DEFAULT_TIMEOUT_SECONDS = 90
        private const val MIN_TIMEOUT_SECONDS = 10
        private const val MAX_TIMEOUT_SECONDS = 300
        private const val MAX_STEPS = 15
        private const val MAX_ANSWER_LENGTH = 160
        private const val MAX_SESSIONS_IN_MEMORY = 200

        private val CODE_REGEX = Regex("^[0-9*#]+#$")
        private val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val PROGRESS_WORDS = listOf("cours", "running", "exécution", "execution")
    }
}
