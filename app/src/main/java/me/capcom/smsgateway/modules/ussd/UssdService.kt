package me.capcom.smsgateway.modules.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.capcom.smsgateway.helpers.SubscriptionsHelper
import me.capcom.smsgateway.modules.webhooks.WebHooksService
import me.capcom.smsgateway.modules.webhooks.domain.WebHookEvent
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Id déjà composé dont l'état a été perdu (redémarrage) : le serveur doit se fier au SMS. */
class UssdAlreadyDialedException(id: String) :
    Exception("La session $id a déjà été composée ; son état est inconnu, se fier au SMS de l'opérateur.")

/**
 * Compose des codes USSD complets en une fois (TelephonyManager.sendUssdRequest, Android 8+),
 * une session à la fois, et rapporte la réponse de l'opérateur.
 *
 * Le téléphone ne décide rien : le serveur fournit le code, vérifie la réponse, et seule la
 * preuve de l'opérateur (SMS) confirme un mouvement d'argent.
 */
class UssdService(
    private val context: Context,
    private val settings: UssdSettings,
    private val webHooksService: WebHooksService,
) {
    private val sessions = ConcurrentHashMap<String, UssdSession>()
    private val queue = Channel<UssdSession>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
            simNumber = request.simNumber,
            timeoutSeconds = request.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS,
        )
        sessions[id] = session
        purgeFinishedSessions()
        queue.trySend(session)
        return session
    }

    fun get(id: String): UssdSession? = sessions[id]

    // ── Exécution ────────────────────────────────────────────────────────────

    private suspend fun execute(session: UssdSession) {
        val problem = checkReadiness(session)
        if (problem != null) {
            finish(session, UssdState.Failed, error = problem)
            return
        }

        session.state = UssdState.Running
        session.startedAt = Date()
        try {
            settings.markDialed(session.id)
            val outcome = withTimeoutOrNull(session.timeoutSeconds * 1000L) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) send(session) else Outcome.Failure(UNSUPPORTED)
            }
            when (outcome) {
                null -> finish(session, UssdState.Failed, error = "Pas de réponse de l'opérateur en ${session.timeoutSeconds} s.")
                is Outcome.Response -> finish(session, UssdState.Completed, response = outcome.text)
                is Outcome.Failure -> finish(
                    session, UssdState.Failed, failureCode = outcome.code,
                    error = "Échec USSD renvoyé par le téléphone (code ${outcome.code})."
                )
            }
        } catch (e: Exception) {
            // Jamais e.message brut : il pourrait contenir le code composé, donc le PIN.
            Log.e(TAG, "Session ${session.id} en échec : ${e.javaClass.simpleName}")
            finish(session, UssdState.Failed, error = e.javaClass.simpleName)
        }
    }

    private sealed class Outcome {
        class Response(val text: String) : Outcome()
        class Failure(val code: Int) : Outcome()
    }

    /** Le PIN n'existe que dans cette fonction, le temps de l'appel. */
    @SuppressLint("MissingPermission") // CALL_PHONE vérifiée dans checkReadiness
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun send(session: UssdSession): Outcome = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val dialed = session.code.replace(PIN_PLACEHOLDER, settings.pin ?: "")
            telephonyFor(session.simNumber).sendUssdRequest(
                dialed,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager, request: String, response: CharSequence
                    ) {
                        if (continuation.isActive) continuation.resume(Outcome.Response(response.toString()))
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager, request: String, failureCode: Int
                    ) {
                        if (continuation.isActive) continuation.resume(Outcome.Failure(failureCode))
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.O)
    private fun telephonyFor(simNumber: Int?): TelephonyManager {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        if (simNumber == null) return telephony
        val subscriptionId = SubscriptionsHelper.getSubscriptionId(context, simNumber - 1)
            ?: throw IllegalArgumentException("SIM $simNumber introuvable sur ce téléphone.")
        return telephony.createForSubscriptionId(subscriptionId)
    }

    @Synchronized
    private fun finish(
        session: UssdSession,
        state: UssdState,
        response: String? = null,
        error: String? = null,
        failureCode: Int? = null,
    ) {
        if (session.isFinished) return

        session.state = state
        session.response = response
        session.error = error
        session.failureCode = failureCode
        session.finishedAt = Date()

        val event = if (state == UssdState.Completed) WebHookEvent.UssdCompleted else WebHookEvent.UssdFailed
        val payload = session.toResponse()
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
        val pinCount = code.split(PIN_PLACEHOLDER).size - 1
        require(pinCount <= 1) { "code : $PIN_PLACEHOLDER au plus une fois." }
        require(CODE_REGEX.matches(code.replace(PIN_PLACEHOLDER, ""))) {
            "code invalide : chiffres, * et # uniquement (plus $PIN_PLACEHOLDER), terminé par #."
        }
        if (pinCount == 1) {
            require(settings.pin != null) { "Code PIN non réglé sur le téléphone (Paramètres > USSD)." }
        }

        val id = request.id?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        require(ID_REGEX.matches(id)) { "id invalide : 1 à 64 caractères parmi A-Z, a-z, 0-9, - et _." }

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "USSD en une commande indisponible : Android 8 ou plus requis."
        }
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            return "Permission CALL_PHONE refusée sur le téléphone."
        }
        if (session.simNumber != null && !hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            return "Permission READ_PHONE_STATE refusée : impossible de choisir la SIM."
        }
        return null
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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

        /** Android trop ancien (vérifié aussi dans checkReadiness). */
        private const val UNSUPPORTED = -100

        private const val DEFAULT_TIMEOUT_SECONDS = 60
        private const val MIN_TIMEOUT_SECONDS = 10
        private const val MAX_TIMEOUT_SECONDS = 180
        private const val MAX_SESSIONS_IN_MEMORY = 200

        private val CODE_REGEX = Regex("^[0-9*#]+#$")
        private val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
