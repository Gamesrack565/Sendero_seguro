package com.senderoseguro.server

import com.senderoseguro.server.dispersion.ResultadoDispersion
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Canalización y asignación inicial. */
class DispersionTest : PruebaConBaseDatos() {

    private val operadorCaseta = UUID.fromString("00000000-0000-4000-8000-000000000011")
    private val operadorC5 = UUID.fromString("00000000-0000-4000-8000-000000000012")

    @Test
    fun `una alerta generica frente a la ESCOM va a la caseta mas cercana`() {
        val antes = Instant.now()
        val id = alerta()
        val r = assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id))

        assertEquals("Caseta ESCOM", r.unidadNombre)
        assertEquals("Seguridad IPN", r.corporacion)
        assertTrue(r.distanciaM!! < 50, "distancia ${r.distanciaM}")
        // La regla de la genérica da 120 s para el acuse.
        val plazo = Duration.between(antes, r.acuseLimiteEn).seconds
        assertTrue(plazo in 115..125, "plazo $plazo s")

        assertEquals("ASIGNADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        assertEquals("ASIGNADA", texto("SELECT estado::text FROM unidad_atencion WHERE id = ?", r.unidadId))
        assertEquals(1, numero("SELECT count(*) FROM asignacion WHERE alerta_id = ? AND rol = 'PRINCIPAL'", id))
        assertEquals(1, numero(
            "SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND estado_anterior = 'ENVIADA' AND estado_nuevo = 'ASIGNADA'", id,
        ))
    }

    @Test
    fun `el operador de la unidad asignada recibe el aviso prioritario y el resto del canal el informativo`() {
        val id = alerta("ASALTO_ROBO")
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id))
        // Patrulla Ticomán no tiene operador; el operador de Patrulla Lindavista (C5) recibe el informativo.
        assertEquals(0, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'ALERTA_PRIORITARIA'", id))
        assertEquals(1, numero(
            "SELECT count(*) FROM notificacion WHERE alerta_id = ? AND usuario_id = ? AND tipo = 'ALERTA_INFORMATIVA'", id, operadorC5,
        ))

        val generica = alerta()
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(generica))
        assertEquals(1, numero(
            "SELECT count(*) FROM notificacion WHERE alerta_id = ? AND usuario_id = ? AND tipo = 'ALERTA_PRIORITARIA'", generica, operadorCaseta,
        ))
    }

    @Test
    fun `el temporizador despacha una alerta que se quedo sin despachar`() {
        val id = alerta()
        sql("UPDATE alerta SET registrada_en = now() - interval '1 minute' WHERE id = ?", id)
        assertEquals(1, dispersion.despacharPendientes())
        assertEquals("ASIGNADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
    }

    @Test
    fun `una emergencia medica va a la ambulancia`() {
        val r = assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(alerta("EMERGENCIA_MEDICA")))
        assertEquals("Ambulancia ERUM 12", r.unidadNombre)
    }

    @Test
    fun `un asalto va a la patrulla del C5 mas cercana`() {
        val r = assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(alerta("ASALTO_ROBO")))
        assertEquals("Patrulla Ticomán", r.unidadNombre)
    }

    @Test
    fun `despachar dos veces la misma alerta no la asigna dos veces`() {
        val id = alerta()
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(id))
        assertEquals(ResultadoDispersion.YaDespachada("ASIGNADA"), dispersion.despacharAhora(id))
    }

    @Test
    fun `dos despachos al mismo tiempo de la misma alerta asignan una sola vez`() {
        val id = alerta()
        val hilos = Executors.newFixedThreadPool(2)
        val resultados = hilos.invokeAll(List(2) { Callable { dispersion.despacharAhora(id) } }).map { it.get() }
        hilos.shutdown()
        assertEquals(1, resultados.count { it is ResultadoDispersion.Asignada })
        assertEquals(1, resultados.count { it is ResultadoDispersion.YaDespachada })
    }

    @Test
    fun `sin unidades disponibles la alerta queda ENVIADA con el plazo vencido`() {
        // Seguridad IPN tiene dos unidades en servicio; la Caseta Biblioteca está fuera de servicio.
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(alerta()))
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(alerta()))
        val id = alerta()
        assertEquals(ResultadoDispersion.SinUnidad("Seguridad IPN"), dispersion.despacharAhora(id))

        assertEquals("ENVIADA", texto("SELECT estado::text FROM alerta WHERE id = ?", id))
        assertEquals("t", texto("SELECT acuse_limite_en <= now() FROM alerta WHERE id = ?", id))
        assertEquals("SIN_UNIDAD_DISPONIBLE", texto(
            "SELECT detalle->>'motivo' FROM evento_alerta WHERE alerta_id = ? ORDER BY id DESC LIMIT 1", id,
        ))
        // CU-15 2a: se avisa a todo el canal y al Administrador.
        assertEquals(1, numero(
            "SELECT count(*) FROM notificacion WHERE alerta_id = ? AND usuario_id = ? AND tipo = 'ALERTA_INFORMATIVA'", id, operadorCaseta,
        ))
        assertEquals(1, numero(
            "SELECT count(*) FROM notificacion n JOIN usuario u ON u.id = n.usuario_id WHERE n.alerta_id = ? AND u.rol = 'ADMINISTRADOR'", id,
        ))
    }

    @Test
    fun `una alerta que no existe no hace nada`() {
        assertEquals(ResultadoDispersion.NoExiste, dispersion.despacharAhora(UUID.randomUUID()))
    }
}
