package com.senderoseguro.server

import com.senderoseguro.server.desarrollo.rutasDesarrollo
import com.senderoseguro.server.dispersion.AtencionServicio
import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.dispersion.DispersionServicio
import com.senderoseguro.server.dispersion.EnlacesServicio
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.iniciarTemporizador
import com.senderoseguro.server.dispersion.rutasAlertas
import com.senderoseguro.server.dispersion.rutasEnlace
import com.senderoseguro.server.plataforma.BaseDatos
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarLogs
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.senderoseguro.server.plataforma.rutasSalud
import com.senderoseguro.server.tiemporeal.CentroTiempoReal
import com.senderoseguro.server.tiemporeal.configurarWebSockets
import com.senderoseguro.server.tiemporeal.rutasTiempoReal
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) = EngineMain.main(args)

/** Punto de entrada de Ktor (se declara en application.conf). */
fun Application.module() {
    val baseDatos = BaseDatos.iniciar(this)
    val modoDesarrollo = environment.config.property("db.datosPrueba").getString().toBoolean()
    // Los módulos que registran alertas reciben `dispersion` y llaman a dispersion.despachar(alertaId).
    val config = environment.config
    val enlaces = EnlacesServicio(
        baseDatos.dataSource,
        secreto = config.property("enlace.secreto").getString(),
        horasMax = config.property("enlace.horasMax").getString().toInt(),
        urlWebSocket = config.property("enlace.urlWebSocket").getString(),
    )
    val dispersion = DispersionServicio(baseDatos.dataSource, scope = this, enlaces)
    val escalamiento = EscalamientoServicio(baseDatos.dataSource)
    val revision = environment.config.property("dispersion.revisionSeg").getString().toInt().seconds
    iniciarTemporizador(scope = this, cada = revision, dispersion, escalamiento)
    val consulta = ConsultaAlertas(baseDatos.dataSource)
    val atencion = AtencionServicio(baseDatos.dataSource, consulta, escalamiento, enlaces)
    val tiempoReal = CentroTiempoReal(baseDatos.dataSource, consulta, enlaces)
    tiempoReal.iniciar(scope = this, cada = config.property("tiempoReal.revisionMs").getString().toLong().milliseconds)
    val tokens = EmisorTokens.desde(this)

    configurarSerializacion()
    configurarLogs()
    configurarErrores()
    configurarSeguridad()
    configurarWebSockets()

    routing {
        route("/api/v1") {
            rutasSalud(revisarBaseDatos = baseDatos::estaViva)
            rutasAlertas(consulta, atencion)
            rutasEnlace(enlaces)
            rutasTiempoReal(tiempoReal, tokens)
            // Aquí se registran las rutas de cada módulo, por ejemplo: rutasRegistroAlertas(baseDatos, dispersion)
            if (modoDesarrollo) rutasDesarrollo(baseDatos.dataSource, dispersion, escalamiento, enlaces, tokens)
        }
    }
}
