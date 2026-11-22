package me.capcom.smsgateway.modules.ussd

import kotlinx.coroutines.CompletableDeferred
import java.util.Collections
import java.util.Date

enum class UssdState { Pending, Running, Completed, Failed }

/**
 * Corps de POST /ussd. Champs nullables : Gson ne passe pas par le constructeur Kotlin,
 * un champ absent arriverait null malgré un type non nullable.
 *
 * - id : clé d'idempotence fournie par le serveur. Un id déjà composé n'est JAMAIS recomposé.
 * - code : code à composer, ex. "*144#".
 * - answers : réponses à saisir, une par écran de menu, dans l'ordre. "{PIN}" est remplacé
 *   sur le téléphone par le code PIN réglé dans l'application (il ne transite jamais par l'API).
 * - simNumber : SIM à utiliser (1, 2…). Absent : SIM par défaut du téléphone.
 * - timeoutSeconds : durée maximale de la session (défaut 90 s).
 */
data class UssdRequest(
    val id: String?,
    val code: String?,
    val answers: List<String>?,
    val simNumber: Int?,
    val timeoutSeconds: Int?,
)

class UssdSession(
    val id: String,
    val code: String,
    val answers: List<String>,
    val simNumber: Int?,
    val timeoutSeconds: Int,
) {
    val createdAt: Date = Date()

    @Volatile
    var state: UssdState = UssdState.Pending

    /** Textes des écrans affichés par l'opérateur, dans l'ordre (jamais ce qui a été saisi). */
    val screens: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var answersSent: Int = 0

    @Volatile
    var finalMessage: String? = null

    @Volatile
    var error: String? = null

    @Volatile
    var startedAt: Date? = null

    @Volatile
    var finishedAt: Date? = null

    internal val done = CompletableDeferred<Unit>()

    val isFinished: Boolean
        get() = state == UssdState.Completed || state == UssdState.Failed

    fun toResponse() = UssdSessionResponse(
        id = id,
        state = state,
        code = code,
        simNumber = simNumber,
        answersSent = answersSent,
        answersTotal = answers.size,
        screens = screens.toList(),
        finalMessage = finalMessage,
        error = error,
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )
}

/** Réponse de l'API et contenu des webhooks ussd:completed / ussd:failed. */
data class UssdSessionResponse(
    val id: String,
    val state: UssdState,
    val code: String,
    val simNumber: Int?,
    val answersSent: Int,
    val answersTotal: Int,
    val screens: List<String>,
    val finalMessage: String?,
    val error: String?,
    val createdAt: Date,
    val startedAt: Date?,
    val finishedAt: Date?,
)

/** Action que le service d'accessibilité doit faire sur l'écran USSD affiché. */
sealed class UssdAction {
    object Ignore : UssdAction()
    class Reply(val text: String) : UssdAction()
    object Dismiss : UssdAction()
}
