package com.senderoseguro.server

import com.senderoseguro.server.cuentas.Contrasenas
import com.senderoseguro.server.cuentas.CuentasServicio
import com.senderoseguro.server.cuentas.rutasCuentas
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** P1 del Módulo 1: catálogos, registro, inicio de sesión con bloqueo, cierre de sesión, perfil y baja. */
class CuentasTest : PruebaConBaseDatos() {

    private val tokens = EmisorTokens("secreto-cuentas", "sendero-seguro", "sendero-seguro-api")

    private fun ApplicationTestBuilder.montar() {
        val cuentas = CuentasServicio(dataSource, tokens, 30.days)
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-cuentas", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad(sesiones = cuentas)
            routing { route("/api/v1") { rutasCuentas(cuentas) } }
        }
    }

    private fun json(r: String): JsonObject = Json.parseToJsonElement(r).jsonObject
    private fun JsonObject.texto(clave: String) = this[clave]!!.jsonPrimitive.content
    private suspend fun HttpResponse.cuerpo() = json(bodyAsText())

    /** Teléfono y matrícula que no existen todavía. */
    private fun datosNuevos(): Pair<String, String> {
        val n = Random.nextLong(10_000_000L, 99_999_999L)
        return "55$n" to "T$n"
    }

    private fun registro(telefono: String, matricula: String, tipo: String = "ALUMNO", contrasena: String = "clave1234") = """
        {"nombre":"Prueba Cuentas","telefonoMovil":"+52 ${telefono.take(2)} ${telefono.drop(2)}","identificadorInstitucional":"$matricula",
         "tipoComunidad":"$tipo","escuelaId":${numero("SELECT id FROM escuela WHERE clave = 'ESCOM'")},
         "carreraAdscripcion":"Ingeniería en Sistemas","contrasena":"$contrasena","aceptaEntrenamientoIa":true,
         "consentimiento":{"avisoId":${numero("SELECT id FROM aviso_privacidad WHERE vigente LIMIT 1")},"aceptadoEn":"2026-11-10T10:00:00Z"},
         "dispositivo":{"id":"${UUID.randomUUID()}","modelo":"Pixel 7","androidApi":34,"soportaWifiAware":true}}
    """.trimIndent()

    private suspend fun HttpClient.registrar(telefono: String, matricula: String, tipo: String = "ALUMNO"): JsonObject {
        val r = post("/api/v1/auth/registro") { contentType(ContentType.Application.Json); setBody(registro(telefono, matricula, tipo)) }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return r.cuerpo()
    }

    private suspend fun HttpClient.login(usuario: String, contrasena: String, dispositivo: String? = UUID.randomUUID().toString()) =
        post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            val disp = dispositivo?.let { ""","dispositivo":{"id":"$it","modelo":"Pixel","androidApi":33}""" } ?: ""
            setBody("""{"usuario":"$usuario","contrasena":"$contrasena"$disp}""")
        }

    private suspend fun HttpClient.yo(token: String) = get("/api/v1/usuarios/me") { header("Authorization", "Bearer $token") }

    @Test
    fun `los catalogos del registro responden sin token`() = testApplication {
        montar()
        val aviso = client.get("/api/v1/avisos-privacidad/vigente").cuerpo()
        assertEquals(64, aviso.texto("contenidoHash").length)
        val instituciones = Json.parseToJsonElement(client.get("/api/v1/instituciones").bodyAsText()).jsonArray
        val ipn = instituciones.map { it.jsonObject }.first { it.texto("siglas") == "IPN" }
        val escuelas = Json.parseToJsonElement(client.get("/api/v1/instituciones/${ipn.texto("id")}/escuelas").bodyAsText()).jsonArray
        assertTrue(escuelas.any { it.jsonObject.texto("nombre").contains("Cómputo") }, escuelas.toString())
        assertEquals(ipn.texto("id"), escuelas.first().jsonObject.texto("institucionId"))
    }

    @Test
    fun `el registro crea la cuenta, guarda todo y deja la sesion abierta`() = testApplication {
        montar()
        val (telefono, matricula) = datosNuevos()
        val sesion = client.registrar(telefono, matricula)
        val usuario = sesion["usuario"]!!.jsonObject
        assertEquals(telefono, usuario.texto("telefonoMovil"), "el teléfono se guarda con 10 dígitos")
        assertEquals("INTEGRANTE", usuario.texto("rol"))
        assertEquals("ACTIVA", usuario.texto("estado"))
        val id = usuario.texto("id")

        val hash = texto("SELECT password_hash FROM usuario WHERE id = ?::uuid", id)!!
        assertTrue(hash.startsWith("\$argon2id\$"), "la contraseña se guarda con Argon2id")
        assertTrue(Contrasenas.verificar("clave1234", hash))
        assertEquals(1, numero("SELECT count(*) FROM consentimiento WHERE usuario_id = ?::uuid", id))
        assertEquals(1, numero("SELECT count(*) FROM dispositivo WHERE usuario_id = ?::uuid AND soporta_wifi_aware", id))
        assertEquals(1, numero("SELECT count(*) FROM sesion WHERE usuario_id = ?::uuid AND length(token_hash) = 64", id))
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE usuario_id = ?::uuid AND accion = 'REGISTRO_CUENTA'", id))

        val perfil = client.yo(sesion.texto("token"))
        assertEquals(HttpStatusCode.OK, perfil.status)
        assertEquals("Prueba Cuentas", perfil.cuerpo().texto("nombre"))
    }

    @Test
    fun `un docente queda como coordinador`() = testApplication {
        montar()
        val (telefono, matricula) = datosNuevos()
        assertEquals("COORDINADOR", client.registrar(telefono, matricula, "DOCENTE")["usuario"]!!.jsonObject.texto("rol"))
    }

    @Test
    fun `el registro rechaza datos invalidos y duplicados con el campo que fallo`() = testApplication {
        montar()
        val (telefono, matricula) = datosNuevos()
        val malo = registro("123", "ab", contrasena = "corta").replace("Prueba Cuentas", "R2")
        val r = client.post("/api/v1/auth/registro") { contentType(ContentType.Application.Json); setBody(malo) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val cuerpo = r.cuerpo()
        assertEquals("VALIDACION", cuerpo.texto("codigo"))
        val errores = cuerpo["detalle"]!!.jsonObject["errores"]!!.jsonObject
        assertEquals(setOf("nombre", "telefono", "identificador", "contrasena"), errores.keys)

        client.registrar(telefono, matricula)
        val mismoTelefono = client.post("/api/v1/auth/registro") {
            contentType(ContentType.Application.Json); setBody(registro(telefono, datosNuevos().second))
        }
        assertEquals(HttpStatusCode.Conflict, mismoTelefono.status)
        assertEquals("DUPLICADO", mismoTelefono.cuerpo().texto("codigo"))
        assertNotNull(mismoTelefono.cuerpo()["detalle"]!!.jsonObject["errores"]!!.jsonObject["telefono"])

        val mismaMatricula = client.post("/api/v1/auth/registro") {
            contentType(ContentType.Application.Json); setBody(registro(datosNuevos().first, matricula.lowercase()))
        }
        assertEquals(HttpStatusCode.Conflict, mismaMatricula.status)
        assertNotNull(mismaMatricula.cuerpo()["detalle"]!!.jsonObject["errores"]!!.jsonObject["identificador"])
    }

    @Test
    fun `se inicia sesion con telefono o matricula, y las cuentas de prueba con bcrypt tambien`() = testApplication {
        montar()
        val (telefono, matricula) = datosNuevos()
        client.registrar(telefono, matricula)
        assertEquals(HttpStatusCode.OK, client.login(telefono, "clave1234").status)
        assertEquals(HttpStatusCode.OK, client.login("+52 (${telefono.take(2)}) ${telefono.drop(2)}", "clave1234").status)
        assertEquals(HttpStatusCode.OK, client.login(matricula.lowercase(), "clave1234").status)

        // Ana es de los datos de prueba: su contraseña está en bcrypt. Entra con su propio teléfono registrado.
        val ana = client.login("5510000001", "sendero123", dispositivo = "00000000-0000-4000-8000-0000000000d1")
        assertEquals(HttpStatusCode.OK, ana.status, ana.bodyAsText())
        assertNotNull(texto("SELECT fcm_token FROM dispositivo WHERE id = '00000000-0000-4000-8000-0000000000d1'"),
            "el mismo dueño conserva su token de push")
    }

    @Test
    fun `el administrador no entra por la app pero si por la consola, y el operador por las dos`() = testApplication {
        montar()
        val app = client.login("5510000003", "sendero123")
        assertEquals(HttpStatusCode.Forbidden, app.status)
        val cuerpo = app.cuerpo()
        assertEquals("PERFIL_NO_PERMITIDO", cuerpo.texto("codigo"))
        assertEquals("ADMINISTRADOR", cuerpo["detalle"]!!.jsonObject.texto("rol"))

        val consola = client.login("5510000003", "sendero123", dispositivo = null)
        assertEquals(HttpStatusCode.OK, consola.status)
        assertEquals("ADMINISTRADOR", consola.cuerpo()["usuario"]!!.jsonObject.texto("rol"))

        // Modo operador (A-14): el operador en campo usa la app para recibir alertas por la malla.
        val operadorApp = client.login("5510000011", "sendero123")
        assertEquals(HttpStatusCode.OK, operadorApp.status, operadorApp.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.login("5510000011", "sendero123", dispositivo = null).status)
    }

    @Test
    fun `al quinto intento fallido la cuenta se bloquea y al vencer el bloqueo vuelve a entrar`() = testApplication {
        montar()
        val (telefono, matricula) = datosNuevos()
        val id = client.registrar(telefono, matricula)["usuario"]!!.jsonObject.texto("id")

        for (restantes in 4 downTo 1) {
            val r = client.login(telefono, "otra-clave1")
            assertEquals(HttpStatusCode.Unauthorized, r.status)
            val cuerpo = r.cuerpo()
            assertEquals("CREDENCIALES_INVALIDAS", cuerpo.texto("codigo"))
            assertEquals(restantes, cuerpo["detalle"]!!.jsonObject["intentosRestantes"]!!.jsonPrimitive.int)
        }
        val quinto = client.login(telefono, "otra-clave1")
        assertEquals(HttpStatusCode.Locked, quinto.status)
        assertEquals("CUENTA_BLOQUEADA", quinto.cuerpo().texto("codigo"))
        assertNotNull(quinto.cuerpo()["detalle"]!!.jsonObject["bloqueadaHasta"])
        assertEquals("BLOQUEADA_TEMPORAL", texto("SELECT estado::text FROM usuario WHERE id = ?::uuid", id))
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE usuario_id = ?::uuid AND accion = 'BLOQUEO'", id))

        assertEquals(HttpStatusCode.Locked, client.login(telefono, "clave1234").status, "bloqueada aunque la contraseña sea correcta")

        sql("UPDATE usuario SET bloqueada_hasta = now() - interval '1 minute' WHERE id = ?::uuid", id)
        assertEquals(HttpStatusCode.OK, client.login(telefono, "clave1234").status)
        assertEquals(0, numero("SELECT intentos_fallidos FROM usuario WHERE id = ?::uuid", id))
        assertEquals("ACTIVA", texto("SELECT estado::text FROM usuario WHERE id = ?::uuid", id))
    }

    @Test
    fun `una cuenta que no existe responde igual que una contrasena incorrecta`() = testApplication {
        montar()
        val r = client.login("5599999999", "clave1234")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals("CREDENCIALES_INVALIDAS", r.cuerpo().texto("codigo"))
    }

    @Test
    fun `al cerrar sesion el token deja de servir y las otras sesiones siguen`() = testApplication {
        montar()
        val (telefono, matricula) = datosNuevos()
        val primera = client.registrar(telefono, matricula).texto("token")
        val segunda = client.login(telefono, "clave1234").cuerpo().texto("token")

        val salida = client.post("/api/v1/auth/logout") { header("Authorization", "Bearer $primera") }
        assertEquals(HttpStatusCode.NoContent, salida.status)
        val despues = client.yo(primera)
        assertEquals(HttpStatusCode.Unauthorized, despues.status)
        assertEquals("NO_AUTENTICADO", despues.cuerpo().texto("codigo"))
        assertEquals(HttpStatusCode.OK, client.yo(segunda).status)
    }

    @Test
    fun `el perfil se cambia y la baja cierra todas las sesiones`() = testApplication {
        montar()
        val (telefono, matricula) = datosNuevos()
        val token = client.registrar(telefono, matricula).texto("token")
        val (otroTelefono, _) = datosNuevos()

        val cambio = client.patch("/api/v1/usuarios/me") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json)
            setBody("""{"telefonoMovil":"$otroTelefono","aceptaEntrenamientoIa":false}""")
        }
        assertEquals(HttpStatusCode.OK, cambio.status, cambio.bodyAsText())
        val perfil = cambio.cuerpo()
        assertEquals(otroTelefono, perfil.texto("telefonoMovil"))
        assertEquals("false", perfil.texto("aceptaEntrenamientoIa"))
        assertEquals(matricula, perfil.texto("identificadorInstitucional"), "lo que no se manda no cambia")

        val malaClave = client.post("/api/v1/usuarios/me/baja") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody("""{"contrasena":"no-es1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, malaClave.status)

        val baja = client.post("/api/v1/usuarios/me/baja") {
            header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody("""{"contrasena":"clave1234"}""")
        }
        assertEquals(HttpStatusCode.NoContent, baja.status)
        assertEquals(HttpStatusCode.Unauthorized, client.yo(token).status)
        val login = client.login(otroTelefono, "clave1234")
        assertEquals(HttpStatusCode.Forbidden, login.status)
        assertEquals("CUENTA_SUSPENDIDA", login.cuerpo().texto("codigo"))
        assertFalse(numero("SELECT count(*) FROM sesion s JOIN usuario u ON u.id = s.usuario_id WHERE u.telefono_movil = ? AND s.revocada_en IS NULL", otroTelefono) > 0)
        assertEquals(1, numero("SELECT count(*) FROM solicitud_arco a JOIN usuario u ON u.id = a.usuario_id WHERE u.telefono_movil = ? AND a.tipo = 'CANCELACION'", otroTelefono))
    }
}
