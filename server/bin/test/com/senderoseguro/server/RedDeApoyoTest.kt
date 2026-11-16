package com.senderoseguro.server

import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.dispersion.AtencionServicio
import com.senderoseguro.server.dispersion.CambioEstado
import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.RedDeApoyo
import com.senderoseguro.server.dispersion.ResultadoDispersion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.ejecutar
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Aviso a la red de apoyo y enlace de ubicación (RF-9, CU-16). */
class RedDeApoyoTest : PruebaConBaseDatos() {

    private val atencion by lazy { AtencionServicio(dataSource, ConsultaAlertas(dataSource), EscalamientoServicio(dataSource), enlaces) }
    private val caseta = UsuarioToken(AlertasPrueba.CUENTAS.getValue("caseta"), Rol.OPERADOR, null, null)
    private val ana = AlertasPrueba.CUENTAS.getValue("ana")
    private val beto = AlertasPrueba.CUENTAS.getValue("beto")
    private val mama = UUID.fromString("00000000-0000-4000-8000-0000000000c1")

    private fun avisosRed(id: UUID) = numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO'", id)

    @Test
    fun `la red de apoyo de Ana recibe un aviso por persona`() {
        val id = alerta()
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id))
        // Mamá por SMS (sin app) y Beto por push. Beto es contacto y coordinador del círculo: un solo aviso.
        assertEquals(2, avisosRed(id))
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' AND contacto_id = ? AND canal = 'SMS'", id, mama))
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' AND usuario_id = ? AND canal = 'PUSH'", id, beto))
        // Volver a despachar no repite los avisos.
        dispersion.despacharAhora(id)
        assertEquals(2, avisosRed(id))
    }

    @Test
    fun `tambien se avisa aunque no haya unidad`() {
        dispersion.despacharAhora(alerta())
        dispersion.despacharAhora(alerta())
        val id = alerta()
        assertIs<ResultadoDispersion.SinUnidad>(dispersion.despacharAhora(id))
        assertEquals(2, avisosRed(id))
    }

    @Test
    fun `el enlace muestra la ubicacion sin datos del perfil y se apaga al cerrar`() {
        val id = alerta()
        dispersion.despacharAhora(id)
        val token = assertNotNull(enlaces.tokenDeAlerta(id))
        assertEquals(43, token.length)
        assertEquals(64, texto("SELECT length(token_hash)::text FROM enlace_ubicacion WHERE alerta_id = ?", id)?.toInt())

        val publico = enlaces.consultar(token)
        assertEquals("Ana", publico.primerNombre)
        assertEquals("Unidad asignada", publico.estado)
        assertEquals(19.504507, publico.ultimaUbicacion?.latitud)

        atencion.acusar(caseta, id)
        assertEquals("Recibida por la unidad", enlaces.consultar(token).estado)
        atencion.cambiarEstado(caseta, id, CambioEstado("EN_ATENCION"))
        atencion.cambiarEstado(caseta, id, CambioEstado("ATENDIDA"))
        atencion.cambiarEstado(caseta, id, CambioEstado("CERRADA", "Todo bien"))

        val e = assertFailsWith<ApiException> { enlaces.consultar(token) }
        assertEquals(410, e.status.value)
        // Aviso de cierre a la red de apoyo (2) y al emisor (1).
        assertEquals(3, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'CIERRE'", id))
    }

    @Test
    fun `un token inventado no existe`() {
        assertEquals(404, assertFailsWith<ApiException> { enlaces.consultar("abc") }.status.value)
        assertEquals(404, assertFailsWith<ApiException> { enlaces.consultar("A".repeat(43)) }.status.value)
    }

    @Test
    fun `los contactos sin confirmar no reciben aviso y SOLO_AVISO va sin enlace`() {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.ejecutar(
                    """INSERT INTO contacto_confianza (usuario_id, nombre, telefono, estado_confirmacion, canal_preferido, prioridad)
                       VALUES (?, 'Sin confirmar', '5520000098', 'PENDIENTE', 'SMS', 3)""",
                    ana,
                )
                conn.ejecutar(
                    """INSERT INTO contacto_confianza (usuario_id, nombre, telefono, nivel_acceso, estado_confirmacion, canal_preferido, prioridad)
                       VALUES (?, 'Solo aviso', '5520000099', 'SOLO_AVISO', 'CONFIRMADO', 'SMS', 4)""",
                    ana,
                )
                val lista = RedDeApoyo.destinatarios(conn, ana)
                assertEquals(3, lista.size)
                assertEquals(listOf(true, true, false), lista.map { it.conEnlace })
            } finally {
                conn.rollback()
            }
        }
    }

    @Test
    fun `al cancelar se avisa a la unidad y a la red, se liberan las unidades y se apaga el enlace`() {
        val id = alerta()
        dispersion.despacharAhora(id)
        val token = assertNotNull(enlaces.tokenDeAlerta(id))
        // Lo que hace la cancelación del Módulo 1 antes de llamar a la dispersión.
        sql("UPDATE alerta SET estado = 'CANCELADA', cancelada_en = now(), motivo_cancelacion = 'FALSA_ALARMA' WHERE id = ?", id)
        dispersion.cancelada(id)
        dispersion.cancelada(id)

        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'CANCELACION' AND usuario_id = ?", id, caseta.usuarioId))
        assertEquals(3, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'CANCELACION'", id))
        assertEquals("DISPONIBLE", texto("SELECT estado::text FROM unidad_atencion WHERE nombre = 'Caseta ESCOM'"))
        assertEquals(410, assertFailsWith<ApiException> { enlaces.consultar(token) }.status.value)
    }

    @Test
    fun `el enlace y la consola muestran velocidad, rumbo y bateria`() {
        val id = alerta()
        dispersion.despacharAhora(id)
        sql(
            """INSERT INTO ubicacion_alerta (alerta_id, latitud, longitud, precision_m, velocidad_ms, rumbo_grados, bateria, fuente, capturada_en)
               VALUES (?, 19.5050, -99.1470, 8, 1.4, 90, 76, 'GNSS', now() + interval '1 second')""",
            id,
        )
        val u = assertNotNull(enlaces.consultar(assertNotNull(enlaces.tokenDeAlerta(id))).ultimaUbicacion)
        assertEquals(1.4, u.velocidadMs)
        assertEquals(90, u.rumboGrados)
        assertEquals(76, u.bateria)
        assertEquals(76, ConsultaAlertas(dataSource).detalle(caseta, id).ultimaUbicacion?.bateria)
    }
}
