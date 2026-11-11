package com.escom.sendero_seguro.identidad.procesos

import com.escom.sendero_seguro.identidad.dominio.CancelacionMotivo
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.EventoCancelacion
import com.escom.sendero_seguro.identidad.dominio.HashPin
import com.escom.sendero_seguro.identidad.dominio.MetodoAutenticacion
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.ResultadoCancelacion
import com.escom.sendero_seguro.identidad.dominio.SolicitudCancelacion
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import com.escom.sendero_seguro.identidad.dominio.exito
import com.escom.sendero_seguro.identidad.dominio.falla
import com.escom.sendero_seguro.identidad.puertos.AlmacenSeguro
import com.escom.sendero_seguro.identidad.puertos.ClavesConfig
import com.escom.sendero_seguro.identidad.puertos.ColaCancelaciones
import com.escom.sendero_seguro.identidad.puertos.RepositorioConfigLocal
import com.escom.sendero_seguro.identidad.puertos.ServicioRemotoIdentidad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * P8. Cancelación de Alerta con Validación de PIN y Registro de Motivo (A-08, y A-05 durante la cuenta regresiva).
 *
 * - El PIN se verifica en el teléfono (funciona sin internet).
 * - Si no coincide la alerta SIGUE ACTIVA, el intento se registra (cancelacion.exitoso = false) para que el
 *   backend lo trate como posible coacción, y la pantalla solo muestra un mensaje neutro (flujo 3a de CU-21).
 * - Con el PIN de coacción (si la persona lo creó) la pantalla recibe Cancelada, igual que una cancelación real,
 *   pero al backend va como intento no exitoso: la alerta sigue y escala en silencio (P8, RF-13).
 * - Si coincide se avisa al backend, que pasa la alerta a CANCELADA, avisa a la unidad y a la red de apoyo
 *   y revoca enlace_ubicacion. Sin señal la cancelación queda en cola_alerta (tipo CANCELACION).
 *
 * Composición: capturarMotivoCancelacion() + verificarPinCancelacion() + cerrarAlertaYNotificarCierre()
 */
