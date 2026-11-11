package com.senderoseguro.server

import com.senderoseguro.server.administracion.AdministracionServicio
import com.senderoseguro.server.administracion.CambioEstadoCuenta
import com.senderoseguro.server.administracion.CambioPerfil
import com.senderoseguro.server.administracion.CorporacionCambio
import com.senderoseguro.server.administracion.CorporacionEntrada
import com.senderoseguro.server.administracion.OperadoresEntrada
import com.senderoseguro.server.administracion.TurnoCambio
import com.senderoseguro.server.administracion.UnidadCambio
import com.senderoseguro.server.administracion.UnidadEntrada
import com.senderoseguro.server.administracion.rutasAdministracion
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
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Usuarios y perfiles (CU-07), corporaciones, unidades y operadores (CU-10) y turno del operador. */
class AdministracionTest : PruebaConBaseDatos() {

    private val admin = UsuarioToken(AlertasPrueba.CUENTAS.getValue("admin"), Rol.ADMINISTRADOR, null, null)
    private val servicio by lazy { AdministracionServicio(dataSource) }
    private val motivo = "Alta como operador de la caseta"

    private fun codigoDe(bloque: () -> Unit): String = assertFailsWith<ApiException> { bloque() }.codigo

    /** Una cuenta nueva con una sesión abierta. */
    private fun cuentaNueva(nombre: String = "Persona Admin Prueba"): UUID {
        val id = UUID.randomUUID()
        val n = Random.nextLong(1_000_000_000L, 9_999_999_999L).toString()
        sql(
            """INSERT INTO usuario (id, nombre, telefono_movil, identificador_institucional, tipo_comunidad, escuela_id, password_hash)
               VALUES (?, ?, ?, ?, 'PERSONAL', (SELECT id FROM escuela WHERE clave = 'ESCOM'), 'x')""",
            id, nombre, n, "ADM$n",
        )
        sql("INSERT INTO sesion (usuario_id, token_hash, expira_en) VALUES (?, ?, now() + interval '1 day')", id, UUID.randomUUID().toString().replace("-", "").padEnd(64, '0'))
        return id
    }

    private fun sesionesAbiertas(id: UUID) = numero("SELECT count(*) FROM sesion WHERE usuario_id = ? AND revocada_en IS NULL", id)

    /** Una corporación propia de la prueba, para que sus unidades no compitan con las de los datos de prueba. */
    private fun corporacion(): Int {
        val sufijo = Random.nextInt(100_000, 999_999)
        return servicio.crearCorporacion(admin, CorporacionEntrada("Corporación prueba $sufijo", "OTRA", "PRUEBA_$sufijo"), null).id
    }

    @Test
    fun `la busqueda pide 3 caracteres y encuentra por nombre, matricula o telefono`() {
        assertEquals("VALIDACION", codigoDe { servicio.buscarUsuarios("an", null) })
        val encontrados = servicio.buscarUsuarios("Ana Prueba", null)
        assertTrue(encontrados.any { it.id == AlertasPrueba.ANA.toString() && it.escuela.isNotBlank() })
        assertTrue(servicio.buscarUsuarios("2026630001", null).any { it.id == AlertasPrueba.ANA.toString() })
        assertTrue(servicio.buscarUsuarios(null, "ADMINISTRADOR").all { it.rol == "ADMINISTRADOR" })
        assertEquals("VALIDACION", codigoDe { servicio.buscarUsuarios(null, "JEFE") })
    }

    @Test
    fun `asignar el perfil Operador lo vincula a su unidad, cierra sus sesiones y queda en la bitacora`() {
        val persona = cuentaNueva()
        val unidad = servicio.crearUnidad(admin, UnidadEntrada(corporacion(), "Unidad perfil", listOf("GENERICA")), null)

        assertEquals("VALIDACION", codigoDe { servicio.cambiarPerfil(admin, persona, CambioPerfil("OPERADOR", null, motivo), null) })
        assertEquals("VALIDACION", codigoDe { servicio.cambiarPerfil(admin, persona, CambioPerfil("OPERADOR", unidad.id, "corto"), null) })
        assertEquals("PROPIA_CUENTA", codigoDe { servicio.cambiarPerfil(admin, admin.usuarioId, CambioPerfil("INTEGRANTE", null, motivo), null) })

        val cambiado = servicio.cambiarPerfil(admin, persona, CambioPerfil("OPERADOR", unidad.id, motivo), null)
        assertEquals("OPERADOR", cambiado.rol)
        assertEquals(unidad.id, cambiado.unidadId)
        assertEquals(0, sesionesAbiertas(persona))
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'CAMBIAR_PERFIL' AND entidad_id = ?", persona.toString()))
        assertEquals(listOf(persona.toString()), servicio.unidad(UUID.fromString(unidad.id)).operadores.map { it.usuarioId })

        // De regreso a Integrante deja de ser operador de la unidad.
        val integrante = servicio.cambiarPerfil(admin, persona, CambioPerfil("INTEGRANTE", null, "Ya no trabaja en la caseta"), null)
        assertEquals(null, integrante.unidadId)
        assertTrue(servicio.unidad(UUID.fromString(unidad.id)).operadores.isEmpty())
    }

