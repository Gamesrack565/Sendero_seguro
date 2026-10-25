package com.senderoseguro.server.plataforma

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.configurarSerializacion() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true   // la app puede mandar campos nuevos sin romper el servidor
            explicitNulls = false
        })
    }
}

fun Application.configurarLogs() {
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }
}
