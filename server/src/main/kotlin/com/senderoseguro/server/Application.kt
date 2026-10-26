package com.senderoseguro.server

import com.senderoseguro.server.auditoria.Estadisticas
import com.senderoseguro.server.auditoria.Exportacion
import com.senderoseguro.server.auditoria.Historial
import com.senderoseguro.server.auditoria.LineaDeTiempo
import com.senderoseguro.server.auditoria.RetencionTrayectos
import com.senderoseguro.server.auditoria.rutasBitacora
import com.senderoseguro.server.auditoria.rutasEstadisticas
import com.senderoseguro.server.auditoria.rutasHistorial
import com.senderoseguro.server.auditoria.rutasLineaDeTiempo
import com.senderoseguro.server.avisos.CredencialesFcm
import com.senderoseguro.server.avisos.EnvioAvisos
import com.senderoseguro.server.avisos.ProveedorFcm
import com.senderoseguro.server.avisos.rutasDispositivos
import com.senderoseguro.server.avisos.Proveedor
import com.senderoseguro.server.avisos.ProveedorSimulado
import com.senderoseguro.server.avisos.ProveedorTwilio
import com.senderoseguro.server.desarrollo.RegistroSmsPrueba
import com.senderoseguro.server.desarrollo.SyncAlertaPrueba
import com.senderoseguro.server.desarrollo.rutasDesarrollo
import com.senderoseguro.server.dispersion.AtencionServicio
import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.dispersion.DispersionServicio
import com.senderoseguro.server.dispersion.EnlacesServicio
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.iniciarTemporizador
import com.senderoseguro.server.dispersion.rutasAlertas
import com.senderoseguro.server.dispersion.rutasEnlace
import com.senderoseguro.server.pasarela.ConfigPasarela
import com.senderoseguro.server.pasarela.RecepcionSms
import com.senderoseguro.server.pasarela.RegistroAlertasSms
import com.senderoseguro.server.pasarela.rutasPasarela
import com.senderoseguro.server.plataforma.BaseDatos
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarLogs
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.senderoseguro.server.plataforma.problemasDeArranque
import com.senderoseguro.server.plataforma.rutasSalud
import com.senderoseguro.server.sincronizacion.ManejadorSync
import com.senderoseguro.server.sincronizacion.Sincronizacion
import com.senderoseguro.server.sincronizacion.TipoSync
import com.senderoseguro.server.sincronizacion.rutasSync
import com.senderoseguro.server.tiemporeal.CentroTiempoReal
import com.senderoseguro.server.tiemporeal.configurarWebSockets
import com.senderoseguro.server.tiemporeal.rutasTiempoReal
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main(args: Array<String>) = EngineMain.main(args)

