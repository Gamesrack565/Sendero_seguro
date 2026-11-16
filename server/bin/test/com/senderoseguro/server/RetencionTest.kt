package com.senderoseguro.server

import com.senderoseguro.server.auditoria.RetencionTrayectos
import com.senderoseguro.server.desarrollo.AlertasPrueba
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Retención de trayectos (P13): se borran los terminados hace más de 30 días que no tuvieron alerta. */
class RetencionTest : PruebaConBaseDatos() {

    /** Un trayecto de Ana, terminado (o en curso) hace [dias] días. */
    private fun trayecto(dias: Int, estado: String = "FINALIZADO"): UUID = UUID.randomUUID().also {
        sql(
            """INSERT INTO trayecto (id, usuario_id, origen_latitud, origen_longitud, destino_latitud, destino_longitud,
                                     estado, iniciado_en, finalizado_en)
               VALUES (?, ?, 19.5, -99.1, 19.6, -99.2, ?::trayecto_estado, now() - make_interval(days => ?) - interval '1 hour',
                       CASE WHEN ? = 'EN_CURSO' THEN NULL ELSE now() - make_interval(days => ?) END)""",
            it, AlertasPrueba.ANA, estado, dias, estado, dias,
        )
    }

    private fun existe(id: UUID) = numero("SELECT count(*) FROM trayecto WHERE id = ?", id) == 1

    @Test
    fun `borra los viejos sin alerta con lo que cuelga de ellos, y deja los demas`() {
        val viejo = trayecto(40)
        sql("INSERT INTO checkin_trayecto (trayecto_id, esperado_en, confirmado_en) VALUES (?, now() - interval '40 days', now() - interval '40 days')", viejo)
        sql(
            """INSERT INTO enlace_ubicacion (trayecto_id, token_hash, creado_en, vence_en)
               VALUES (?, ?, now() - interval '40 days', now() - interval '39 days')""",
            viejo, UUID.randomUUID().toString().replace("-", "").repeat(2),
        )
        sql(
            "INSERT INTO notificacion (trayecto_id, usuario_id, tipo, canal, estado) VALUES (?, ?, 'TRAYECTO_COMPARTIDO', 'PUSH', 'ENVIADA')",
            viejo, AlertasPrueba.CUENTAS.getValue("beto"),
        )
        val viejoConAlerta = trayecto(40)
        val alertaId = alerta()
        sql("UPDATE alerta SET trayecto_id = ? WHERE id = ?", viejoConAlerta, alertaId)
        val reciente = trayecto(10)
        val cancelado = trayecto(35, "CANCELADO")
        val enCurso = trayecto(45, "EN_CURSO")
        val antes = numero("SELECT count(*) FROM bitacora WHERE accion = 'PURGAR_TRAYECTOS'")

        assertTrue(RetencionTrayectos(dataSource, dias = 30).purgar() >= 2)
        assertEquals(listOf(false, true, true, false, true), listOf(viejo, viejoConAlerta, reciente, cancelado, enCurso).map(::existe))
        assertEquals(0, numero("SELECT count(*) FROM checkin_trayecto WHERE trayecto_id = ?", viejo))
        assertEquals(0, numero("SELECT count(*) FROM enlace_ubicacion WHERE trayecto_id = ?", viejo))
        assertEquals(0, numero("SELECT count(*) FROM notificacion WHERE trayecto_id = ?", viejo))
        assertEquals(antes + 1, numero("SELECT count(*) FROM bitacora WHERE accion = 'PURGAR_TRAYECTOS'"))

        assertEquals(0, RetencionTrayectos(dataSource, dias = 30).purgar(), "Sin nada que borrar no escribe en la bitácora")
        assertEquals(antes + 1, numero("SELECT count(*) FROM bitacora WHERE accion = 'PURGAR_TRAYECTOS'"))
        sql("UPDATE trayecto SET estado = 'FINALIZADO', finalizado_en = now() WHERE id = ?", enCurso)
        sql("DELETE FROM trayecto WHERE id = ANY(ARRAY[?, ?]::uuid[])", reciente, enCurso)
    }
}
