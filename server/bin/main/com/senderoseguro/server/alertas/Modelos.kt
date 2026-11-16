package com.senderoseguro.server.alertas

import kotlinx.serialization.Serializable

// Contrato de P4 (docs/api/openapi.yaml, AlertaNueva y AlertaRegistrada), en camelCase como el resto del servidor.

/** Primera ubicación de la alerta. Si no llegan, `fuente` es GNSS y `capturadaEn` es `activadaEn`. */
@Serializable
data class UbicacionNueva(
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val velocidadMs: Double? = null,
    val rumboGrados: Int? = null,
    val bateria: Int? = null,
    val fuente: String? = null,
    val capturadaEn: String? = null,
)

/**
 * Lo que manda la app al activar una alerta. El `id` lo genera el teléfono y hace la llamada idempotente.
 * Sin `tipoIncidenteId` (o con uno que no existe en el catálogo) se registra como genérica (RF-6).
 */
@Serializable
data class AlertaNueva(
    val id: String,
    val tipoIncidenteId: Int? = null,
    val versionCatalogoId: Int? = null,
    val tipoSugeridoId: Int? = null,
    val confianzaSugerencia: Double? = null,
    val descripcion: String? = null,
    val canalRecepcion: String? = null,
    val saltosMalla: Int? = null,
    val firmaPaquete: String? = null,
    val detonador: String? = null,
    val nivelBateria: Int? = null,
    val trayectoId: String? = null,
    val activadaEn: String,
    val enviadaEn: String? = null,
    val ubicacion: UbicacionNueva,
)

/** Respuesta de `POST /alertas`: 201 la primera vez, 200 en un reenvío. */
@Serializable
data class AlertaRegistrada(
    val id: String,
    val folio: String,
    val estado: String,
    val registradaEn: String,
    val enlaceSeguimiento: String?,
)

/**
 * Lo que manda el teléfono que retransmite un paquete de la malla (`POST /malla/relevos`). Los ids cortos van en
 * hexadecimal: `idCortoAlerta` son los primeros 8 caracteres del UUID de la alerta y `idCortoUsuario` los últimos 8
 * del UUID de la persona que la activó, sin guiones.
 */
@Serializable
data class RelevoMalla(
    val idCortoAlerta: String,
    val idCortoUsuario: String,
    val latitud: Double,
    val longitud: Double,
    val nivelBateria: Int? = null,
    val ttl: Int? = null,
    val saltosMalla: Int? = null,
    val activadaEn: String,
)

/** Una lectura de `POST /alertas/{id}/ubicaciones`. Sin `fuente`, GNSS. */
@Serializable
data class LecturaUbicacion(
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val velocidadMs: Double? = null,
    val rumboGrados: Int? = null,
    val bateria: Int? = null,
    val fuente: String? = null,
    val capturadaEn: String,
)

@Serializable
data class LoteUbicaciones(val lecturas: List<LecturaUbicacion>)

/** Los `datos` de un elemento UBICACION de `/sync/lote`. */
@Serializable
data class UbicacionesSync(val alertaId: String, val lecturas: List<LecturaUbicacion>)

/** Respuesta de `POST /alertas/{id}/ubicaciones`: cuántas lecturas entraron y cuántas ya estaban. */
@Serializable
data class UbicacionesAgregadas(val insertadas: Int, val duplicadas: Int)