    @Test
    fun `suspender cierra las sesiones y desbloquear limpia los intentos`() {
        val persona = cuentaNueva()
        assertEquals("SUSPENDIDA", servicio.cambiarEstado(admin, persona, CambioEstadoCuenta("SUSPENDIDA", "Uso indebido de la app"), null).estado)
        assertEquals(0, sesionesAbiertas(persona))
        sql("UPDATE usuario SET estado = 'BLOQUEADA_TEMPORAL', intentos_fallidos = 5, bloqueada_hasta = now() + interval '1 hour' WHERE id = ?", persona)
        assertEquals("ACTIVA", servicio.cambiarEstado(admin, persona, CambioEstadoCuenta("ACTIVA", "La persona confirmó su identidad"), null).estado)
        assertEquals(0, numero("SELECT intentos_fallidos FROM usuario WHERE id = ?", persona))
        assertEquals("VALIDACION", codigoDe { servicio.cambiarEstado(admin, persona, CambioEstadoCuenta("ELIMINADA", motivo), null) })
        assertEquals("PROPIA_CUENTA", codigoDe { servicio.cambiarEstado(admin, admin.usuarioId, CambioEstadoCuenta("SUSPENDIDA", motivo), null) })
    }

    @Test
    fun `corporaciones y unidades con sus tipos, duplicados y baja`() {
        val corp = corporacion()
        val nombre = servicio.corporaciones().first { it.id == corp }.nombre
        assertEquals("DUPLICADO", codigoDe { servicio.crearCorporacion(admin, CorporacionEntrada(nombre, "OTRA", "OTRO_CANAL_${Random.nextInt(1000, 9999)}"), null) })
        assertEquals("VALIDACION", codigoDe { servicio.crearCorporacion(admin, CorporacionEntrada("Corp mala", "MARINA", "MALA"), null) })
        assertEquals("POLICIA", servicio.editarCorporacion(admin, corp, CorporacionCambio(tipo = "POLICIA"), null).tipo)

        assertEquals("VALIDACION", codigoDe { servicio.crearUnidad(admin, UnidadEntrada(corp, "Sin tipos", emptyList()), null) })
        assertEquals("VALIDACION", codigoDe { servicio.crearUnidad(admin, UnidadEntrada(corp, "Tipo raro", listOf("NO_EXISTE")), null) })
        // VENTA_SUSTANCIAS está inactivo en el catálogo de inicio.
        assertEquals("VALIDACION", codigoDe { servicio.crearUnidad(admin, UnidadEntrada(corp, "Tipo inactivo", listOf("VENTA_SUSTANCIAS")), null) })

        val unidad = servicio.crearUnidad(admin, UnidadEntrada(corp, "P-214", listOf("asalto_robo", "GENERICA")), null)
        assertEquals(listOf("ASALTO_ROBO", "GENERICA"), unidad.tipos)
        assertEquals("FUERA_DE_SERVICIO", unidad.estado)
        assertEquals("DUPLICADO", codigoDe { servicio.crearUnidad(admin, UnidadEntrada(corp, "P-214", listOf("GENERICA")), null) })

        val id = UUID.fromString(unidad.id)
        assertEquals(listOf("ACOSO"), servicio.editarUnidad(admin, id, UnidadCambio(tipos = listOf("ACOSO")), null).tipos)

        sql("UPDATE unidad_atencion SET estado = 'ASIGNADA' WHERE id = ?", id)
        assertEquals("UNIDAD_OCUPADA", codigoDe { servicio.editarUnidad(admin, id, UnidadCambio(activa = false), null) })
        sql("UPDATE unidad_atencion SET estado = 'DISPONIBLE' WHERE id = ?", id)
        val baja = servicio.editarUnidad(admin, id, UnidadCambio(activa = false), null)
        assertEquals(false, baja.activa)
        assertEquals("FUERA_DE_SERVICIO", baja.estado)
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'BAJA_UNIDAD' AND entidad_id = ?", unidad.id))
    }

