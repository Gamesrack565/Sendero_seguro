package com.escom.sendero_seguro.cola

import java.util.Locale

/**
 * P11: Enumeración de tipos de elementos para la cola de alertas y eventos.
 */
enum class TipoElementoAlerta(val valor: String) {
    ALERTA_SOS("ALERTA_SOS"),
    ACUSE_RECIBO("ACUSE_RECIBO"),
    TELEMETRIA("TELEMETRIA"),
    PAQUETE_MALLA("PAQUETE_MALLA")
}

/**
 * Canales de comunicación por los que se intentó transmitir antes del fallback offline.
 * Cascada de envío:
 * 1. INTERNET
 * 2. MALLA_BLE (y WiFi-Direct)
 * 3. SMS
 * 4. FALLO_TOTAL -> Almacenamiento local en SQLite / Room (P11)
 */
enum class CanalEnvio(val valor: String) {
    INTERNET("INTERNET"),
    MALLA_BLE("MALLA_BLE"),
    SMS("SMS"),
    FALLO_TOTAL("FALLO_TOTAL")
}

/**
 * P11 / A-05: Representación unificada de un elemento pendiente en cola para su sincronización diferida.
 * Permite ordenar por prioridad (Alertas primero, luego Reportes) y verificar el TTL.
 */
sealed class ElementoColaPendiente {
    abstract val id: String
    abstract val reintentos: Int
    abstract val creadoEn: Long
    abstract val expiraEn: Long
    abstract val tamanoBytes: Long

    val estaVencido: Boolean
        get() = System.currentTimeMillis() >= expiraEn

    val tiempoRestanteMs: Long
        get() = maxOf(0L, expiraEn - System.currentTimeMillis())

    val tamanoMb: Double
        get() = tamanoBytes / (1024.0 * 1024.0)

    val tiempoRestanteFormateado: String
        get() {
            val ms = tiempoRestanteMs
            val horas = ms / (1000 * 3600)
            val minutos = (ms % (1000 * 3600)) / (1000 * 60)
            val segundos = (ms % (1000 * 60)) / 1000
            return String.format(Locale.getDefault(), "%02dh %02dm %02ds", horas, minutos, segundos)
        }

    /**
     * Elemento de Alerta / Telemetría / Malla (Prioridad 1 en A-05).
     */
    data class Alerta(
        override val id: String,
        val paqueteBytes: ByteArray?,
        val tipoElemento: String,
        val ultimoCanal: String,
        override val reintentos: Int,
        override val creadoEn: Long,
        override val expiraEn: Long,
        override val tamanoBytes: Long = (paqueteBytes?.size?.toLong() ?: 0L)
    ) : ElementoColaPendiente() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Alerta
            if (id != other.id) return false
            if (paqueteBytes != null) {
                if (other.paqueteBytes == null) return false
                if (!paqueteBytes.contentEquals(other.paqueteBytes)) return false
            } else if (other.paqueteBytes != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + (paqueteBytes?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Elemento de Reporte Comunitario con o sin fotos/audios adjuntos (Prioridad 2 en A-05).
     */
    data class Reporte(
        override val id: String,
        val datosJson: String,
        val rutasEvidencia: List<String>,
        override val tamanoBytes: Long,
        override val reintentos: Int,
        override val creadoEn: Long,
        override val expiraEn: Long
    ) : ElementoColaPendiente()
}

/**
 * P11: Métricas de estado de la cola para diagnóstico, UI y supervisión.
 */
data class EstadisticasCola(
    val totalAlertasPendientes: Int = 0,
    val totalReportesPendientes: Int = 0,
    val totalElementosPendientes: Int = 0,
    val tamanoTotalBytes: Long = 0L,
    val totalReintentosAcumulados: Int = 0,
    val proximoVencimientoMs: Long? = null
) {
    val tamanoTotalMb: Double
        get() = tamanoTotalBytes / (1024.0 * 1024.0)
}

