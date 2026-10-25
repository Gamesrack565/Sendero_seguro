package com.senderoseguro.server

import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.dispersion.AtencionServicio
import com.senderoseguro.server.dispersion.CambioEstado
import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.ResultadoDispersion
import com.senderoseguro.server.dispersion.ResultadoEscalamiento
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Acuse, cambios de estado, escalamiento manual y consulta desde la consola. */
class AtencionTest : PruebaConBaseDatos() {

    private val escalamiento by lazy { EscalamientoServicio(dataSource) }
    private val consulta by lazy { ConsultaAlertas(dataSource) }
    private val atencion by lazy { AtencionServicio(dataSource, consulta, escalamiento) }

    private fun cuenta(nombre: String, rol: Rol) = UsuarioToken(AlertasPrueba.CUENTAS.getValue(nombre), rol, null, null)
    private val caseta = cuenta("caseta", Rol.OPERADOR)
    private val operadorC5 = cuenta("c5", Rol.OPERADOR)
    private val operadorPbi = cuenta("pbi", Rol.OPERADOR)
    private val admin = cuenta("admin", Rol.ADMINISTRADOR)
    private val ana = cuenta("ana", Rol.INTEGRANTE)
    private val beto = cuenta("beto", Rol.COORDINADOR)

    /** Alerta genérica asignada a la Caseta ESCOM. */
    private fun alertaEnCaseta(): UUID = alerta().also {
        assertEquals("Caseta ESCOM", assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(it)).unidadNombre)
    }

    private fun codigoDe(bloque: () -> Unit): String = assertFailsWith<ApiException> { bloque() }.codigo

    @Test
    fun `el acuse detiene el escalamiento y avisa al emisor`() {
        val id = alertaEnCaseta()
        val d = atencion.acusar(caseta, id)
        assertEquals("ACUSADA", d.estado)
        assertNotNull(d.acusadaEn)
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND usuario_id = ? AND tipo = 'ACUSE'", id, ana.usuarioId))

        // Aunque el plazo venza, una alerta acusada ya no escala.
        AlertasPrueba.vencerPlazo(dataSource, id)
        assertEquals(ResultadoEscalamiento.NoAplica, escalamiento.escalarPorFaltaDeAcuse(id))

        // Repetir el acuse no cambia nada.
        assertEquals(d.acusadaEn, atencion.acusar(caseta, id).acusadaEn)
        assertEquals(1, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND estado_nuevo = 'ACUSADA'", id))
    }

    @Test
    fun `solo el operador de la unidad asignada puede dar el acuse`() {
        val id = alertaEnCaseta()
        assertEquals("NO_ENCONTRADO", codigoDe { atencion.acusar(operadorC5, id) })
        assertEquals("SIN_PERMISO", codigoDe { atencion.acusar(admin, id) })
        assertEquals("SIN_PERMISO", codigoDe { atencion.acusar(ana, id) })
    }

    @Test
    fun `un acuse que llega despues de escalar se registra y responde VENCIDO`() {
        val id = alertaEnCaseta()
        AlertasPrueba.vencerPlazo(dataSource, id)
        assertIs<ResultadoEscalamiento.Escalada>(escalamiento.escalarPorFaltaDeAcuse(id))

        assertEquals("VENCIDO", codigoDe { atencion.acusar(caseta, id) })
        assertEquals(1, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND detalle->>'accion' = 'ACUSE_TARDIO'", id))
        assertEquals("ESCALADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
    }

    @Test
    fun `la atencion completa termina con la unidad libre`() {
        val id = alertaEnCaseta()
        assertEquals("TRANSICION_INVALIDA", codigoDe { atencion.cambiarEstado(caseta, id, CambioEstado("EN_ATENCION")) })

        atencion.acusar(caseta, id)
        assertEquals("EN_ATENCION", atencion.cambiarEstado(caseta, id, CambioEstado("EN_ATENCION")).estado)
        assertEquals("ATENDIENDO", texto("SELECT estado::text FROM unidad_atencion WHERE nombre = 'Caseta ESCOM'"))
        // Repetir el mismo estado no falla (reintentos de la consola).
        assertEquals("EN_ATENCION", atencion.cambiarEstado(caseta, id, CambioEstado("EN_ATENCION")).estado)

        assertEquals("TRANSICION_INVALIDA", codigoDe { atencion.cambiarEstado(caseta, id, CambioEstado("CERRADA", "x")) })
        atencion.cambiarEstado(caseta, id, CambioEstado("ATENDIDA"))
        assertEquals("VALIDACION", codigoDe { atencion.cambiarEstado(caseta, id, CambioEstado("CERRADA")) })

        val cerrada = atencion.cambiarEstado(caseta, id, CambioEstado("CERRADA", "Se acompañó a la persona a la caseta", "Sin lesiones"))
        assertEquals("CERRADA", cerrada.estado)
        assertEquals("Se acompañó a la persona a la caseta", cerrada.resultadoCierre)
        assertNotNull(cerrada.cerradaEn)
        assertEquals("DISPONIBLE", texto("SELECT estado::text FROM unidad_atencion WHERE nombre = 'Caseta ESCOM'"))
        assertEquals(0, numero("SELECT count(*) FROM asignacion WHERE alerta_id = ? AND liberada_en IS NULL", id))
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'CIERRE'", id))
        assertEquals(2, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'CAMBIO_ESTADO'", id))
        // Ya cerrada: la unidad que la atendió recibe 409, no 403.
        assertEquals("TRANSICION_INVALIDA", codigoDe { atencion.cambiarEstado(caseta, id, CambioEstado("ATENDIDA")) })
        assertEquals("CONFLICTO", codigoDe { atencion.escalar(caseta, id, null) })
    }

    @Test
    fun `el administrador puede cerrar y un operador sin asignacion no`() {
        val id = alertaEnCaseta()
        atencion.acusar(caseta, id)
        assertEquals("NO_ENCONTRADO", codigoDe { atencion.cambiarEstado(operadorC5, id, CambioEstado("EN_ATENCION")) })
        atencion.cambiarEstado(admin, id, CambioEstado("EN_ATENCION"))
        atencion.cambiarEstado(admin, id, CambioEstado("ATENDIDA"))
        assertEquals("CERRADA", atencion.cambiarEstado(admin, id, CambioEstado("CERRADA", "Falsa alarma confirmada")).estado)
    }

    @Test
    fun `escalar a mano sin acuse libera la unidad y reinicia el plazo`() {
        val id = alertaEnCaseta()
        val e = atencion.escalar(caseta, id, "No hay personal en la caseta")
        assertEquals(1, e.nivel)
        assertEquals("CORPORACION", e.destinoTipo)
        assertEquals("MANUAL", e.motivo)
        assertEquals("ESCALADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        assertEquals("DISPONIBLE", texto("SELECT estado::text FROM unidad_atencion WHERE nombre = 'Caseta ESCOM'"))
        assertEquals("t", texto("SELECT (acuse_limite_en > now())::text FROM alerta WHERE id = ?", id)?.take(1))
        // La caseta ya no puede actuar; la patrulla nueva sí.
        assertEquals("SIN_PERMISO", codigoDe { atencion.escalar(caseta, id, null) })
    }

    @Test
    fun `escalar a mano en atencion manda apoyo y la unidad que atiende se queda`() {
        val id = alertaEnCaseta()
        atencion.acusar(caseta, id)
        atencion.cambiarEstado(caseta, id, CambioEstado("EN_ATENCION"))

        val e = atencion.escalar(caseta, id, "Se necesita apoyo de la policía")
        assertEquals(1, e.nivel)
        assertEquals("EN_ATENCION", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        assertEquals("ATENDIENDO", texto("SELECT estado::text FROM unidad_atencion WHERE nombre = 'Caseta ESCOM'"))
        assertEquals(2, numero("SELECT count(*) FROM asignacion WHERE alerta_id = ? AND liberada_en IS NULL", id))

        // La patrulla de apoyo da su acuse sin cambiar el estado.
        sql("UPDATE operador SET unidad_id = (SELECT id FROM unidad_atencion WHERE nombre = 'Patrulla Ticomán') WHERE usuario_id = ?", operadorC5.usuarioId)
        try {
            assertEquals("EN_ATENCION", atencion.acusar(operadorC5, id).estado)
        } finally {
            sql("UPDATE operador SET unidad_id = (SELECT id FROM unidad_atencion WHERE nombre = 'Patrulla Lindavista') WHERE usuario_id = ?", operadorC5.usuarioId)
        }

        // Al cerrar se liberan las dos.
        atencion.cambiarEstado(caseta, id, CambioEstado("ATENDIDA"))
        atencion.cambiarEstado(caseta, id, CambioEstado("CERRADA", "Atendida con apoyo"))
        assertEquals(0, numero("SELECT count(*) FROM asignacion WHERE alerta_id = ? AND liberada_en IS NULL", id))
        assertEquals(0, numero("SELECT count(*) FROM unidad_atencion WHERE estado IN ('ASIGNADA', 'ATENDIENDO')"))
    }

    @Test
    fun `escalar sin pasos restantes responde CONFLICTO`() {
        val id = alertaEnCaseta()
        atencion.escalar(admin, id, null)
        atencion.escalar(admin, id, null)
        atencion.escalar(admin, id, null)
        assertEquals("CONFLICTO", codigoDe { atencion.escalar(admin, id, null) })
        assertEquals(3, numero("SELECT nivel_escalamiento FROM alerta WHERE id = ?", id))
    }

    @Test
    fun `cada quien ve lo que le toca`() {
        val id = alertaEnCaseta()
        // Consola: datos del emisor y asignaciones.
        val consola = consulta.detalle(caseta, id)
        assertEquals("Ana Prueba", consola.emisor?.nombre)
        assertEquals(1, consola.asignaciones.size)
        assertNotNull(consola.ultimaUbicacion)
        // Emisor: su alerta, sin datos de la consola.
        val propia = consulta.detalle(ana, id)
        assertNull(propia.emisor)
        assertNull(propia.coaccionDetectada)
        assertTrue(propia.asignaciones.isEmpty())
        // Otros: no existe.
        assertEquals("NO_ENCONTRADO", codigoDe { consulta.detalle(beto, id) })
        assertEquals("NO_ENCONTRADO", codigoDe { consulta.detalle(operadorPbi, id) })
    }

    @Test
    fun `con coaccion el emisor la ve cancelada y no recibe avisos`() {
        val id = alertaEnCaseta()
        sql("UPDATE alerta SET coaccion_detectada = true WHERE id = ?", id)
        assertEquals("CANCELADA", consulta.detalle(ana, id).estado)
        assertEquals("ASIGNADA", consulta.detalle(caseta, id).estado)
        atencion.acusar(caseta, id)
        assertEquals(0, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND usuario_id = ?", id, ana.usuarioId))
    }

    @Test
    fun `la lista del operador trae solo sus alertas y pagina`() {
        val mias = List(3) { alertaEnCaseta().also { AlertasPrueba.liberarUnidades(dataSource) } }
        val pagina = consulta.listar(caseta, ConsultaAlertas.Filtros(listOf("ASIGNADA"), null, null, 2, null))
        assertEquals(2, pagina.elementos.size)
        val cursor = (pagina.siguienteCursor as JsonPrimitive).content
        val resto = consulta.listar(caseta, ConsultaAlertas.Filtros(listOf("ASIGNADA"), null, null, 200, cursor))
        assertEquals(JsonNull, resto.siguienteCursor)
        val ids = (pagina.elementos + resto.elementos).map { it.id }
        assertTrue(ids.containsAll(mias.map { it.toString() }))
        assertEquals(ids.size, ids.distinct().size)
        // El operador de PBI no tuvo ninguna de estas.
        val pbi = consulta.listar(operadorPbi, ConsultaAlertas.Filtros(listOf("ASIGNADA"), null, null, 200, null)).elementos.map { it.id }
        assertTrue(mias.none { it.toString() in pbi })
        assertEquals("SIN_PERMISO", codigoDe { consulta.listar(ana, ConsultaAlertas.Filtros(null, null, null, 50, null)) })
    }
}
