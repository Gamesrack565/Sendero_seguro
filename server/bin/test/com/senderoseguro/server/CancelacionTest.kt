package com.senderoseguro.server

import com.senderoseguro.server.alertas.RegistroAlertas
import com.senderoseguro.server.alertas.RelevoMalla
import com.senderoseguro.server.cancelacion.CancelacionServicio
import com.senderoseguro.server.cancelacion.rutasCancelacion
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

/** P8 del Módulo 1: cancelar una alerta con PIN, y el PIN que no coincide como posible coacción. */
class CancelacionTest : PruebaConBaseDatos() {

    private val tokens = EmisorTokens("secreto-cancelacion", "sendero-seguro", "sendero-seguro-api")
    private val tokenAna = tokens.emitir(UsuarioToken(AlertasPrueba.ANA, Rol.INTEGRANTE, null, null), 1.hours).first
    private val tokenBeto = tokens.emitir(
        UsuarioToken(UUID.fromString("00000000-0000-4000-8000-000000000002"), Rol.COORDINADOR, null, null), 1.hours,
    ).first

    private fun ApplicationTestBuilder.montar() {
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-cancelacion", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasCancelacion(CancelacionServicio(dataSource, dispersion)) } }
        }
    }

    /** Lo que manda la app (CancelacionDto). */
    private suspend fun HttpClient.cancelar(
        alerta: Any, exitoso: Boolean, hora: Instant = Instant.now(), motivo: String? = "YA_ESTOY_A_SALVO",
        metodo: String = "PIN", token: String = tokenAna,
    ): HttpResponse = post("/api/v1/alertas/$alerta/cancelaciones") {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        val m = motivo?.let { "\"$it\"" } ?: "null"
        setBody("""{"dispositivoId":"00000000-0000-4000-8000-0000000000d1","motivo":$m,"metodo":"$metodo","exitoso":$exitoso,"intentadoEn":"$hora"}""")
    }

    private fun estado(alerta: UUID) = texto("SELECT estado::text FROM alerta WHERE id = ?", alerta)

    @Test
    fun `con el PIN correcto la alerta se cancela y se avisa a la unidad`() = testApplication {
        montar()
        val alerta = alerta()
        dispersion.despacharAhora(alerta)
        assertEquals("ASIGNADA", estado(alerta))

        val hora = Instant.parse("2026-11-10T20:00:00.123Z")
        assertEquals(HttpStatusCode.NoContent, client.cancelar(alerta, exitoso = true, hora = hora).status)
        assertEquals("CANCELADA", estado(alerta))
        assertEquals("YA_ESTOY_A_SALVO", texto("SELECT motivo_cancelacion::text FROM alerta WHERE id = ?", alerta))
        assertEquals(1, numero("SELECT count(*) FROM intento_cancelacion WHERE alerta_id = ? AND exitoso AND NOT es_coaccion", alerta))
        assertEquals(1, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND estado_nuevo = 'CANCELADA'", alerta))
        // La dispersión avisó a los operadores y liberó la unidad.
        assertEquals(true, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'CANCELACION'", alerta) > 0)
        assertEquals(0, numero("SELECT count(*) FROM asignacion WHERE alerta_id = ? AND liberada_en IS NULL", alerta))
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad_id = ? AND accion = 'CANCELAR_ALERTA'", alerta.toString()))

        // La app reintenta desde su cola: el mismo intento no se guarda dos veces.
        assertEquals(HttpStatusCode.NoContent, client.cancelar(alerta, exitoso = true, hora = hora).status)
        assertEquals(1, numero("SELECT count(*) FROM intento_cancelacion WHERE alerta_id = ?", alerta))
        assertEquals(1, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND estado_nuevo = 'CANCELADA'", alerta))
    }

    @Test
    fun `con un PIN que no coincide la alerta sigue y se marca la coaccion`() = testApplication {
        montar()
        val alerta = alerta()
        dispersion.despacharAhora(alerta)

        assertEquals(HttpStatusCode.NoContent, client.cancelar(alerta, exitoso = false, motivo = null).status)
        assertEquals("ASIGNADA", estado(alerta), "La alerta sigue activa")
        assertEquals("true", texto("SELECT coaccion_detectada::text FROM alerta WHERE id = ?", alerta))
        assertEquals(1, numero("SELECT count(*) FROM intento_cancelacion WHERE alerta_id = ? AND es_coaccion AND NOT exitoso", alerta))
        // La consola lo ve: evento sin cambio de estado con la marca de coacción.
        assertEquals(1, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND detalle->>'coaccion' = 'true'", alerta))
        assertEquals(0, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'CANCELACION'", alerta))
        assertEquals(1, numero("SELECT count(*) FROM asignacion WHERE alerta_id = ? AND liberada_en IS NULL", alerta))
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad_id = ? AND accion = 'POSIBLE_COACCION'", alerta.toString()))
    }

    @Test
    fun `si la alerta ya termino el intento se guarda y no cambia nada`() = testApplication {
        montar()
        val alerta = alerta()
        client.cancelar(alerta, exitoso = true)
        val otra = Instant.now().plusMillis(5).minusSeconds(1)
        assertEquals(HttpStatusCode.NoContent, client.cancelar(alerta, exitoso = false, hora = otra, motivo = null).status)
        assertEquals("CANCELADA", estado(alerta))
        assertEquals("false", texto("SELECT coaccion_detectada::text FROM alerta WHERE id = ?", alerta))
        assertEquals(2, numero("SELECT count(*) FROM intento_cancelacion WHERE alerta_id = ?", alerta))
        assertEquals(0, numero("SELECT count(*) FROM intento_cancelacion WHERE alerta_id = ? AND es_coaccion", alerta))
    }

    @Test
    fun `solo quien envio la alerta la cancela, y con datos validos`() = testApplication {
        montar()
        val alerta = alerta()
        assertEquals(HttpStatusCode.NotFound, client.cancelar(alerta, exitoso = true, token = tokenBeto).status)
        assertEquals(HttpStatusCode.NotFound, client.cancelar(UUID.randomUUID(), exitoso = true).status)
        assertEquals(HttpStatusCode.NotFound, client.cancelar("basura", exitoso = true).status)
        assertEquals(HttpStatusCode.BadRequest, client.cancelar(alerta, exitoso = true, metodo = "HUELLA").status)
        assertEquals(HttpStatusCode.BadRequest, client.cancelar(alerta, exitoso = true, motivo = "ME_ABURRI").status)
        assertEquals(HttpStatusCode.Unauthorized, client.cancelar(alerta, exitoso = true, token = "x").status)
        assertEquals("ENVIADA", estado(alerta))
        assertEquals(0, numero("SELECT count(*) FROM intento_cancelacion WHERE alerta_id = ?", alerta))
    }

    @Test
    fun `si la alerta llego por la malla se cancela con el id del telefono`() = testApplication {
        montar()
        // Otro teléfono entregó la alerta de Ana por la malla: en el servidor tiene otro UUID con el mismo id corto.
        val idTelefono = UUID.randomUUID()
        val relevo = RelevoMalla(idTelefono.toString().take(8), "00000001", 19.504507, -99.146911, 40, 3, 2, Instant.now().minusSeconds(30).toString())
        val enServidor = RegistroAlertas(dataSource, dispersion)
            .registrarRelevo(UUID.fromString("00000000-0000-4000-8000-000000000002"), relevo, null).id

        assertEquals(HttpStatusCode.NoContent, client.cancelar(idTelefono, exitoso = true).status)
        assertEquals("CANCELADA", estado(enServidor))
        assertEquals(1, numero("SELECT count(*) FROM intento_cancelacion WHERE alerta_id = ?", enServidor))
        assertEquals(HttpStatusCode.NotFound, client.cancelar(idTelefono, exitoso = true, token = tokenBeto).status, "Sigue siendo solo de Ana")
    }
}

