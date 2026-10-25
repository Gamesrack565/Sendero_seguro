package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.dobleONulo
import com.senderoseguro.server.plataforma.instante
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.sql.ResultSet

// JSON de las rutas de alertas de la consola, con los nombres de los esquemas de openapi.yaml.
// Las fechas van como texto ISO 8601 en UTC y los ids como texto.

@Serializable
data class TipoIncidenteRef(val id: Int, val nombre: String, val categoria: String? = null, val esGenerica: Boolean)

@Serializable
data class UbicacionJson(
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val velocidadMs: Double? = null,
    val rumboGrados: Int? = null,
    val bateria: Int? = null,
    val fuente: String,
    val capturadaEn: String,
)

/** La última lectura de `ubicacion_alerta` de la alerta `a`, con velocidad, rumbo y batería (P6). */
internal object UltimaUbicacion {
    const val COLUMNAS = """ub.latitud::double precision AS ub_lat, ub.longitud::double precision AS ub_lon,
                   ub.precision_m::double precision AS ub_precision, ub.velocidad_ms::double precision AS ub_velocidad,
                   ub.rumbo_grados AS ub_rumbo, ub.bateria AS ub_bateria, ub.fuente::text AS ub_fuente, ub.capturada_en AS ub_capturada"""
    const val JOIN = """LEFT JOIN LATERAL (
                SELECT * FROM ubicacion_alerta WHERE alerta_id = a.id ORDER BY capturada_en DESC LIMIT 1
            ) ub ON true"""

    fun leer(rs: ResultSet): UbicacionJson? = rs.dobleONulo("ub_lat")?.let {
        UbicacionJson(
            latitud = it,
            longitud = rs.getDouble("ub_lon"),
            precisionM = rs.dobleONulo("ub_precision"),
            velocidadMs = rs.dobleONulo("ub_velocidad"),
            rumboGrados = rs.getInt("ub_rumbo").takeUnless { rs.wasNull() },
            bateria = rs.getInt("ub_bateria").takeUnless { rs.wasNull() },
            fuente = rs.getString("ub_fuente"),
            capturadaEn = rs.instante("ub_capturada")!!.toString(),
        )
    }
}

@Serializable
data class AlertaResumen(
    val id: String,
    val folio: String,
    val estado: String,
    val tipoIncidente: TipoIncidenteRef,
    val prioridad: Int,
    val canalRecepcion: String,
    val activadaEn: String,
    val acuseLimiteEn: String? = null,
    val nivelEscalamiento: Int,
    val sinAcuse: Boolean,
    val ultimaUbicacion: UbicacionJson? = null,
)

/**
 * El contrato pide `siguienteCursor` siempre, aunque sea null. El servidor omite los null (explicitNulls = false),
 * así que aquí va como JsonElement para que salga `"siguienteCursor": null` en la última página.
 */
@Serializable
data class PaginaAlertas(val elementos: List<AlertaResumen>, val siguienteCursor: JsonElement) {
    constructor(elementos: List<AlertaResumen>, siguienteCursor: String?) :
        this(elementos, siguienteCursor?.let(::JsonPrimitive) ?: JsonNull)
}

@Serializable
data class Emisor(
    val nombre: String,
    val telefonoMovil: String,
    val identificadorInstitucional: String,
    val escuela: String,
)

@Serializable
data class AsignacionJson(
    val unidadId: String,
    val unidadNombre: String,
    val corporacion: String,
    val rol: String,
    val nivelEscalamiento: Int,
    val distanciaM: Double? = null,
    val asignadaEn: String,
    val liberadaEn: String? = null,
)

/** `AlertaDetalle`: el resumen más los datos de la atención. `emisor` y `coaccionDetectada` solo van a la consola. */
@Serializable
data class AlertaDetalle(
    val id: String,
    val folio: String,
    val estado: String,
    val tipoIncidente: TipoIncidenteRef,
    val prioridad: Int,
    val canalRecepcion: String,
    val activadaEn: String,
    val acuseLimiteEn: String? = null,
    val nivelEscalamiento: Int,
    val sinAcuse: Boolean,
    val ultimaUbicacion: UbicacionJson? = null,
    val emisor: Emisor? = null,
    val descripcion: String? = null,
    val detonador: String,
    val nivelBateria: Int? = null,
    val coaccionDetectada: Boolean? = null,
    val prioridadMotivos: List<String> = emptyList(),
    val asignaciones: List<AsignacionJson> = emptyList(),
    val registradaEn: String,
    val acusadaEn: String? = null,
    val cerradaEn: String? = null,
    val resultadoCierre: String? = null,
    val canceladaEn: String? = null,
)

@Serializable
data class CambioEstado(val estado: String, val resultadoCierre: String? = null, val observaciones: String? = null)

@Serializable
data class EscalarPeticion(val comentario: String? = null)

@Serializable
data class EscalamientoJson(
    val nivel: Int,
    val destinoTipo: String,
    val corporacion: String? = null,
    val operadorId: String? = null,
    val motivo: String,
    val escaladaEn: String,
)
