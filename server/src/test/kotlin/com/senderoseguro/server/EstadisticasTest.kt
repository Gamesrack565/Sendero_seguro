package com.senderoseguro.server

import com.senderoseguro.server.auditoria.Estadisticas
import com.senderoseguro.server.auditoria.FiltrosEstadisticas
import com.senderoseguro.server.auditoria.rutasEstadisticas
import com.senderoseguro.server.desarrollo.AlertaPrueba
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Estadísticas de alertas para el tablero (P13). */
class EstadisticasTest : PruebaConBaseDatos() {

    private val estadisticas get() = Estadisticas(dataSource)
    private val hoy = LocalDate.now(Estadisticas.ZONA)

    /**
     * Una zona en medio del mar, en un lugar distinto en cada corrida, con cuatro alertas activadas hace 10 minutos:
     * acusada en 60 s y cerrada, acusada en 200 s después de escalar, cancelada a los 30 s sin acuse, y una por SMS
     * que nadie acusó. La zona se borra al final y la alerta abierta se cancela.
     */
    private fun conZona(prueba: (zonaId: Int, alertas: List<UUID>) -> Unit) {
        val lat = Random.nextDouble(-60.0, -50.0)
        val lon = Random.nextDouble(-140.0, -100.0)
        val zona = numero(
            """INSERT INTO zona_geografica (nombre, bbox_min_lat, bbox_min_lon, bbox_max_lat, bbox_max_lon)
               VALUES (?, ?, ?, ?, ?) RETURNING id""",
            "Prueba ${UUID.randomUUID()}", lat - 0.001, lon - 0.001, lat + 0.001, lon + 0.001,
        )
        val alertas = List(4) { AlertasPrueba.crear(dataSource, AlertaPrueba(latitud = lat, longitud = lon)).first }
        val (cerrada, escalada, cancelada, sinAcuse) = alertas
        alertas.forEach { sql("UPDATE alerta SET activada_en = now() - interval '10 minutes' WHERE id = ?", it) }
        sql(
            """UPDATE alerta SET estado = 'CERRADA', acusada_en = activada_en + interval '60 seconds',
                                 cerrada_en = activada_en + interval '5 minutes' WHERE id = ?""",
            cerrada,
        )
        sql("UPDATE alerta SET estado = 'ACUSADA', acusada_en = activada_en + interval '200 seconds', nivel_escalamiento = 1 WHERE id = ?", escalada)
        sql("UPDATE alerta SET estado = 'CANCELADA', cancelada_en = activada_en + interval '30 seconds' WHERE id = ?", cancelada)
        sql("UPDATE alerta SET canal_recepcion = 'SMS', sin_acuse = true WHERE id = ?", sinAcuse)
        try {
            prueba(zona, alertas)
        } finally {
            sql("UPDATE alerta SET estado = 'CANCELADA', cancelada_en = now() WHERE id = ?", sinAcuse)
            sql("DELETE FROM zona_geografica WHERE id = ?", zona)
        }
    }

    @Test
    fun `cuenta, mide el acuse contra la meta y reparte por canal, estado, tipo y semana`() = conZona { zona, _ ->
        val e = estadisticas.alertas(FiltrosEstadisticas(hoy.minusDays(1), hoy, zonaId = zona))

        assertEquals(4, e.totalAlertas)
        assertEquals(130, e.medianaAcuseSeg, "Mediana de 60 s y 200 s")
        // Cuentan la de 60 s (cumple), la de 200 s y la que nunca se acusó; la cancelada a los 30 s no.
        assertEquals(33.3, e.pctCumpleMetaAcuse)
        assertEquals(25.0, e.pctEscaladas)
        assertEquals(25.0, e.pctCanceladas)
        assertEquals(mapOf("INTERNET" to 3, "SMS" to 1), e.porCanal)
        assertEquals(mapOf("ACUSADA" to 1, "CANCELADA" to 1, "CERRADA" to 1, "ENVIADA" to 1), e.porEstado)
        val tipo = e.porTipo.single()
        assertEquals(4, tipo.total)
        assertTrue(tipo.nombre.isNotBlank())
        assertEquals(Estadisticas.semanasDe(hoy.minusDays(1), hoy), e.porSemana.map { it.semana })
        assertEquals(4, e.porSemana.sumOf { it.total })

        val otroTipo = estadisticas.alertas(FiltrosEstadisticas(hoy, hoy, tipoIncidenteId = tipo.tipoIncidenteId + 1000, zonaId = zona))
        assertEquals(0, otroTipo.totalAlertas)
        assertEquals(null, otroTipo.pctCumpleMetaAcuse)
        assertEquals(null, otroTipo.medianaAcuseSeg)
        val antes = estadisticas.alertas(FiltrosEstadisticas(hoy.minusDays(30), hoy.minusDays(2), zonaId = zona))
        assertEquals(0, antes.totalAlertas, "Fuera del periodo")
        assertEquals(HttpStatusCode.BadRequest, assertFailsWith<ApiException> {
            estadisticas.alertas(FiltrosEstadisticas(hoy, hoy, zonaId = 32000))
        }.status)
    }

    @Test
    fun `las semanas son ISO, de lunes a domingo, y salen todas aunque no haya alertas`() {
        assertEquals(listOf("2026-W43", "2026-W44"), Estadisticas.semanasDe(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26)))
        assertEquals(listOf("2026-W53", "2027-W01"), Estadisticas.semanasDe(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun `solo el Administrador, con un periodo valido`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-estadisticas", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasEstadisticas(estadisticas) } }
        }
        val tokens = EmisorTokens("secreto-estadisticas", "sendero-seguro", "sendero-seguro-api")
        suspend fun estado(consulta: String, cuenta: String = "admin", rol: Rol = Rol.ADMINISTRADOR): HttpStatusCode {
            val jwt = tokens.emitir(UsuarioToken(AlertasPrueba.CUENTAS.getValue(cuenta), rol, null, null), 1.hours).first
            return client.get("/api/v1/estadisticas/alertas$consulta") { header("Authorization", "Bearer $jwt") }.status
        }
        assertEquals(HttpStatusCode.OK, estado("?desde=$hoy&hasta=$hoy"))
        assertEquals(HttpStatusCode.Forbidden, estado("?desde=$hoy&hasta=$hoy", "caseta", Rol.OPERADOR))
        assertEquals(HttpStatusCode.BadRequest, estado("?hasta=$hoy"), "Falta desde")
        assertEquals(HttpStatusCode.BadRequest, estado("?desde=$hoy&hasta=${hoy.minusDays(1)}"), "hasta antes de desde")
        assertEquals(HttpStatusCode.BadRequest, estado("?desde=2024-01-01&hasta=$hoy"), "Más de un año")
        assertEquals(HttpStatusCode.BadRequest, estado("?desde=ayer&hasta=$hoy"))
        assertEquals(HttpStatusCode.BadRequest, estado("?desde=$hoy&hasta=$hoy&zonaId=abc"))
    }
}
