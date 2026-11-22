package me.capcom.smsgateway.modules.localserver.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import me.capcom.smsgateway.modules.localserver.auth.AuthScopes
import me.capcom.smsgateway.modules.localserver.auth.requireScope
import me.capcom.smsgateway.modules.ussd.UssdAlreadyDialedException
import me.capcom.smsgateway.modules.ussd.UssdRequest
import me.capcom.smsgateway.modules.ussd.UssdService

/**
 * POST /ussd       : met une session en file (202). Même id = même session, jamais recomposée.
 * GET  /ussd/{id}  : état de la session (404 si inconnue de cette exécution de l'application).
 * Fin de session notifiée par webhook ussd:completed / ussd:failed.
 */
class UssdRoutes(
    private val ussdService: UssdService,
) {
    fun register(routing: Route) {
        routing.apply {
            ussdRoutes()
        }
    }

    private fun Route.ussdRoutes() {
        post {
            if (!requireScope(AuthScopes.AllAny)) return@post
            val request = call.receive<UssdRequest>()
            try {
                val session = ussdService.enqueue(request)
                call.respond(HttpStatusCode.Accepted, session.toResponse())
            } catch (e: UssdAlreadyDialedException) {
                call.respond(HttpStatusCode.Conflict, mapOf("message" to e.message))
            }
        }

        get("{id}") {
            if (!requireScope(AuthScopes.AllAny)) return@get
            val id = call.parameters["id"]
                ?: throw IllegalArgumentException("id manquant.")
            val session = ussdService.get(id)
            if (session == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("message" to "Session $id inconnue."))
                return@get
            }
            call.respond(session.toResponse())
        }
    }
}
