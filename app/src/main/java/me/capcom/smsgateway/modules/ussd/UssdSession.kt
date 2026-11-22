package me.capcom.smsgateway.modules.ussd

import java.util.Date

enum class UssdState { Pending, Running, Completed, Failed }

/**
 * Corps de POST /ussd. Champs nullables : Gson ne passe pas par le constructeur Kotlin,
 * un champ absent arriverait null malgré un type non nullable.
 *
 * - id : clé d'idempotence fournie par le serveur. Un id déjà composé n'est JAMAIS recomposé.
 * - code : code complet à composer en une fois, ex. "*144*1*1*627023108*1000*1*1*{PIN}#".
 *   "{PIN}" est remplacé sur le téléphone par le code PIN réglé dans l'application : il ne
 *   transite jamais par l'API et n'est jamais stocké dans la session.
 * - simNumber : SIM à utiliser (1, 2…). Absent : SIM par défaut du téléphone.
 * - timeoutSeconds : attente maximale de la réponse de l'opérateur (défaut 60 s).
 */
data class UssdRequest(
    val id: String?,
    val code: String?,
    val simNumber: Int?,
    val timeoutSeconds: Int?,
)

class UssdSession(
    val id: String,
    /** Code tel que reçu : contient "{PIN}", jamais le PIN lui-même. */
    val code: String,
    val simNumber: Int?,
    val timeoutSeconds: Int,
) {
    val createdAt: Date = Date()

    @Volatile
    var state: UssdState = UssdState.Pending

    /** Texte renvoyé par l'opérateur. */
    @Volatile
    var response: String? = null

    @Volatile
    var error: String? = null

    /** Code d'échec Android (TelephonyManager.USSD_RETURN_FAILURE = -1, USSD_ERROR_SERVICE_UNAVAIL = -2). */
    @Volatile
    var failureCode: Int? = null

    @Volatile
    var startedAt: Date? = null

    @Volatile
    var finishedAt: Date? = null

    val isFinished: Boolean
        get() = state == UssdState.Completed || state == UssdState.Failed

    fun toResponse() = UssdSessionResponse(
        id = id,
        state = state,
        code = code,
        simNumber = simNumber,
        response = response,
        error = error,
        failureCode = failureCode,
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
    val response: String?,
    val error: String?,
    val failureCode: Int?,
    val createdAt: Date,
    val startedAt: Date?,
    val finishedAt: Date?,
)