class ProcesoCancelacion(
    private val remoto: ServicioRemotoIdentidad,
    private val config: RepositorioConfigLocal,
    private val almacen: AlmacenSeguro,
    private val cola: ColaCancelaciones,
    private val reloj: () -> Long = System::currentTimeMillis,
    private val cpu: CoroutineDispatcher = Dispatchers.Default
) {
    private val _eventos = MutableSharedFlow<EventoCancelacion>(extraBufferCapacity = 16)

    /** El Módulo 2 escucha aquí para detener cuenta regresiva, GNSS cada 10 s y retransmisión. */
    val eventos: SharedFlow<EventoCancelacion> = _eventos.asSharedFlow()

    fun motivosDisponibles(): List<CancelacionMotivo> = CancelacionMotivo.entries.toList()

    fun capturarMotivoCancelacion(motivo: CancelacionMotivo?): Resultado<CancelacionMotivo> =
        if (motivo != null) exito(motivo)
        else falla(ErrorIdentidad.Validacion(mapOf(Validaciones.MOTIVO to "Elige el motivo de la cancelación")))

    /**
     * Verifica el PIN contra el hash guardado en el Keystore. Devuelve true si coincide.
     * Un PIN incorrecto se registra como intento fallido y la alerta no cambia.
     */
    suspend fun verificarPinCancelacion(alertaId: String, pin: String, motivo: CancelacionMotivo?): Resultado<Boolean> {
        val guardado = almacen.leerHashPin() ?: return falla(ErrorIdentidad.PinNoConfigurado)
        val coincide = pin.length == 4 && withContext(cpu) { HashPin.verificar(pin, guardado) }
        if (!coincide) registrarIntentoFallido(alertaId, motivo, MetodoAutenticacion.PIN)
        return exito(coincide)
    }

    /**
     * Cambia la alerta a CANCELADA. [alertaYaEnviada] = false cuando se cancela durante la cuenta
     * regresiva (A-05): la alerta nunca salió del teléfono y no hay nada que avisar al servidor.
     */
    suspend fun cerrarAlertaYNotificarCierre(
        alertaId: String,
        motivo: CancelacionMotivo,
        metodo: MetodoAutenticacion,
        alertaYaEnviada: Boolean = true
    ): ResultadoCancelacion.Cancelada {
        var sincronizada = !alertaYaEnviada
        if (alertaYaEnviada) {
            val solicitud = solicitud(alertaId, motivo, metodo, exitoso = true)
            sincronizada = when (remoto.registrarCancelacion(solicitud)) {
                is Resultado.Exito -> true
                is Resultado.Error -> {
                    cola.encolar(solicitud) // el despachador multicanal la enviará (internet → malla → SMS)
                    false
                }
            }
        }
        _eventos.emit(EventoCancelacion.AlertaCancelada(alertaId, motivo))
        return ResultadoCancelacion.Cancelada(alertaId, sincronizada)
    }

    /** Composición de P8 con PIN (A-08). */
    suspend fun cancelarConPin(
        alertaId: String,
        motivo: CancelacionMotivo?,
        pin: String,
        alertaYaEnviada: Boolean = true
    ): Resultado<ResultadoCancelacion> {
        val m = when (val r = capturarMotivoCancelacion(motivo)) {
            is Resultado.Error -> return r
            is Resultado.Exito -> r.valor
        }
        // PIN de coacción: a la vista se cancela; en el servidor la alerta sigue y se marca la coacción.
        if (esPinCoaccion(pin)) return exito(simularCancelacionPorCoaccion(alertaId, m))
        return when (val v = verificarPinCancelacion(alertaId, pin, m)) {
            is Resultado.Error -> v
            is Resultado.Exito ->
                if (v.valor) exito(cerrarAlertaYNotificarCierre(alertaId, m, MetodoAutenticacion.PIN, alertaYaEnviada))
                else exito(ResultadoCancelacion.NoCancelada())
        }
    }

    /**
     * Composición de P8 con huella (A-05 y A-08). La UI ejecuta BiometricPrompt
     * (plataforma/AutenticadorBiometrico) y pasa aquí el resultado.
     */
    suspend fun cancelarConBiometria(
        alertaId: String,
        motivo: CancelacionMotivo?,
        autenticada: Boolean,
        alertaYaEnviada: Boolean = true
    ): Resultado<ResultadoCancelacion> {
        val m = when (val r = capturarMotivoCancelacion(motivo)) {
            is Resultado.Error -> return r
            is Resultado.Exito -> r.valor
        }
        if (!autenticada) {
            registrarIntentoFallido(alertaId, m, MetodoAutenticacion.BIOMETRIA)
            return exito(ResultadoCancelacion.NoCancelada())
        }
        return exito(cerrarAlertaYNotificarCierre(alertaId, m, MetodoAutenticacion.BIOMETRIA, alertaYaEnviada))
    }

    /** Sube los intentos fallidos registrados sin señal. Llamarlo al recuperar internet. */
    suspend fun sincronizarIntentosFallidosPendientes(): Int {
        val pendientes = leerFallidos()
        if (pendientes.isEmpty()) return 0
        val noEnviados = pendientes.filter { remoto.registrarCancelacion(it) is Resultado.Error }
        guardarFallidos(noEnviados)
        return pendientes.size - noEnviados.size
    }

    /**
     * Manda las cancelaciones que se quedaron en cola_alerta sin señal. Llamarlo al recuperar internet.
     * El servidor no repite un intento ya guardado. Si responde que la alerta no existe, se conserva hasta que
     * venza (72 h), porque la alerta puede venir en camino por la malla o por SMS.
     */
    suspend fun sincronizarCancelacionesPendientes(): Int {
        var enviadas = 0
        for (s in cola.pendientes()) {
            when (val r = remoto.registrarCancelacion(s)) {
                is Resultado.Exito -> { cola.quitar(s.alertaId); enviadas++ }
                is Resultado.Error ->
                    if (r.error is ErrorIdentidad.Validacion || r.error is ErrorIdentidad.SinPermiso) cola.quitar(s.alertaId)
            }
        }
        return enviadas
    }

    // ------------------------------------------------------------------ internos

    private suspend fun esPinCoaccion(pin: String): Boolean {
        val guardado = almacen.leerHashPinCoaccion() ?: return false
        return pin.length == 4 && withContext(cpu) { HashPin.verificar(pin, guardado) }
    }

    /**
     * Responde igual que una cancelación real, para que quien vea la pantalla no note nada. Al servidor va como
     * intento no exitoso (posible coacción): la alerta sigue, la consola ve la marca y no se avisa la cancelación.
     */
    private suspend fun simularCancelacionPorCoaccion(alertaId: String, motivo: CancelacionMotivo): ResultadoCancelacion.Cancelada {
        val s = solicitud(alertaId, motivo, MetodoAutenticacion.PIN, exitoso = false)
        val enviada = remoto.registrarCancelacion(s) is Resultado.Exito
        if (!enviada) guardarFallidos(leerFallidos() + s)
        _eventos.emit(EventoCancelacion.CoaccionSilenciosa(alertaId))
        return ResultadoCancelacion.Cancelada(alertaId, sincronizada = enviada)
    }

    private suspend fun registrarIntentoFallido(alertaId: String, motivo: CancelacionMotivo?, metodo: MetodoAutenticacion) {
        val s = solicitud(alertaId, motivo, metodo, exitoso = false)
        if (remoto.registrarCancelacion(s) is Resultado.Error) guardarFallidos(leerFallidos() + s)
        _eventos.emit(EventoCancelacion.IntentoFallido(alertaId))
    }

    private suspend fun solicitud(
        alertaId: String,
        motivo: CancelacionMotivo?,
        metodo: MetodoAutenticacion,
        exitoso: Boolean
    ) = SolicitudCancelacion(
        alertaId = alertaId,
        usuarioId = config.leer(ClavesConfig.USUARIO_ID) ?: "",
        dispositivoId = config.leer(ClavesConfig.DISPOSITIVO_ID),
        motivo = motivo,
        metodo = metodo,
        exitoso = exitoso,
        intentadoEnMs = reloj()
    )

    // Los intentos fallidos sin señal se guardan en config_local y no en cola_alerta:
    // cola_alerta tiene alerta_id como llave y sobrescribiría la propia alerta pendiente.
    private suspend fun leerFallidos(): List<SolicitudCancelacion> =
        config.leer(ClavesConfig.CANCELACIONES_FALLIDAS_PENDIENTES)
            ?.split(';')?.mapNotNull { decodificar(it) } ?: emptyList()

    private suspend fun guardarFallidos(lista: List<SolicitudCancelacion>) {
        if (lista.isEmpty()) config.borrar(ClavesConfig.CANCELACIONES_FALLIDAS_PENDIENTES)
        else config.escribir(ClavesConfig.CANCELACIONES_FALLIDAS_PENDIENTES, lista.joinToString(";") { codificar(it) })
    }

    private fun codificar(s: SolicitudCancelacion) =
        listOf(s.alertaId, s.usuarioId, s.dispositivoId ?: "", s.motivo?.name ?: "", s.metodo.name, s.intentadoEnMs)
            .joinToString("|")

    private fun decodificar(t: String): SolicitudCancelacion? {
        val p = t.split('|')
        if (p.size != 6) return null
        return SolicitudCancelacion(
            alertaId = p[0],
            usuarioId = p[1],
            dispositivoId = p[2].ifEmpty { null },
            motivo = p[3].takeIf { it.isNotEmpty() }?.let { runCatching { CancelacionMotivo.valueOf(it) }.getOrNull() },
            metodo = runCatching { MetodoAutenticacion.valueOf(p[4]) }.getOrDefault(MetodoAutenticacion.PIN),
            exitoso = false,
            intentadoEnMs = p[5].toLongOrNull() ?: return null
        )
    }
}
