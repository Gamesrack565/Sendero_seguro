package com.senderoseguro.server.dispersion

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// JSON de las rutas de alertas de la consola, con los nombres de los esquemas de openapi.yaml.
// Las fechas van como texto ISO 8601 en UTC y los ids como texto.

@Serializable
data class TipoIncidenteRef(val id: Int, val nombre: String, val categoria: String? = null, val esGenerica: Boolean)

@Serializable
data class UbicacionJson(
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val fuente: String,
    val capturadaEn: String,
)

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
