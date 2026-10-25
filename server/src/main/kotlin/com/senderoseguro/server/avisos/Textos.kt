package com.senderoseguro.server.avisos

import com.senderoseguro.server.dispersion.EnlacesServicio
import java.text.Normalizer

/** Lo que se sabe de un aviso para redactarlo. Los campos de la alerta son null en avisos que no son de una alerta. */
internal data class Contexto(
    val tipo: String,
    val folio: String?,
    val tipoIncidente: String?,
    val emisorNombre: String?,
    /** Para CAMBIO_ESTADO, el estado al que cambió la alerta cuando se creó el aviso. */
    val estadoAlerta: String?,
    val nivelEscalamiento: Int?,
    /** El aviso es para quien envió la alerta. */
    val paraEmisor: Boolean,
    /** Rol de la cuenta que lo recibe; null si es un contacto sin cuenta. */
    val rolDestinatario: String?,
    /** Enlace de ubicación en vivo, solo para la red de apoyo con acceso de seguimiento. */
    val enlace: String?,
)

/** Textos de los avisos (push y SMS). */
internal object Textos {

    fun push(c: Contexto): Pair<String, String> {
        val folio = c.folio ?: ""
        val tipo = c.tipoIncidente ?: "emergencia"
        val nombre = c.emisorNombre ?: "Una persona"
        val deConsola = c.rolDestinatario == "OPERADOR" || c.rolDestinatario == "ADMINISTRADOR"
        return when (c.tipo) {
            "ALERTA_PRIORITARIA" ->
                if (deConsola) "Alerta $folio: $tipo" to "Asignada a tu unidad. Atiéndela en la consola."
                else "Alerta de $nombre sin atender" to "$nombre, de tu círculo, activó una alerta ($tipo) que nadie ha atendido. Intenta comunicarte y avisa a las autoridades."
            "ALERTA_INFORMATIVA" -> "Alerta $folio: $tipo" to "Nueva alerta en tu corporación. Puedes verla en la consola."
            "RED_APOYO" ->
                if (c.enlace != null) "$nombre activó una alerta" to "Tipo: $tipo. Sigue su ubicación aquí: ${c.enlace}"
                else "$nombre activó una alerta" to "Tipo: $tipo. Sendero Seguro ya la está canalizando."
            "ACUSE" -> "Tu alerta fue recibida" to "Una unidad de atención recibió tu alerta $folio."
            "CAMBIO_ESTADO" -> "Tu alerta $folio" to "Estado: ${estadoLegible(c.estadoAlerta)}."
            "CIERRE" ->
                if (c.paraEmisor) "Tu alerta $folio se cerró" to "Gracias por usar Sendero Seguro."
                else "La alerta de $nombre terminó" to "La alerta se cerró y el enlace de ubicación ya no está disponible."
            "CANCELACION" ->
                if (deConsola) "Alerta $folio cancelada" to "La persona canceló la alerta. Ya no es necesario atenderla."
                else "$nombre canceló su alerta" to "La alerta se canceló y el enlace de ubicación ya no está disponible."
            "ESCALAMIENTO" -> "Alerta $folio escalada" to "Pasó al nivel ${c.nivelEscalamiento ?: ""} de escalamiento. Revísala en la consola."
            "SIN_ACUSE" -> "Alerta $folio sin atender" to "Nadie ha acusado esta alerta y no quedan niveles de escalamiento. Revísala en la consola."
            else -> "Sendero Seguro" to "Tienes un aviso nuevo. Ábrelo en la app."
        }
    }

    private fun estadoLegible(estado: String?) = when (estado) {
        "CERRADA" -> "Cerrada"
        "CANCELADA" -> "Cancelada"
        else -> EnlacesServicio.estadoLegible(estado ?: "")
    }

    /**
     * El SMS lleva el mismo contenido sin acentos ni eñes: así cabe en el alfabeto GSM de 160 caracteres por
     * mensaje (con un acento el SMS pasa a 70 caracteres por mensaje y cuesta más).
     */
    fun sms(c: Contexto): String {
        val (titulo, texto) = push(c)
        return sinAcentos("Sendero Seguro: $titulo. $texto")
    }

    fun sinAcentos(texto: String): String =
        Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
