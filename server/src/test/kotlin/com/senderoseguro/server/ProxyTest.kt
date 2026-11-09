package com.senderoseguro.server

import com.senderoseguro.server.plataforma.ipCliente
import com.senderoseguro.server.plataforma.ipReenviada
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** IP real de quien llama cuando el servidor está detrás del balanceador de Azure. */
class ProxyTest {

    @Test
    fun `toma la ultima IP de X-Forwarded-For y le quita el puerto`() {
        assertEquals("5.6.7.8", ipReenviada("1.2.3.4, 5.6.7.8:51000"))
        assertEquals("5.6.7.8", ipReenviada("5.6.7.8"))
        assertEquals("2001:db8::1", ipReenviada("[2001:db8::1]:443"))
        assertEquals("2001:db8::1", ipReenviada("2001:db8::1"))
        assertEquals(null, ipReenviada("1.2.3.4, "))
    }

    @Test
    fun `solo confia en el encabezado si esta detras del proxy`() {
        for ((detras, esperada) in listOf("true" to "5.6.7.8", "false" to "localhost")) {
            testApplication {
                environment { config = MapApplicationConfig("servidor.detrasDeProxy" to detras) }
                application { routing { get("/ip") { call.respondText(call.ipCliente().orEmpty()) } } }
                val ip = client.get("/ip") { header("X-Forwarded-For", "9.9.9.9, 5.6.7.8:51000") }.bodyAsText()
                assertEquals(esperada, ip, "detrasDeProxy=$detras")
            }
        }
    }
}
