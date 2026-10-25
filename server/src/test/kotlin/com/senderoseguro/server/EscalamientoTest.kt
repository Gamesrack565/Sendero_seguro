package com.senderoseguro.server

import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.ResultadoDispersion
import com.senderoseguro.server.dispersion.ResultadoEscalamiento
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Escalamiento por falta de acuse, con las cadenas de los datos iniciales. */
class EscalamientoTest : PruebaConBaseDatos() {

    private val escalamiento by lazy { EscalamientoServicio(dataSource) }
    private val beto = UUID.fromString("00000000-0000-4000-8000-000000000002")

    private fun vencerYEscalar(id: UUID): ResultadoEscalamiento {
        AlertasPrueba.vencerPlazo(dataSource, id)
        return escalamiento.escalarPorFaltaDeAcuse(id)
    }

    private fun avisos(id: UUID, tipo: String) =
        numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = ?::notificacion_tipo", id, tipo)

    @Test
    fun `una alerta generica sin acuse recorre toda la cadena de Seguridad IPN`() {
        val id = alerta()
        val inicial = assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id))
        assertEquals("Caseta ESCOM", inicial.unidadNombre)

        // Nivel 1: SSC CDMX (C5). La caseta se libera y se reinicia el plazo de 120 s.
        val antes = Instant.now()
        val n1 = assertIs<ResultadoEscalamiento.Escalada>(vencerYEscalar(id))
        assertEquals(1, n1.nivel)
        assertEquals("CORPORACION", n1.destino)
        assertEquals("SSC CDMX (C5)", n1.corporacion)
        assertEquals("Patrulla Ticomán", n1.unidad)
        assertTrue(Duration.between(antes, n1.acuseLimiteEn!!).seconds in 115..125)
        assertEquals("DISPONIBLE", texto("SELECT estado::text FROM unidad_atencion WHERE id = ?", inicial.unidadId))
        assertEquals("ESCALADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        assertEquals(1, avisos(id, "ESCALAMIENTO"))

        // Nivel 2: PBI.
        val n2 = assertIs<ResultadoEscalamiento.Escalada>(vencerYEscalar(id))
        assertEquals(2, n2.nivel)
        assertEquals("PBI Zacatenco", n2.unidad)
        assertEquals("DISPONIBLE", texto("SELECT estado::text FROM unidad_atencion WHERE nombre = 'Patrulla Ticomán'"))

        // Nivel 3: Coordinador del círculo de Ana (Beto). No hay unidad; se le avisa.
        val n3 = assertIs<ResultadoEscalamiento.Escalada>(vencerYEscalar(id))
        assertEquals(3, n3.nivel)
        assertEquals("COORDINADOR_CIRCULO", n3.destino)
        assertNull(n3.unidad)
        assertEquals(1, numero(
            "SELECT count(*) FROM notificacion WHERE alerta_id = ? AND usuario_id = ? AND tipo = 'ALERTA_PRIORITARIA'", id, beto,
        ))
        assertEquals(0, numero("SELECT count(*) FROM asignacion WHERE alerta_id = ? AND liberada_en IS NULL", id))

        // Se agota la cadena: sin acuse y aviso a los Administradores.
        assertEquals(ResultadoEscalamiento.CadenaAgotada, vencerYEscalar(id))
        assertEquals("true", texto("SELECT sin_acuse::text FROM alerta WHERE id = ?", id))
        assertNull(texto("SELECT acuse_limite_en FROM alerta WHERE id = ?", id))
        assertEquals(1, avisos(id, "SIN_ACUSE"))
        assertEquals(3, numero("SELECT count(*) FROM escalamiento WHERE alerta_id = ?", id))
        assertEquals(3, numero("SELECT nivel_escalamiento FROM alerta WHERE id = ?", id))

        // Ya no vuelve a escalar.
        assertEquals(ResultadoEscalamiento.NoAplica, vencerYEscalar(id))
    }

    @Test
    fun `si el plazo no ha vencido no escala`() {
        val id = alerta()
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id))
        assertEquals(ResultadoEscalamiento.NoAplica, escalamiento.escalarPorFaltaDeAcuse(id))
    }

    @Test
    fun `una alerta con acuse no escala`() {
        val id = alerta()
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id))
        sql("UPDATE alerta SET estado = 'ACUSADA', acusada_en = now() WHERE id = ?", id)
        assertEquals(ResultadoEscalamiento.NoAplica, vencerYEscalar(id))
    }

    @Test
    fun `si el siguiente nivel no tiene unidades pasa al que sigue en la misma vuelta`() {
        val id = alerta()
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id))
        sql("UPDATE unidad_atencion SET estado = 'ATENDIENDO' WHERE corporacion_id = (SELECT id FROM corporacion WHERE canal_codigo = 'SSC_C5')")

        val r = assertIs<ResultadoEscalamiento.Escalada>(vencerYEscalar(id))
        assertEquals(2, r.nivel)
        assertEquals("PBI Zacatenco", r.unidad)
        assertEquals("SIN_UNIDAD_DISPONIBLE", texto(
            "SELECT detalle->>'resultado' FROM evento_alerta WHERE alerta_id = ? AND detalle->>'nivel' = '1'", id,
        ))
    }

    @Test
    fun `el temporizador toma la alerta que se quedo sin unidad`() {
        dispersion.despacharAhora(alerta())
        dispersion.despacharAhora(alerta())
        val id = alerta()
        assertIs<ResultadoDispersion.SinUnidad>(dispersion.despacharAhora(id))

        assertEquals(1, escalamiento.escalarVencidas())
        assertEquals("ESCALADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        assertEquals("Patrulla Ticomán", texto(
            "SELECT u.nombre FROM asignacion a JOIN unidad_atencion u ON u.id = a.unidad_id WHERE a.alerta_id = ? AND a.liberada_en IS NULL", id,
        ))
    }
}
