package com.senderoseguro.server

import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.senderoseguro.server.plataforma.rutasSalud
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PlataformaTest {

    private fun ApplicationTestBuilder.montar(baseDatosOk: Boolean) {
        application {
            configurarSerializacion()
            configurarErrores()
            routing { route("/api/v1") { rutasSalud { baseDatosOk } } }
        }
    }

    @Test
    fun `salud responde 200 cuando la base contesta`() = testApplication {
        montar(baseDatosOk = true)
        val respuesta = client.get("/api/v1/salud")
        assertEquals(HttpStatusCode.OK, respuesta.status)
        assertContains(respuesta.bodyAsText(), "\"estado\":\"OK\"")
    }

    @Test
    fun `salud responde 503 cuando la base no contesta`() = testApplication {
        montar(baseDatosOk = false)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/v1/salud").status)
    }

    @Test
    fun `una ruta que no existe usa el formato de error del contrato`() = testApplication {
        montar(baseDatosOk = true)
        val respuesta = client.get("/api/v1/no-existe")
        assertEquals(HttpStatusCode.NotFound, respuesta.status)
        assertContains(respuesta.bodyAsText(), "\"codigo\":\"NO_ENCONTRADO\"")
    }
}
