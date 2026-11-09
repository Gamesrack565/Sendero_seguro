package com.senderoseguro.server.desarrollo

import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.sincronizacion.ContextoSync
import com.senderoseguro.server.sincronizacion.ElementoSync
import com.senderoseguro.server.sincronizacion.ManejadorSync
import com.senderoseguro.server.sincronizacion.ResultadoManejo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Manejador de prueba de las ALERTA del lote, para probar `/sync/lote` en local mientras el Módulo 2 conecta el
 * suyo. Solo se usa con `db.datosPrueba = true`. Lee lo mínimo de `AlertaNueva` (id, tipoIncidenteId, activadaEn y
 * ubicacion); si el tipo no está en el catálogo publicado, la registra como genérica.
 */
object SyncAlertaPrueba : ManejadorSync {
    override fun procesar(conn: Connection, contexto: ContextoSync, elemento: ElementoSync): ResultadoManejo {
        val d = elemento.datos
        fun texto(o: JsonObject, campo: String) = o[campo]?.jsonPrimitive?.contentOrNull
        val id = texto(d, "id")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: elemento.id
        val activadaEn = texto(d, "activadaEn")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return ResultadoManejo.Rechazado("VALIDACION", "activadaEn debe ser una fecha ISO 8601")
        val ubicacion = runCatching { d.getValue("ubicacion").jsonObject }.getOrNull()
        val latitud = ubicacion?.get("latitud")?.jsonPrimitive?.doubleOrNull
        val longitud = ubicacion?.get("longitud")?.jsonPrimitive?.doubleOrNull
        if (latitud == null || longitud == null || latitud !in -90.0..90.0 || longitud !in -180.0..180.0) {
            return ResultadoManejo.Rechazado("VALIDACION", "ubicacion necesita latitud y longitud válidas")
        }
        val tipo = d["tipoIncidenteId"]?.jsonPrimitive?.intOrNull ?: 0
        val folio = conn.consultarUno(
            """INSERT INTO alerta (id, usuario_id, dispositivo_id, tipo_incidente_id, version_catalogo_id,
                                   canal_recepcion, detonador, activada_en, enviada_en)
               SELECT ?, ?, ?, tv.tipo_incidente_id, tv.version_catalogo_id, 'INTERNET', 'BOTON', ?::timestamptz, ?::timestamptz
               FROM tipo_incidente_version tv
               JOIN version_catalogo v ON v.id = tv.version_catalogo_id
               WHERE v.estado = 'PUBLICADA' AND tv.activo AND tv.aplica_a IN ('ALERTA', 'AMBOS')
                 AND (tv.tipo_incidente_id = ? OR tv.es_generica)
               ORDER BY tv.tipo_incidente_id = ? DESC LIMIT 1
               RETURNING folio""",
            id, contexto.usuarioId, contexto.dispositivoId, activadaEn, elemento.creadoEn, tipo, tipo,
        ) { it.getString("folio") } ?: return ResultadoManejo.Rechazado("ERROR_INTERNO", "No hay catálogo publicado", temporal = true)
        conn.ejecutar(
            """INSERT INTO ubicacion_alerta (alerta_id, latitud, longitud, precision_m, fuente, capturada_en)
               VALUES (?, ?, ?, ?, 'GNSS', ?::timestamptz)""",
            id, latitud, longitud, ubicacion["precisionM"]?.jsonPrimitive?.doubleOrNull, activadaEn,
        )
        conn.ejecutar(
            "INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, actor_id) VALUES (?, NULL, 'ENVIADA', ?)",
            id, contexto.usuarioId,
        )
        return ResultadoManejo.Aceptado(referencia = folio, despachar = id)
    }
}
