package com.senderoseguro.server.avisos

import org.slf4j.LoggerFactory

/** Un aviso listo para entregarse por un canal: el token FCM (PUSH) o el teléfono (SMS) y el texto. */
data class Mensaje(
    val notificacionId: Long,
    val canal: String,
    val destino: String,
    val titulo: String,
    val texto: String,
    /** Datos para que la app abra la pantalla correcta al tocar el push (tipo, alertaId, folio). */
    val datos: Map<String, String> = emptyMap(),
)

/** Lo que contestó el proveedor al recibir el mensaje. */
sealed interface ResultadoEnvio {
    /** Lo aceptó. [entregado] es true si el proveedor ya confirma la entrega en la misma respuesta. */
    data class Aceptado(val proveedorMensajeId: String?, val entregado: Boolean = false) : ResultadoEnvio

    /**
     * No lo aceptó. Con [definitivo] no tiene caso reintentar. Con [destinoInvalido] el token o el número ya no
     * sirve: un token de push se borra y el aviso sale por SMS; un número de SMS inválido se descarta.
     */
    data class Fallo(val error: String, val definitivo: Boolean = false, val destinoInvalido: Boolean = false) : ResultadoEnvio
}

/** Un servicio que entrega mensajes de un canal: FCM para PUSH y la pasarela (Twilio) para SMS. */
interface Proveedor {
    val nombre: String

    /**
     * Si avisa después que el mensaje llegó (por ejemplo, el webhook de estado del SMS). Solo entonces un envío
     * sin confirmación vence y se reintenta (E-03: "error o tiempo agotado").
     */
    val confirmaEntrega: Boolean

    fun enviar(m: Mensaje): ResultadoEnvio
}

/**
 * Proveedor de desarrollo: no manda nada, solo escribe el mensaje en el log y lo da por entregado.
 * Es el que se usa en local y en las pruebas, para no gastar crédito ni depender de cuentas externas.
 */
class ProveedorSimulado(override val nombre: String = "simulado") : Proveedor {
    override val confirmaEntrega = false
    private val log = LoggerFactory.getLogger(ProveedorSimulado::class.java)

    override fun enviar(m: Mensaje): ResultadoEnvio {
        log.info("[SIMULADO] {} a {}: {} | {}", m.canal, m.destino, m.titulo, m.texto)
        return ResultadoEnvio.Aceptado("sim-${m.notificacionId}", entregado = true)
    }
}
