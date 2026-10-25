package com.senderoseguro.server

import com.senderoseguro.server.auditoria.EventoTimeline
import com.senderoseguro.server.auditoria.LineaDeTiempo
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.dispersion.AtencionServicio
import com.senderoseguro.server.dispersion.CambioEstado
import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.ResultadoDispersion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Línea de tiempo de una alerta (P13): lo que ve la consola y lo que ve quien la emitió. */
class LineaDeTiempoTest : PruebaConBaseDatos() {

    private fun cuenta(nombre: String, rol: Rol) = UsuarioToken(AlertasPrueba.CUENTAS.getValue(nombre), rol, null, null)
    private val admin = cuenta("admin", Rol.ADMINISTRADOR)
    private val caseta = cuenta("caseta", Rol.OPERADOR)
    private val ana = cuenta("ana", Rol.INTEGRANTE)
    private val consulta get() = ConsultaAlertas(dataSource)
    private val linea get() = LineaDeTiempo(dataSource, consulta)
    private val atencion get() = AtencionServicio(dataSource, consulta, EscalamientoServicio(dataSource), enlaces)

    private fun alertaDespachada(): UUID = alerta().also { assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(it)) }

    private fun List<EventoTimeline>.estados() = filter { it.tipo == "CAMBIO_ESTADO" }.map { it.estadoNuevo }

    @Test
    fun `la consola ve todo en orden, con nombres`() {
        val id = alertaDespachada()
        atencion.acusar(caseta, id)
        atencion.cambiarEstado(caseta, id, CambioEstado("EN_ATENCION"))
        val eventos = linea.de(admin, id).eventos

        assertEquals(listOf("ENVIADA", "ASIGNADA", "ACUSADA", "EN_ATENCION"), eventos.estados())
        assertEquals(eventos.map { it.ocurridoEn }, eventos.map { it.ocurridoEn }.sorted(), "En orden cronológico")
        val asignacion = eventos.single { it.tipo == "ASIGNACION" }
        assertEquals("Caseta ESCOM", asignacion.detalle["unidad"]?.jsonPrimitive?.content)
        assertEquals("Operador Caseta", eventos.single { it.estadoNuevo == "ACUSADA" }.actor)
        val avisoMama = eventos.single { it.tipo == "NOTIFICACION" && it.detalle["destinatario"]?.jsonPrimitive?.content == "Mamá de Ana" }
        assertEquals("RED_APOYO", avisoMama.detalle["tipo"]?.jsonPrimitive?.content)
        assertTrue(eventos.any { it.tipo == "NOTIFICACION" && it.detalle["tipo"]?.jsonPrimitive?.content == "ALERTA_PRIORITARIA" })
        // El operador que la tuvo ve lo mismo que el Administrador.
        assertEquals(eventos, linea.de(caseta, id).eventos)
    }

    @Test
    fun `quien la emitio ve su version, sin datos internos, y nadie mas la ve`() {
        val id = alertaDespachada()
        atencion.acusar(caseta, id)
        val eventos = linea.de(ana, id).eventos

        assertEquals(listOf("ENVIADA", "ASIGNADA", "ACUSADA"), eventos.estados())
        assertEquals("Operador", eventos.single { it.estadoNuevo == "ACUSADA" }.actor, "Rol, no el nombre del operador")
        assertEquals("Ana Prueba", eventos.first().actor, "Su propio nombre sí")
        val avisos = eventos.filter { it.tipo == "NOTIFICACION" }
        assertTrue(avisos.isNotEmpty() && avisos.all { it.detalle["tipo"]?.jsonPrimitive?.content == "RED_APOYO" })
        assertTrue(avisos.none { "destinatario" in it.detalle })
        assertTrue(eventos.none { it.tipo == "ESCALAMIENTO" })

        val beto = cuenta("beto", Rol.COORDINADOR)
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> { linea.de(beto, id) }.status)
        val c5 = cuenta("c5", Rol.OPERADOR)
        assertEquals(HttpStatusCode.NotFound, assertFailsWith<ApiException> { linea.de(c5, id) }.status)
    }

    @Test
    fun `con el PIN de coaccion quien la emitio ve que termino en CANCELADA y la consola ve la verdad`() {
        val id = alertaDespachada()
        sql(
            """INSERT INTO intento_cancelacion (alerta_id, usuario_id, motivo, metodo, exitoso, es_coaccion, intentado_en)
               VALUES (?, ?, 'FALSA_ALARMA', 'PIN', false, true, now())""",
            id, AlertasPrueba.ANA,
        )
        sql("UPDATE alerta SET coaccion_detectada = true WHERE id = ?", id)
        Thread.sleep(20)
        atencion.acusar(caseta, id)

        val deAna = linea.de(ana, id).eventos
        assertEquals("CANCELADA", deAna.last().estadoNuevo)
        assertFalse("ACUSADA" in deAna.estados(), "Lo que pasó después de la coacción no se le muestra")
        val intento = deAna.single { it.tipo == "INTENTO_CANCELACION" }
        assertEquals("true", intento.detalle["exitoso"]?.jsonPrimitive?.content)
        assertFalse("esCoaccion" in intento.detalle)

        val consola = linea.de(admin, id).eventos
        assertTrue("ACUSADA" in consola.estados())
        assertEquals("true", consola.single { it.tipo == "INTENTO_CANCELACION" }.detalle["esCoaccion"]?.jsonPrimitive?.content)
    }
}
