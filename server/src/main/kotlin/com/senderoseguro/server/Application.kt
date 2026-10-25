package com.senderoseguro.server

import com.senderoseguro.server.desarrollo.rutasDesarrollo
import com.senderoseguro.server.dispersion.DispersionServicio
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.iniciarTemporizador
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
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) = EngineMain.main(args)

/** Punto de entrada de Ktor (se declara en application.conf). */
fun Application.module() {
    val baseDatos = BaseDatos.iniciar(this)
    val modoDesarrollo = environment.config.property("db.datosPrueba").getString().toBoolean()
    // Los módulos que registran alertas reciben `dispersion` y llaman a dispersion.despachar(alertaId).
    val dispersion = DispersionServicio(baseDatos.dataSource, scope = this)
    val escalamiento = EscalamientoServicio(baseDatos.dataSource)
    val revision = environment.config.property("dispersion.revisionSeg").getString().toInt().seconds
    iniciarTemporizador(scope = this, cada = revision, dispersion, escalamiento)

    configurarSerializacion()
    configurarLogs()
    configurarErrores()
    configurarSeguridad()

    routing {
        route("/api/v1") {
            rutasSalud(revisarBaseDatos = baseDatos::estaViva)
            // Aquí se registran las rutas de cada módulo, por ejemplo: rutasAlertas(baseDatos, dispersion)
            if (modoDesarrollo) rutasDesarrollo(baseDatos.dataSource, dispersion, escalamiento)
        }
    }
}
