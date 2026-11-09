package com.senderoseguro.server.desarrollo

import com.senderoseguro.server.pasarela.AlertaSms
import com.senderoseguro.server.pasarela.RegistroAlertasSms
import com.senderoseguro.server.plataforma.ejecutar
import java.sql.Connection
import java.util.UUID

/**
 * Registro de prueba de las alertas que llegan por SMS, para probar la pasarela en local mientras el Módulo 2
 * conecta el suyo. Solo se usa con `db.datosPrueba = true`. Si el tipo no existe en el catálogo publicado, la
 * registra como genérica.
 */
object RegistroSmsPrueba : RegistroAlertasSms {
    override fun registrar(conn: Connection, sms: AlertaSms): UUID {
        val d = sms.datos
        val id = UUID.fromString(d.idCorto + UUID.randomUUID().toString().substring(8))
        val insertadas = conn.ejecutar(
            """INSERT INTO alerta (id, usuario_id, dispositivo_id, tipo_incidente_id, version_catalogo_id,
                                   canal_recepcion, detonador, activada_en, enviada_en)
               SELECT ?, ?, (SELECT dp.id FROM dispositivo dp WHERE dp.usuario_id = ? ORDER BY dp.ultimo_visto_en DESC NULLS LAST LIMIT 1),
                      tv.tipo_incidente_id, tv.version_catalogo_id, 'SMS', 'BOTON', ?::timestamptz, ?::timestamptz
               FROM tipo_incidente_version tv
               JOIN version_catalogo v ON v.id = tv.version_catalogo_id
               WHERE v.estado = 'PUBLICADA' AND tv.activo AND tv.aplica_a IN ('ALERTA', 'AMBOS')
                 AND (tv.tipo_incidente_id = ? OR tv.es_generica)
               ORDER BY tv.tipo_incidente_id = ? DESC LIMIT 1""",
            id, sms.usuarioId, sms.usuarioId, d.activadaEn, d.activadaEn, d.tipoIncidenteId, d.tipoIncidenteId,
        )
        check(insertadas == 1) { "No hay catálogo publicado para registrar la alerta del SMS" }
        conn.ejecutar(
            """INSERT INTO ubicacion_alerta (alerta_id, latitud, longitud, precision_m, fuente, capturada_en)
               VALUES (?, ?, ?, ?, 'GNSS', ?::timestamptz)""",
            id, d.latitud, d.longitud, d.precisionM, d.activadaEn,
        )
        conn.ejecutar(
            "INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, actor_id) VALUES (?, NULL, 'ENVIADA', ?)",
            id, sms.usuarioId,
        )
        return id
    }
}
