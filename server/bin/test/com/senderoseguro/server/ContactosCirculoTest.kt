package com.senderoseguro.server

import com.senderoseguro.server.cuentas.CuentasServicio
import com.senderoseguro.server.cuentas.rutasCuentas
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import com.senderoseguro.server.redapoyo.RedApoyoServicio
import com.senderoseguro.server.redapoyo.rutasRedApoyo
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** P2 del Módulo 1: contactos de confianza, círculo escolar y avisos de vinculación. */
class ContactosCirculoTest : PruebaConBaseDatos() {

    private val tokens = EmisorTokens("secreto-red", "sendero-seguro", "sendero-seguro-api")

    private fun ApplicationTestBuilder.montar() {
        val cuentas = CuentasServicio(dataSource, tokens, 30.days)
        val red = RedApoyoServicio(dataSource)
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-red", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad(sesiones = cuentas)
            routing { route("/api/v1") { rutasCuentas(cuentas); rutasRedApoyo(red) } }
        }
    }

    private data class Cuenta(val id: String, val token: String, val telefono: String)

    private fun JsonElement.o() = jsonObject
    private fun JsonObject.texto(clave: String) = this[clave]!!.jsonPrimitive.content
    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText())
    private fun telefonoNuevo() = "55" + Random.nextLong(10_000_000L, 99_999_999L)

    private suspend fun HttpClient.cuenta(tipo: String = "ALUMNO", telefono: String = telefonoNuevo()): Cuenta {
        val r = post("/api/v1/auth/registro") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"nombre":"Prueba Red","telefonoMovil":"$telefono","identificadorInstitucional":"R${telefono.drop(2)}",
                    "tipoComunidad":"$tipo","escuelaId":${numero("SELECT id FROM escuela WHERE clave = 'ESCOM'")},
                    "contrasena":"clave1234","consentimiento":{"avisoId":${numero("SELECT id FROM aviso_privacidad WHERE vigente LIMIT 1")}},
                    "dispositivo":{"id":"${UUID.randomUUID()}","androidApi":34}}""",
            )
        }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val s = r.json().o()
        return Cuenta(s["usuario"]!!.o().texto("id"), s.texto("token"), telefono)
    }

    private suspend fun HttpClient.pedir(metodo: String, ruta: String, c: Cuenta, cuerpo: String? = null): HttpResponse {
        val url = "/api/v1$ruta"
        return when (metodo) {
            "GET" -> get(url) { header("Authorization", "Bearer ${c.token}") }
            "DELETE" -> delete(url) { header("Authorization", "Bearer ${c.token}") }
            "PUT" -> put(url) { header("Authorization", "Bearer ${c.token}"); contentType(ContentType.Application.Json); setBody(cuerpo ?: "{}") }
            else -> post(url) { header("Authorization", "Bearer ${c.token}"); contentType(ContentType.Application.Json); setBody(cuerpo ?: "{}") }
        }
    }

    /** Lo que manda la app (ContactoDto con encodeDefaults). */
    private fun contacto(id: String, nombre: String, telefono: String, prioridad: Int) =
        """{"id":"$id","nombre":"$nombre","telefono":"$telefono","prioridad":$prioridad,"canalPreferido":"SMS","activo":true}"""

    @Test
    fun `contactos se guardan, se editan, se reordenan y se borran`() = testApplication {
        montar()
        val ana = client.cuenta()
        val conCuenta = client.cuenta()
        val (a, b, c) = List(3) { UUID.randomUUID().toString() }

        val r1 = client.pedir("PUT", "/contactos/$a", ana, contacto(a, "Mamá", "+52 55 1234 5678", 1))
        assertEquals(HttpStatusCode.OK, r1.status, r1.bodyAsText())
        val guardado = r1.json().o()
        assertEquals("5512345678", guardado.texto("telefono"))
        assertEquals("SMS", guardado.texto("canalPreferido"))
        assertEquals(null, guardado["usuarioVinculadoId"], "Sin cuenta vinculada no se manda")
        assertEquals("CONFIRMADO", texto("SELECT estado_confirmacion::text FROM contacto_confianza WHERE id = ?::uuid", a))

        // El teléfono es de una cuenta: queda vinculado y el canal pasa a PUSH.
        val vinculado = client.pedir("PUT", "/contactos/$b", ana, contacto(b, "Amiga", conCuenta.telefono, 2)).json().o()
        assertEquals(conCuenta.id, vinculado.texto("usuarioVinculadoId"))
        assertEquals("PUSH", vinculado.texto("canalPreferido"))
        client.pedir("PUT", "/contactos/$c", ana, contacto(c, "Papá", "5587654321", 3))

        // Mandar lo mismo otra vez no duplica (idempotente) y editar cambia el nombre.
        client.pedir("PUT", "/contactos/$a", ana, contacto(a, "Mamá", "5512345678", 1))
        assertEquals("Mi mamá", client.pedir("PUT", "/contactos/$a", ana, contacto(a, "Mi mamá", "5512345678", 1)).json().o().texto("nombre"))
        assertEquals(3, client.pedir("GET", "/contactos", ana).json().jsonArray.size)

        // Errores: número repetido, el propio, datos malos, id distinto.
        val d = UUID.randomUUID().toString()
        val repetido = client.pedir("PUT", "/contactos/$d", ana, contacto(d, "Otra", "5512345678", 4))
        assertEquals(HttpStatusCode.Conflict, repetido.status)
        assertTrue("telefono" in repetido.json().o()["detalle"]!!.o()["errores"]!!.o())
        assertEquals(HttpStatusCode.BadRequest, client.pedir("PUT", "/contactos/$d", ana, contacto(d, "Yo", ana.telefono, 4)).status)
        val malo = client.pedir("PUT", "/contactos/$d", ana, contacto(d, "X", "123", 0)).json().o()
        assertEquals(setOf("nombre", "telefono", "prioridad"), malo["detalle"]!!.o()["errores"]!!.o().keys)
        assertEquals(HttpStatusCode.BadRequest, client.pedir("PUT", "/contactos/$d", ana, contacto(a, "Otra", "5500000000", 4)).status)

        // Reordenar: c, a, b → prioridades 1, 2, 3.
        val orden = client.pedir("PUT", "/contactos/orden", ana, """{"ids":["$c","$a","$b"]}""").json().jsonArray
        assertEquals(listOf(c, a, b), orden.map { it.o().texto("id") })
        assertEquals(listOf("1", "2", "3"), orden.map { it.o().texto("prioridad") })
        assertEquals(HttpStatusCode.BadRequest, client.pedir("PUT", "/contactos/orden", ana, """{"ids":["$c","$a"]}""").status)

        // Otra persona no ve ni puede tocar los contactos de Ana.
        assertEquals(0, client.pedir("GET", "/contactos", conCuenta).json().jsonArray.size)
        assertEquals(HttpStatusCode.NotFound, client.pedir("PUT", "/contactos/$a", conCuenta, contacto(a, "Robado", "5511112222", 1)).status)
        assertEquals(HttpStatusCode.NoContent, client.pedir("DELETE", "/contactos/$a", conCuenta).status)
        assertEquals(3, client.pedir("GET", "/contactos", ana).json().jsonArray.size)

        // Borrar es idempotente; y el número se puede volver a agregar con otro id.
        assertEquals(HttpStatusCode.NoContent, client.pedir("DELETE", "/contactos/$a", ana).status)
        assertEquals(HttpStatusCode.NoContent, client.pedir("DELETE", "/contactos/$a", ana).status)
        assertEquals(2, client.pedir("GET", "/contactos", ana).json().jsonArray.size)
        assertEquals(HttpStatusCode.OK, client.pedir("PUT", "/contactos/$d", ana, contacto(d, "Mamá", "5512345678", 3)).status)
        assertEquals(numero("SELECT count(*) FROM bitacora WHERE usuario_id = ?::uuid AND accion = 'ELIMINAR_CONTACTO'", ana.id), 1)
    }

    @Test
    fun `un contacto que ya recibio avisos se desactiva en lugar de borrarse`() = testApplication {
        montar()
        val ana = client.cuenta()
        val id = UUID.randomUUID().toString()
        client.pedir("PUT", "/contactos/$id", ana, contacto(id, "Mamá", "5522223333", 1))
        assertEquals(HttpStatusCode.Accepted, client.pedir("POST", "/red-apoyo/avisos", ana, """{"tipo":"CONTACTO_AGREGADO","contactoId":"$id"}""").status)
        client.pedir("DELETE", "/contactos/$id", ana)
        assertEquals("false", texto("SELECT activo::text FROM contacto_confianza WHERE id = ?::uuid", id))
        assertEquals(0, client.pedir("GET", "/contactos", ana).json().jsonArray.size)
        // El mismo número con otro id ya no se puede (el registro viejo lo usa un aviso): 409.
        val otro = UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.Conflict, client.pedir("PUT", "/contactos/$otro", ana, contacto(otro, "Mamá", "5522223333", 1)).status)
        // Con el mismo id sí: se reactiva.
        assertEquals(HttpStatusCode.OK, client.pedir("PUT", "/contactos/$id", ana, contacto(id, "Mamá", "5522223333", 1)).status)
        assertEquals(1, client.pedir("GET", "/contactos", ana).json().jsonArray.size)
    }

    @Test
    fun `el aviso CONTACTO_AGREGADO crea un SMS para el contacto, una sola vez`() = testApplication {
        montar()
        val ana = client.cuenta()
        val id = UUID.randomUUID().toString()
        client.pedir("PUT", "/contactos/$id", ana, contacto(id, "Mamá", "5533334444", 1))
        val aviso = """{"tipo":"CONTACTO_AGREGADO","contactoId":"$id"}"""
        client.pedir("POST", "/red-apoyo/avisos", ana, aviso)
        client.pedir("POST", "/red-apoyo/avisos", ana, aviso)
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE contacto_id = ?::uuid AND tipo = 'CONTACTO_AGREGADO' AND canal = 'SMS'", id))

        val otra = client.cuenta()
        assertEquals(HttpStatusCode.NotFound, client.pedir("POST", "/red-apoyo/avisos", otra, aviso).status)
        assertEquals(HttpStatusCode.BadRequest, client.pedir("POST", "/red-apoyo/avisos", ana, """{"tipo":"OTRO"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.pedir("POST", "/red-apoyo/avisos", ana, """{"tipo":"CONTACTO_AGREGADO"}""").status)
    }

    @Test
    fun `circulo completo - crear, unirse, ver integrantes, avisar, codigo nuevo, baja y retiro`() = testApplication {
        montar()
        val profe = client.cuenta("DOCENTE")
        val alumna = client.cuenta()
        val alumno = client.cuenta()
        val circuloId = UUID.randomUUID().toString()

        assertEquals(JsonNull, client.pedir("GET", "/circulos/mio", profe).json().o()["circulo"] ?: JsonNull)

        // Solo un coordinador crea.
        assertEquals(HttpStatusCode.Forbidden, client.pedir("POST", "/circulos", alumna, """{"id":"${UUID.randomUUID()}","nombreGrupo":"Grupo 5CV1"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.pedir("POST", "/circulos", profe, """{"id":"$circuloId","nombreGrupo":"x"}""").status)
        val creado = client.pedir("POST", "/circulos", profe, """{"id":"$circuloId","nombreGrupo":"Grupo 5CV1"}""")
        assertEquals(HttpStatusCode.Created, creado.status, creado.bodyAsText())
        val circulo = creado.json().o()
        val codigo = circulo.texto("codigoInvitacion")
        assertTrue(Regex("^[A-Z0-9]{3}-[A-Z0-9]{3}$").matches(codigo), codigo)
        assertEquals("VIGENTE", circulo.texto("estadoCodigo"))
        assertEquals(profe.id, circulo.texto("coordinadorId"))
        // Reintento con el mismo id: el mismo círculo, no otro.
        assertEquals(codigo, client.pedir("POST", "/circulos", profe, """{"id":"$circuloId","nombreGrupo":"Grupo 5CV1"}""").json().o().texto("codigoInvitacion"))
        assertEquals(HttpStatusCode.Conflict, client.pedir("POST", "/circulos", profe, """{"id":"${UUID.randomUUID()}","nombreGrupo":"Otro grupo"}""").status)

        // Unirse con el código escrito como sea (minúsculas, sin guion).
        assertEquals(HttpStatusCode.BadRequest, client.pedir("POST", "/circulos/unirse", alumna, """{"codigo":"12"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.pedir("POST", "/circulos/unirse", alumna, """{"codigo":"ZZZ-999"}""").status)
        val unida = client.pedir("POST", "/circulos/unirse", alumna, """{"codigo":"${codigo.lowercase().replace("-", "")}"}""").json().o()
        assertEquals(circuloId, unida.texto("id"))
        assertEquals(null, unida["codigoInvitacion"], "El código solo lo ve el coordinador")
        client.pedir("POST", "/circulos/unirse", alumno, """{"codigo":"$codigo"}""")
        assertEquals(circuloId, client.pedir("GET", "/circulos/mio", alumna).json().o()["circulo"]!!.o().texto("id"))

        // Aviso al coordinador, una vez.
        val aviso = """{"tipo":"NUEVO_INTEGRANTE","circuloId":"$circuloId"}"""
        assertEquals(HttpStatusCode.Accepted, client.pedir("POST", "/red-apoyo/avisos", alumna, aviso).status)
        client.pedir("POST", "/red-apoyo/avisos", alumna, aviso)
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE usuario_id = ?::uuid AND tipo = 'NUEVO_INTEGRANTE'", profe.id))

        // Integrantes: los tres activos. Alguien de fuera no los ve.
        val miembros = client.pedir("GET", "/circulos/$circuloId/miembros", alumna).json().jsonArray
        assertEquals(setOf(profe.id, alumna.id, alumno.id), miembros.map { it.o().texto("usuarioId") }.toSet())
        assertTrue(miembros.all { it.o().texto("estado") == "ACTIVA" })
        val fuera = client.cuenta()
        assertEquals(HttpStatusCode.NotFound, client.pedir("GET", "/circulos/$circuloId/miembros", fuera).status)

        // Código nuevo: solo el coordinador; el viejo deja de servir.
        assertEquals(HttpStatusCode.Forbidden, client.pedir("POST", "/circulos/$circuloId/codigo", alumna).status)
        val nuevo = client.pedir("POST", "/circulos/$circuloId/codigo", profe).json().o().texto("codigoInvitacion")
        assertNotEquals(codigo, nuevo)
        assertEquals(HttpStatusCode.NotFound, client.pedir("POST", "/circulos/unirse", fuera, """{"codigo":"$codigo"}""").status)

        // Código vencido por fecha: 410 CODIGO_VENCIDO.
        sql("UPDATE circulo SET codigo_vence_en = now() - interval '1 minute' WHERE id = ?::uuid", circuloId)
        val vencido = client.pedir("POST", "/circulos/unirse", fuera, """{"codigo":"$nuevo"}""")
        assertEquals(HttpStatusCode.Gone, vencido.status)
        assertEquals("CODIGO_VENCIDO", vencido.json().o().texto("codigo"))
        assertEquals("VENCIDO", client.pedir("GET", "/circulos/mio", profe).json().o()["circulo"]!!.o().texto("estadoCodigo"))

        // Baja por el coordinador, retiro voluntario.
        assertEquals(HttpStatusCode.Forbidden, client.pedir("DELETE", "/circulos/$circuloId/miembros/${alumno.id}", alumna).status)
        assertEquals(HttpStatusCode.Conflict, client.pedir("DELETE", "/circulos/$circuloId/miembros/${profe.id}", profe).status)
        assertEquals(HttpStatusCode.NoContent, client.pedir("DELETE", "/circulos/$circuloId/miembros/${alumno.id}", profe).status)
        assertEquals(HttpStatusCode.NoContent, client.pedir("POST", "/circulos/$circuloId/retirarme", alumna).status)
        assertEquals(HttpStatusCode.Conflict, client.pedir("POST", "/circulos/$circuloId/retirarme", profe).status)
        val estados = client.pedir("GET", "/circulos/$circuloId/miembros", profe).json().jsonArray
            .associate { it.o().texto("usuarioId") to it.o().texto("estado") }
        assertEquals(mapOf(profe.id to "ACTIVA", alumna.id to "RETIRADA", alumno.id to "DADA_DE_BAJA"), estados)
        assertEquals(JsonNull, client.pedir("GET", "/circulos/mio", alumna).json().o()["circulo"] ?: JsonNull)
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE usuario_id = ?::uuid AND accion = 'DAR_DE_BAJA_INTEGRANTE'", profe.id))
    }

    @Test
    fun `nadie esta en dos circulos a la vez`() = testApplication {
        montar()
        val profe1 = client.cuenta("DOCENTE")
        val profe2 = client.cuenta("DOCENTE")
        val alumna = client.cuenta()
        val c1 = client.pedir("POST", "/circulos", profe1, """{"id":"${UUID.randomUUID()}","nombreGrupo":"Grupo A"}""").json().o()
        val c2 = client.pedir("POST", "/circulos", profe2, """{"id":"${UUID.randomUUID()}","nombreGrupo":"Grupo B"}""").json().o()
        assertEquals(HttpStatusCode.OK, client.pedir("POST", "/circulos/unirse", alumna, """{"codigo":"${c1.texto("codigoInvitacion")}"}""").status)
        // Unirse otra vez al mismo responde el mismo círculo.
        assertEquals(HttpStatusCode.OK, client.pedir("POST", "/circulos/unirse", alumna, """{"codigo":"${c1.texto("codigoInvitacion")}"}""").status)
        assertEquals(HttpStatusCode.Conflict, client.pedir("POST", "/circulos/unirse", alumna, """{"codigo":"${c2.texto("codigoInvitacion")}"}""").status)
        client.pedir("POST", "/circulos/${c1.texto("id")}/retirarme", alumna)
        assertEquals(c2.texto("id"), client.pedir("POST", "/circulos/unirse", alumna, """{"codigo":"${c2.texto("codigoInvitacion")}"}""").json().o().texto("id"))
    }

    @Test
    fun `sin token no se entra`() = testApplication {
        montar()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/contactos").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/circulos/mio").status)
    }
}
