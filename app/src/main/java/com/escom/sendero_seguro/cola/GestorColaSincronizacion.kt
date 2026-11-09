package com.escom.sendero_seguro.cola

import android.content.Context
import android.util.Log
import com.escom.sendero_seguro.emergencias.AlertaEmergencia
import com.escom.sendero_seguro.emergencias.DatosTelemetria
import com.escom.sendero_seguro.emergencias.PaqueteMalla
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * P11. Gestión de Cola de Sincronización Offline y Expiración de Paquetes.
 * 
 * Responsabilidades:
 * 1. encolarPendienteConVigencia(): Guarda en SQLite/Room local los envíos pendientes asignándoles
 *    su tiempo de expiración (2h para acuses/alertas, 6 días para reportes comunitarios).
 * 2. Priorización de envíos (A-05): Alertas primero, luego Reportes comunitarios.
 * 3. Descarte por TTL: Purga de elementos cuya vigencia ha expirado antes de intentar enviarlos.
 * 4. Gestión de reintentos y cálculo de espera creciente (backoff exponencial).
 * 5. Idempotencia y eliminación: Retiro seguro de la cola una vez confirmado por el backend.
 */
class GestorColaSincronizacion(
    private val context: Context,
    private val db: BaseDatosLocal = BaseDatosLocal.obtenerInstancia(context)
) {

    companion object {
        private const val TAG = "P11_COLA_OFFLINE"

        // Vigencias por defecto según especificación P11
        const val VIGENCIA_ALERTAS_HORAS = 2L
        const val VIGENCIA_REPORTES_DIAS = 6L

        const val MS_POR_HORA = 3600 * 1000L
        const val MS_POR_DIA = 24 * 3600 * 1000L
    }

    private val colaAlertaDao = db.colaAlertaDao()
    private val colaReporteDao = db.colaReporteDao()
    private val paqueteVistoDao = db.paqueteVistoDao()

    /**
     * P11: encolarPendienteConVigencia() para Alertas de Emergencia.
     * Invocado cuando falló la cascada completa:
     * 1. Internet (HTTP/Azure) -> Falló
     * 2. Malla (BLE + WiFi Direct) -> Falló
     * 3. SMS -> Falló
     * 4. Encolado local en SQLite con TTL de 2 horas.
     */
    suspend fun encolarAlertaFalloCascada(
        alerta: AlertaEmergencia,
        ultimoCanal: CanalEnvio = CanalEnvio.FALLO_TOTAL,
        vigenciaHoras: Long = VIGENCIA_ALERTAS_HORAS
    ): ColaAlertaEntity = withContext(Dispatchers.IO) {
        val ahora = System.currentTimeMillis()
        val expiraEn = ahora + (vigenciaHoras * MS_POR_HORA)

        // Serializamos los datos de la alerta a un paquete binario / JSON representativo
        val jsonPayload = JSONObject().apply {
            put("idAlerta", alerta.idAlerta)
            put("detonador", alerta.detonador.name)
            put("latitudInicial", alerta.latitudInicial)
            put("longitudInicial", alerta.longitudInicial)
            put("precisionMetros", alerta.precisionMetros)
            put("nivelBateria", alerta.nivelBateria)
            put("timestampInicio", alerta.timestampInicio)
            put("enModoCoaccionSilencioso", alerta.enModoCoaccionSilencioso)
        }.toString()

        val entidad = ColaAlertaEntity(
            alertaId = alerta.idAlerta,
            paquete = jsonPayload.toByteArray(Charsets.UTF_8),
            tipoElemento = TipoElementoAlerta.ALERTA_SOS.valor,
            ultimoCanal = ultimoCanal.valor,
            reintentos = 0,
            creadoEn = ahora,
            expiraEn = expiraEn
        )

        colaAlertaDao.insertarOActualizar(entidad)
        Log.w(
            TAG,
            "⚠️ Alerta encolada offline tras fallo en cascada [ID: ${alerta.idAlerta}]. Expira en $vigenciaHoras horas."
        )
        entidad
    }

    /**
     * P11: encolarPendienteConVigencia() para Acuses de Alerta o Paquetes de Terceros en Malla.
     * Vigencia: 2 horas.
     */
    suspend fun encolarPaqueteMalla(
        paquete: PaqueteMalla,
        tipo: TipoElementoAlerta = TipoElementoAlerta.PAQUETE_MALLA,
        vigenciaHoras: Long = VIGENCIA_ALERTAS_HORAS
    ): ColaAlertaEntity = withContext(Dispatchers.IO) {
        val ahora = System.currentTimeMillis()
        val expiraEn = ahora + (vigenciaHoras * MS_POR_HORA)

        val jsonPayload = JSONObject().apply {
            put("packetId", paquete.packetId)
            put("emisorId", paquete.emisorId)
            put("latitud", paquete.latitud)
            put("longitud", paquete.longitud)
            put("nivelBateria", paquete.nivelBateria)
            put("ttl", paquete.ttl)
            put("timestamp", paquete.timestamp)
        }.toString()

        val entidad = ColaAlertaEntity(
            alertaId = paquete.packetId,
            paquete = jsonPayload.toByteArray(Charsets.UTF_8),
            tipoElemento = tipo.valor,
            ultimoCanal = CanalEnvio.MALLA_BLE.valor,
            reintentos = 0,
            creadoEn = ahora,
            expiraEn = expiraEn
        )

        colaAlertaDao.insertarOActualizar(entidad)
        entidad
    }

    /**
     * P11: encolarPendienteConVigencia() para Lecturas de Telemetría sin conexión.
     */
    suspend fun encolarTelemetria(
        telemetria: DatosTelemetria,
        alertaIdPadre: String,
        vigenciaHoras: Long = VIGENCIA_ALERTAS_HORAS
    ): ColaAlertaEntity = withContext(Dispatchers.IO) {
        val ahora = System.currentTimeMillis()
        val expiraEn = ahora + (vigenciaHoras * MS_POR_HORA)

        val jsonPayload = JSONObject().apply {
            put("alertaIdPadre", alertaIdPadre)
            put("latitud", telemetria.latitud)
            put("longitud", telemetria.longitud)
            put("precision", telemetria.precision)
            put("velocidadMps", telemetria.velocidadMps)
            put("rumboGrados", telemetria.rumboGrados)
            put("aceleracionG", telemetria.aceleracionG)
            put("nivelBateria", telemetria.nivelBateria)
            put("timestamp", telemetria.timestamp)
        }.toString()

        val idTelemetria = UUID.randomUUID().toString()
        val entidad = ColaAlertaEntity(
            alertaId = idTelemetria,
            paquete = jsonPayload.toByteArray(Charsets.UTF_8),
            tipoElemento = TipoElementoAlerta.TELEMETRIA.valor,
            ultimoCanal = CanalEnvio.FALLO_TOTAL.valor,
            reintentos = 0,
            creadoEn = ahora,
            expiraEn = expiraEn
        )

        colaAlertaDao.insertarOActualizar(entidad)
        entidad
    }

    /**
     * P11: encolarPendienteConVigencia() para Reportes Comunitarios de Incidentes (Módulo 10).
     * Vigencia: 6 días (518,400,000 ms).
     */
    suspend fun encolarReporteComunitario(
        reporteId: String = UUID.randomUUID().toString(),
        datosJson: String,
        rutasEvidencia: List<String> = emptyList(),
        tamanoBytes: Long = 0L,
        vigenciaDias: Long = VIGENCIA_REPORTES_DIAS
    ): ColaReporteEntity = withContext(Dispatchers.IO) {
        val ahora = System.currentTimeMillis()
        val expiraEn = ahora + (vigenciaDias * MS_POR_DIA)

        val rutasConcatenadas = rutasEvidencia.joinToString(separator = ";")

        val entidad = ColaReporteEntity(
            reporteId = reporteId,
            datos = datosJson,
            rutasEvidencia = rutasConcatenadas.ifBlank { null },
            tamanoBytes = tamanoBytes,
            reintentos = 0,
            creadoEn = ahora,
            expiraEn = expiraEn
        )

        colaReporteDao.insertarOActualizar(entidad)
        Log.i(
            TAG,
            "📋 Reporte comunitario encolado offline [ID: $reporteId, peso: ${tamanoBytes / 1024} KB]. Expira en $vigenciaDias días."
        )
        entidad
    }

    /**
     * A-05: Tomar el siguiente elemento de la cola respetando estricto orden de prioridad:
     * 1. Alertas primero (cola_alerta)
     * 2. Reportes comunitarios después (cola_reporte)
     * 
     * Implementa la decisión del diagrama A-05:
     * ¿TTL vencido? -> Sí: Descartar y registrar -> toma el siguiente.
     *                -> No: Retorna el elemento para su envío.
     */
    suspend fun obtenerSiguienteElementoPrioritario(): ElementoColaPendiente? = withContext(Dispatchers.IO) {
        val ahora = System.currentTimeMillis()

        // 1. Revisar cola de alertas primero
        while (true) {
            val alerta = colaAlertaDao.obtenerSiguienteElemento() ?: break
            if (alerta.expiraEn <= ahora) {
                Log.w(
                    TAG,
                    "⏰ TTL vencido en alerta [ID: ${alerta.alertaId}]. Descartando y registrando según diagrama A-05."
                )
                colaAlertaDao.eliminarPorId(alerta.alertaId)
                // Continúa el ciclo para evaluar el siguiente elemento
            } else {
                return@withContext ElementoColaPendiente.Alerta(
                    id = alerta.alertaId,
                    paqueteBytes = alerta.paquete,
                    tipoElemento = alerta.tipoElemento,
                    ultimoCanal = alerta.ultimoCanal,
                    reintentos = alerta.reintentos,
                    creadoEn = alerta.creadoEn,
                    expiraEn = alerta.expiraEn
                )
            }
        }

        // 2. Si no hay alertas válidas, revisar cola de reportes
        while (true) {
            val reporte = colaReporteDao.obtenerSiguienteElemento() ?: break
            if (reporte.expiraEn <= ahora) {
                Log.w(
                    TAG,
                    "⏰ TTL vencido en reporte comunitario [ID: ${reporte.reporteId}]. Descartando y registrando según A-05."
                )
                colaReporteDao.eliminarPorId(reporte.reporteId)
                // Continúa el ciclo
            } else {
                val evidencias = reporte.rutasEvidencia?.split(";")?.filter { it.isNotBlank() } ?: emptyList()
                return@withContext ElementoColaPendiente.Reporte(
                    id = reporte.reporteId,
                    datosJson = reporte.datos,
                    rutasEvidencia = evidencias,
                    tamanoBytes = reporte.tamanoBytes,
                    reintentos = reporte.reintentos,
                    creadoEn = reporte.creadoEn,
                    expiraEn = reporte.expiraEn
                )
            }
        }

        // Cola vacía
        null
    }

    /**
     * A-05: Descartar y registrar masivamente todos los elementos vencidos en ambas colas.
     */
    suspend fun descartarYRegistrarVencidos(): Int = withContext(Dispatchers.IO) {
        val ahora = System.currentTimeMillis()
        val alertasPurgadas = colaAlertaDao.purgarExpiradas(ahora)
        val reportesPurgados = colaReporteDao.purgarExpirados(ahora)
        val total = alertasPurgadas + reportesPurgados

        if (total > 0) {
            Log.i(
                TAG,
                "🧹 Depuración TTL completada: $alertasPurgadas alertas y $reportesPurgados reportes vencidos purgados."
            )
        }
        total
    }

    /**
     * A-05: Backend confirmó recepción (o ya existía por idempotencia con su UUID).
     * Marcar sincronizado y eliminar de la cola local.
     */
    suspend fun marcarSincronizadoYEliminar(id: String, esAlerta: Boolean): Boolean = withContext(Dispatchers.IO) {
        val filasAfectadas = if (esAlerta) {
            colaAlertaDao.eliminarPorId(id)
        } else {
            colaReporteDao.eliminarPorId(id)
        }
        val exito = filasAfectadas > 0
        if (exito) {
            Log.i(TAG, "✅ Elemento sincronizado y removido de la cola local [ID: $id, esAlerta: $esAlerta].")
        }
        exito
    }

    /**
     * A-05: Error de red durante el envío al backend.
     * Sumar reintento y esperar backoff.
     */
    suspend fun registrarFalloReintento(id: String, esAlerta: Boolean): Int = withContext(Dispatchers.IO) {
        if (esAlerta) {
            colaAlertaDao.incrementarReintentos(id)
            val actualizado = colaAlertaDao.buscarPorId(id)
            actualizado?.reintentos ?: 1
        } else {
            colaReporteDao.incrementarReintentos(id)
            val actualizado = colaReporteDao.buscarPorId(id)
            actualizado?.reintentos ?: 1
        }
    }

    /**
     * A-05: Cálculo de tiempo de espera creciente (exponential backoff) para el siguiente reintento.
     * Retorna tiempo en milisegundos.
     */
    fun calcularEsperaBackoffMs(reintentos: Int): Long {
        val backoffBaseSegundos = 15L // 15s inicial
        val factorExponencial = 1L shl minOf(reintentos, 6) // máximo 2^6 = 64
        val esperaMs = backoffBaseSegundos * factorExponencial * 1000L
        return minOf(esperaMs, 10 * 60 * 1000L) // límite máximo de 10 minutos
    }

    /**
     * P11: Métrica de estado actual de la cola (para diagnóstico y frontend de monitoreo).
     */
    suspend fun obtenerEstadisticasCola(): EstadisticasCola = withContext(Dispatchers.IO) {
        val ahora = System.currentTimeMillis()
        val alertasPendientes = colaAlertaDao.contarPendientes(ahora)
        val reportesPendientes = colaReporteDao.contarPendientes(ahora)
        val tamanoBytesReportes = colaReporteDao.obtenerTamanoTotalBytes(ahora) ?: 0L

        val reintentosAlertas = colaAlertaDao.sumarTotalReintentos() ?: 0
        val reintentosReportes = colaReporteDao.sumarTotalReintentos() ?: 0

        val proximoVencimientoAlerta = colaAlertaDao.obtenerProximoVencimiento(ahora)
        val proximoVencimientoReporte = colaReporteDao.obtenerProximoVencimiento(ahora)

        val proximoVencimientoMs = listOfNotNull(proximoVencimientoAlerta, proximoVencimientoReporte).minOrNull()

        EstadisticasCola(
            totalAlertasPendientes = alertasPendientes,
            totalReportesPendientes = reportesPendientes,
            totalElementosPendientes = alertasPendientes + reportesPendientes,
            tamanoTotalBytes = tamanoBytesReportes,
            totalReintentosAcumulados = reintentosAlertas + reintentosReportes,
            proximoVencimientoMs = proximoVencimientoMs
        )
    }

    /**
     * P5.1 / Malla: Control de paquetes vistos en la red de malla.
     */
    suspend fun registrarPaqueteVisto(paqueteId: String, alertaId: String): Boolean = withContext(Dispatchers.IO) {
        val resultado = paqueteVistoDao.registrarPaquete(
            PaqueteVistoEntity(
                paqueteId = paqueteId,
                alertaId = alertaId,
                visto_en = System.currentTimeMillis(),
                retransmitido = 0
            )
        )
        resultado != -1L
    }

    suspend fun fuePaqueteVisto(paqueteId: String): Boolean = withContext(Dispatchers.IO) {
        paqueteVistoDao.existePaquete(paqueteId)
    }
}