    @Test
    fun `vincular operadores deja exactamente la lista y rechaza cuentas sin perfil Operador`() {
        val corp = corporacion()
        val a = UUID.fromString(servicio.crearUnidad(admin, UnidadEntrada(corp, "Unidad A", listOf("GENERICA")), null).id)
        val b = UUID.fromString(servicio.crearUnidad(admin, UnidadEntrada(corp, "Unidad B", listOf("GENERICA")), null).id)
        val uno = cuentaNueva().also { servicio.cambiarPerfil(admin, it, CambioPerfil("OPERADOR", a.toString(), motivo), null) }
        val dos = cuentaNueva().also { servicio.cambiarPerfil(admin, it, CambioPerfil("OPERADOR", a.toString(), motivo), null) }
        val integrante = cuentaNueva()

        assertEquals("VALIDACION", codigoDe { servicio.vincularOperadores(admin, a, OperadoresEntrada(listOf(integrante.toString())), null) })

        // Dos se cambia a la unidad B; en A solo queda Uno.
        servicio.vincularOperadores(admin, b, OperadoresEntrada(listOf(dos.toString())), null)
        assertEquals(listOf(uno.toString()), servicio.unidad(a).operadores.map { it.usuarioId })
        assertEquals(listOf(dos.toString()), servicio.unidad(b).operadores.map { it.usuarioId })

        // Lista vacía: la unidad se queda sin operadores.
        assertTrue(servicio.vincularOperadores(admin, a, OperadoresEntrada(emptyList()), null).operadores.isEmpty())
    }

    @Test
    fun `el operador abre y cierra su turno y manda su ubicacion`() {
        val corp = corporacion()
        val unidad = UUID.fromString(servicio.crearUnidad(admin, UnidadEntrada(corp, "Patrulla turno", listOf("GENERICA")), null).id)
        val persona = cuentaNueva().also { servicio.cambiarPerfil(admin, it, CambioPerfil("OPERADOR", unidad.toString(), motivo), null) }
        val operador = UsuarioToken(persona, Rol.OPERADOR, null, null)

        val abierta = servicio.cambiarTurno(operador, TurnoCambio("DISPONIBLE", 19.5045, -99.1469), null)
        assertEquals("DISPONIBLE", abierta.estado)
        assertEquals(19.5045, abierta.latitud)
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'CAMBIAR_TURNO_UNIDAD' AND entidad_id = ?", unidad.toString()))

        assertEquals("VALIDACION", codigoDe { servicio.cambiarTurno(operador, TurnoCambio("ASIGNADA"), null) })
        assertEquals("VALIDACION", codigoDe { servicio.cambiarTurno(operador, TurnoCambio(latitud = 19.5), null) })

        // Atendiendo una alerta puede mandar su ubicación, pero no cerrar el turno.
        sql("UPDATE unidad_atencion SET estado = 'ATENDIENDO' WHERE id = ?", unidad)
        assertEquals(19.6, servicio.cambiarTurno(operador, TurnoCambio(latitud = 19.6, longitud = -99.1), null).latitud)
        assertEquals("UNIDAD_OCUPADA", codigoDe { servicio.cambiarTurno(operador, TurnoCambio("FUERA_DE_SERVICIO"), null) })
        sql("UPDATE unidad_atencion SET estado = 'DISPONIBLE' WHERE id = ?", unidad)
        assertEquals("FUERA_DE_SERVICIO", servicio.cambiarTurno(operador, TurnoCambio("FUERA_DE_SERVICIO"), null).estado)

        val sinUnidad = UsuarioToken(cuentaNueva(), Rol.OPERADOR, null, null)
        assertEquals("NO_ENCONTRADO", codigoDe { servicio.miUnidad(sinUnidad) })
    }

    @Test
    fun `las rutas de administracion son solo para el Administrador`() = testApplication {
        val tokens = EmisorTokens("secreto-admin", "sendero-seguro", "sendero-seguro-api")
        environment {
            config = MapApplicationConfig("jwt.secreto" to "secreto-admin", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api")
        }
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasAdministracion(servicio) } }
        }
        val ana = UsuarioToken(AlertasPrueba.ANA, Rol.INTEGRANTE, null, null)
        fun jwt(u: UsuarioToken) = tokens.emitir(u, 1.hours).first

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/usuarios?buscar=Prueba") { header("Authorization", "Bearer ${jwt(ana)}") }.status)
        val r = client.get("/api/v1/usuarios?buscar=Prueba") { header("Authorization", "Bearer ${jwt(admin)}") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("Ana Prueba"))

        val sinCuerpo = client.patch("/api/v1/usuarios/${AlertasPrueba.ANA}/perfil") {
            header("Authorization", "Bearer ${jwt(admin)}")
            contentType(ContentType.Application.Json)
            setBody("""{"rol":"COORDINADOR"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, sinCuerpo.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/unidades/mia") { header("Authorization", "Bearer ${jwt(admin)}") }.status)
    }
}
