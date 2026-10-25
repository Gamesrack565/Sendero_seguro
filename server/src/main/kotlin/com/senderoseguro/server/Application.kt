package com.senderoseguro.server

import com.senderoseguro.server.plataforma.BaseDatos
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarLogs
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.senderoseguro.server.plataforma.rutasSalud
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun main(args: Array<String>) = EngineMain.main(args)

/** Punto de entrada de Ktor (se declara en application.conf). */
fun Application.module() {
    val baseDatos = BaseDatos.iniciar(this)

    configurarSerializacion()
    configurarLogs()
    configurarErrores()
    configurarSeguridad()

    routing {
        route("/api/v1") {
            rutasSalud(revisarBaseDatos = baseDatos::estaViva)
            // Aquí se registran las rutas de cada módulo, por ejemplo: rutasAlertas(baseDatos)
        }
    }
}
