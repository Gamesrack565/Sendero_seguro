package com.senderoseguro.server

import com.senderoseguro.server.avisos.CredencialesFcm
import com.senderoseguro.server.avisos.EnvioAvisos
import com.senderoseguro.server.avisos.ProveedorFcm
import com.senderoseguro.server.avisos.rutasDispositivos
import com.senderoseguro.server.avisos.Proveedor
import com.senderoseguro.server.avisos.ProveedorSimulado
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
import io.ktor.server.config.ApplicationConfig
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
    EnvioAvisos(
        baseDatos.dataSource,
        push = proveedorPush(config),
        sms = proveedorSms(config),
        enlaces,
        EnvioAvisos.Politica(
            maxReintentos = config.property("avisos.maxReintentos").getString().toInt(),
            esperaBase = config.property("avisos.esperaBaseSeg").getString().toInt().seconds,
        ),
        urlEnlace = config.property("avisos.urlEnlace").getString(),
    ).iniciar(scope = this, cada = config.property("avisos.revisionMs").getString().toLong().milliseconds)

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
            rutasDispositivos(baseDatos.dataSource)
            // Aquí se registran las rutas de cada módulo, por ejemplo: rutasRegistroAlertas(baseDatos, dispersion)
            if (modoDesarrollo) rutasDesarrollo(baseDatos.dataSource, dispersion, escalamiento, enlaces, tokens)
        }
    }
}

/** El proveedor de push configurado: `simulado` (local) o `fcm`. */
private fun proveedorPush(config: ApplicationConfig): Proveedor = when (val nombre = config.property("avisos.proveedorPush").getString().lowercase()) {
    "simulado" -> ProveedorSimulado()
    "fcm" -> {
        val ruta = config.property("avisos.fcmCredenciales").getString().ifBlank {
            error("AVISOS_PROVEEDOR_PUSH=fcm necesita FCM_CREDENCIALES con la ruta del .json de la cuenta de servicio")
        }
        ProveedorFcm(CredencialesFcm.desdeArchivo(ruta))
    }
    else -> error("Proveedor de push desconocido: $nombre. Valores posibles: simulado, fcm")
}

/** El proveedor de SMS configurado. Twilio se agrega en su paso. */
private fun proveedorSms(config: ApplicationConfig): Proveedor = when (val nombre = config.property("avisos.proveedorSms").getString().lowercase()) {
    "simulado" -> ProveedorSimulado()
    else -> error("Proveedor de SMS desconocido: $nombre. Valores posibles: simulado")
}
