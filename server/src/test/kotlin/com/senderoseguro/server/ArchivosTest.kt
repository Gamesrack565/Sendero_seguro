package com.senderoseguro.server

import com.senderoseguro.server.archivos.AlmacenAzureBlob
import com.senderoseguro.server.archivos.AlmacenCarpeta
import com.senderoseguro.server.archivos.LimitesArchivos
import com.senderoseguro.server.archivos.rutasArchivos
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Subida y descarga de archivos (evidencia de reportes y mapas offline) y el almacén de Azure Blob. */
class ArchivosTest {

    private val tokens = EmisorTokens("secreto-archivos", "sendero-seguro", "sendero-seguro-api")
    private val carpeta = Files.createTempDirectory("sendero-archivos-prueba")

    private fun jwt(rol: Rol) = tokens.emitir(UsuarioToken(UUID.randomUUID(), rol, null, null), 1.hours).first

    private fun ApplicationTestBuilder.montar() {
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-archivos", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasArchivos(AlmacenCarpeta(carpeta), LimitesArchivos(evidenciaBytes = 1024, mapaBytes = 4096)) } }
        }
    }

    @Test
    fun `una foto se sube, se descarga igual y trae su tamano y SHA-256`() = testApplication {
        montar()
        val foto = ByteArray(700) { (it % 251).toByte() }
        val r = client.post("/api/v1/archivos/evidencia") {
            header("Authorization", "Bearer ${jwt(Rol.INTEGRANTE)}")
            contentType(ContentType.Image.JPEG)
            setBody(foto)
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val cuerpo = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        val clave = cuerpo["clave"]!!.jsonPrimitive.content
        assertTrue(Regex("^evidencia/[0-9a-f-]{36}\\.jpg$").matches(clave), clave)
        assertEquals("/archivos/$clave", cuerpo["url"]!!.jsonPrimitive.content)
        assertEquals(700, cuerpo["tamanoBytes"]!!.jsonPrimitive.long)
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(foto)), cuerpo["hashSha256"]!!.jsonPrimitive.content)

        val d = client.get("/api/v1/archivos/$clave") { header("Authorization", "Bearer ${jwt(Rol.OPERADOR)}") }
        assertEquals(HttpStatusCode.OK, d.status)
        assertEquals("image/jpeg", d.headers[HttpHeaders.ContentType])
        assertContentEquals(foto, d.bodyAsBytes())
    }

    @Test
    fun `se rechaza sin sesion, con un tipo no permitido, vacio, demasiado grande o un mapa que no sube el Administrador`() = testApplication {
        montar()
        suspend fun subir(ruta: String, rol: Rol?, tipo: ContentType, bytes: ByteArray) = client.post("/api/v1/archivos/$ruta") {
            rol?.let { header("Authorization", "Bearer ${jwt(it)}") }
            contentType(tipo)
            setBody(bytes)
        }.status
        assertEquals(HttpStatusCode.Unauthorized, subir("evidencia", null, ContentType.Image.PNG, ByteArray(10)))
        assertEquals(HttpStatusCode.UnsupportedMediaType, subir("evidencia", Rol.INTEGRANTE, ContentType.Application.Pdf, ByteArray(10)))
        assertEquals(HttpStatusCode.BadRequest, subir("evidencia", Rol.INTEGRANTE, ContentType.Image.PNG, ByteArray(0)))
        assertEquals(HttpStatusCode.PayloadTooLarge, subir("evidencia", Rol.INTEGRANTE, ContentType.Image.PNG, ByteArray(1025)))
        assertEquals(HttpStatusCode.Forbidden, subir("mapa", Rol.INTEGRANTE, ContentType.Application.Zip, ByteArray(10)))
        assertEquals(HttpStatusCode.Created, subir("mapa", Rol.ADMINISTRADOR, ContentType.Application.Zip, ByteArray(4096)))
        assertEquals(HttpStatusCode.NotFound, subir("otro", Rol.ADMINISTRADOR, ContentType.Application.Zip, ByteArray(10)))
        // Nada de lo rechazado quedó guardado: solo el mapa.
        assertEquals(1, Files.walk(carpeta).filter { Files.isRegularFile(it) }.count())
    }

    @Test
    fun `descargar un archivo que no existe o un nombre raro responde 404`() = testApplication {
        montar()
        val jwt = jwt(Rol.INTEGRANTE)
        for (ruta in listOf("evidencia/${UUID.randomUUID()}.jpg", "evidencia/..%2F..%2Fsecreto.jpg", "evidencia/${UUID.randomUUID()}.exe")) {
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/archivos/$ruta") { header("Authorization", "Bearer $jwt") }.status, ruta)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/archivos/evidencia/${UUID.randomUUID()}.jpg").status)
    }

    @Test
    fun `Azure Blob guarda con Put Blob y el SAS, y lee lo guardado`() {
        val guardados = ConcurrentHashMap<String, Pair<String, ByteArray>>()
        val pedidas = mutableListOf<String>()
        val servidor = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        servidor.createContext("/") { ex ->
            pedidas += "${ex.requestMethod} ${ex.requestURI}"
            val ruta = ex.requestURI.path
            when {
                ex.requestURI.query != "sv=2023&sig=firma" -> ex.sendResponseHeaders(403, -1)
                ex.requestMethod == "PUT" && ex.requestHeaders.getFirst("x-ms-blob-type") == "BlockBlob" -> {
                    guardados[ruta] = ex.requestHeaders.getFirst("x-ms-blob-content-type") to ex.requestBody.readAllBytes()
                    ex.sendResponseHeaders(201, -1)
                }
                ex.requestMethod == "GET" && guardados.containsKey(ruta) -> {
                    val bytes = guardados.getValue(ruta).second
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                else -> ex.sendResponseHeaders(404, -1)
            }
            ex.close()
        }
        servidor.start()
        try {
            val almacen = AlmacenAzureBlob("http://127.0.0.1:${servidor.address.port}/archivos/", "?sv=2023&sig=firma")
            val archivo = Files.createTempFile("blob", ".png")
            Files.write(archivo, byteArrayOf(1, 2, 3, 4))
            almacen.guardar("evidencia/abc.png", "image/png", archivo)
            assertEquals("image/png", guardados.getValue("/archivos/evidencia/abc.png").first)

            val leido = almacen.abrir("evidencia/abc.png")!!
            assertEquals(4, leido.tamanoBytes)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), leido.contenido.use { it.readAllBytes() })
            assertNull(almacen.abrir("evidencia/no-existe.png"))
            assertEquals("PUT /archivos/evidencia/abc.png?sv=2023&sig=firma", pedidas.first())
        } finally {
            servidor.stop(0)
        }
    }
}