/** Punto de entrada de Ktor (se declara en application.conf). */
fun Application.module() {
    val modoDesarrollo = environment.config.property("db.datosPrueba").getString().toBoolean()
    // Se revisa antes de tocar la base: un servidor en la nube con las llaves del repo no debe arrancar.
    val problemas = problemasDeArranque(environment.config, modoDesarrollo)
    check(problemas.isEmpty()) { "El servidor no arranca: " + problemas.joinToString("; ") }
    if (modoDesarrollo) {
        log.warn("Modo de prueba: rutas /dev y cuentas de prueba activas. En la nube usa DB_DATOS_PRUEBA=false.")
    }
    val baseDatos = BaseDatos.iniciar(this)
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
    val urlPublica = config.property("pasarela.urlPublica").getString().trimEnd('/')
    val sms = proveedorSms(config, urlPublica)
    val envioAvisos = EnvioAvisos(
        baseDatos.dataSource,
        push = proveedorPush(config),
        sms = sms,
        enlaces,
        EnvioAvisos.Politica(
            maxReintentos = config.property("avisos.maxReintentos").getString().toInt(),
            esperaBase = config.property("avisos.esperaBaseSeg").getString().toInt().seconds,
        ),
        urlEnlace = config.property("avisos.urlEnlace").getString(),
    )
    envioAvisos.iniciar(scope = this, cada = config.property("avisos.revisionMs").getString().toLong().milliseconds)
    // Registro de las alertas que llegan por SMS: lo conecta el Módulo 2 (P4). Mientras, en desarrollo se usa el de prueba
    // y en la nube los SMS válidos quedan pendientes en mensaje_sms hasta que exista.
    val registroSms: RegistroAlertasSms? = if (modoDesarrollo) RegistroSmsPrueba else null
    val recepcionSms = RecepcionSms(baseDatos.dataSource, dispersion, registroSms)
    // Manejadores de /sync/lote: cada módulo registra el de su tipo, por ejemplo manejadoresSync[TipoSync.REPORTE] = ...
    // Mientras, en desarrollo las ALERTA usan uno de prueba; un tipo sin manejador sale RECHAZADO temporal.
    val manejadoresSync = mutableMapOf<TipoSync, ManejadorSync>()
    if (modoDesarrollo) manejadoresSync[TipoSync.ALERTA] = SyncAlertaPrueba
    recepcionSms.iniciar(scope = this, cada = 30.seconds)
    RetencionTrayectos(baseDatos.dataSource, dias = config.property("retencion.trayectoDias").getString().toInt())
        .iniciar(scope = this, cada = config.property("retencion.revisionHoras").getString().toInt().hours)
    val configPasarela = ConfigPasarela(
        authToken = config.property("twilio.authToken").getString(),
        urlPublica = urlPublica,
        modoDesarrollo = modoDesarrollo,
    )

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
            rutasPasarela(recepcionSms, envioAvisos, configPasarela)
            rutasSync(Sincronizacion(baseDatos.dataSource, dispersion, manejadoresSync))
            rutasBitacora(baseDatos.dataSource)
            rutasLineaDeTiempo(LineaDeTiempo(baseDatos.dataSource, consulta))
            rutasHistorial(Historial(baseDatos.dataSource))
            val estadisticas = Estadisticas(baseDatos.dataSource)
            rutasEstadisticas(estadisticas, Exportacion(baseDatos.dataSource, estadisticas))
            // Aquí se registran las rutas de cada módulo, por ejemplo: rutasRegistroAlertas(baseDatos, dispersion)
            if (modoDesarrollo) rutasDesarrollo(baseDatos.dataSource, dispersion, escalamiento, enlaces, tokens, sms)
        }
    }
}

/** El proveedor de push configurado: `simulado` (local) o `fcm`. */
private fun proveedorPush(config: ApplicationConfig): Proveedor = when (val nombre = config.property("avisos.proveedorPush").getString().lowercase()) {
    "simulado" -> ProveedorSimulado()
    "fcm" -> {
        val valor = config.property("avisos.fcmCredenciales").getString().trim().ifBlank {
            error("AVISOS_PROVEEDOR_PUSH=fcm necesita FCM_CREDENCIALES con la ruta del .json de la cuenta de servicio o su contenido")
        }
        // En la nube la variable trae el contenido del .json; en tu computadora, la ruta del archivo.
        ProveedorFcm(if (valor.startsWith("{")) CredencialesFcm.desdeTexto(valor) else CredencialesFcm.desdeArchivo(valor))
    }
    else -> error("Proveedor de push desconocido: $nombre. Valores posibles: simulado, fcm")
}

/** El proveedor de SMS configurado: `simulado` (local) o `twilio`. */
private fun proveedorSms(config: ApplicationConfig, urlPublica: String): Proveedor = when (val nombre = config.property("avisos.proveedorSms").getString().lowercase()) {
    "simulado" -> ProveedorSimulado()
    "twilio" -> {
        fun valor(clave: String, variable: String) = config.property("twilio.$clave").getString().trim().ifBlank {
            error("AVISOS_PROVEEDOR_SMS=twilio necesita la variable $variable")
        }
        ProveedorTwilio(
            cuenta = valor("accountSid", "TWILIO_ACCOUNT_SID"),
            authToken = valor("authToken", "TWILIO_AUTH_TOKEN"),
            numero = valor("numero", "TWILIO_NUMERO"),
            // Twilio solo puede avisar la entrega a una dirección pública.
            urlEstado = urlPublica.takeIf { it.isNotBlank() }?.let { "$it/pasarela/sms/estado" },
        )
    }
    else -> error("Proveedor de SMS desconocido: $nombre. Valores posibles: simulado, twilio")
}
