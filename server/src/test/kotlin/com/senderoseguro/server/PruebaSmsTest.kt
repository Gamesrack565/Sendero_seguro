package com.senderoseguro.server

import com.senderoseguro.server.avisos.Mensaje
import com.senderoseguro.server.avisos.Proveedor
import com.senderoseguro.server.avisos.ResultadoEnvio
import com.senderoseguro.server.pasarela.rutasPruebaSms
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SMS de prueba en la nube: solo con la llave, y usa el proveedor configurado. */
class PruebaSmsTest {
    private val llave = "x".repeat(32)

    private class ProveedorFalso : Proveedor {
        override val nombre = "falso"
        override val confirmaEntrega = false
        val enviados = mutableListOf<Mensaje>()
        override fun enviar(m: Mensaje): ResultadoEnvio {
            enviados += m
            return ResultadoEnvio.Aceptado("id-1")
        }
    }

    @Test
    fun `sin la llave no manda nada y con ella manda por el proveedor`() {
        val proveedor = ProveedorFalso()
        testApplication {
            application {
                configurarSerializacion()
                configurarErrores()
                routing { rutasPruebaSms(proveedor, llave) }
            }
            for (recibida in listOf(null, "otra")) {
                val r = client.post("/pasarela/prueba-sms") {
                    recibida?.let { header("X-Llave-Prueba", it) }
                    setBody("""{"telefono":"5512345678"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, r.status)
            }
            assertTrue(proveedor.enviados.isEmpty())

            val r = client.post("/pasarela/prueba-sms") {
                header("X-Llave-Prueba", llave)
                setBody("""{"telefono":"5512345678"}""")
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertContains(r.bodyAsText(), "\"aceptado\":true")
            assertEquals(listOf("5512345678"), proveedor.enviados.map { it.destino })
        }
    }
}
