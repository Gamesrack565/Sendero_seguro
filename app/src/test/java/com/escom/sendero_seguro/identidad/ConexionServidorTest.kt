package com.escom.sendero_seguro.identidad

import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import com.escom.sendero_seguro.identidad.datos.remoto.ServicioRemotoRetrofit
import com.escom.sendero_seguro.identidad.dominio.AvisoVinculacion
import com.escom.sendero_seguro.identidad.dominio.CodigoEstado
import com.escom.sendero_seguro.identidad.dominio.ConsentimientoPendiente
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.InfoDispositivo
import com.escom.sendero_seguro.identidad.dominio.MiembroEstado
import com.escom.sendero_seguro.identidad.dominio.NotificacionCanal
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.TipoComunidad
import com.escom.sendero_seguro.identidad.dominio.UsuarioEstado
import com.escom.sendero_seguro.identidad.dominio.UsuarioRol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.random.Random

/**
 * Prueba de punta a punta del P1 y el P2 contra el servidor real (Módulo 4): usa el mismo cliente Retrofit de la app.
 *
 * Necesita el servidor corriendo en local (`.\gradlew.bat :server:run`). Si no está prendido, la prueba se salta.
 * Otro servidor: `-Dsendero.servidor=http://.../api/v1/` o la variable SENDERO_SERVIDOR.
 */
class ConexionServidorTest {

    private val baseUrl = (System.getProperty("sendero.servidor") ?: System.getenv("SENDERO_SERVIDOR")
        ?: "http://localhost:8080/api/v1/").let { if (it.endsWith("/")) it else "$it/" }

    private val almacen = AlmacenEnMemoria()
    private val remoto = ServicioRemotoRetrofit(ClienteHttp.crear(baseUrl, almacen, false), ClienteHttp.json)

    private val telefono = "55" + Random.nextLong(10_000_000, 99_999_999)
    private val matricula = "M" + Random.nextLong(10_000_000, 99_999_999)
    private val dispositivo = InfoDispositivo(UUID.randomUUID().toString(), "Pixel de prueba", 34, true)

    @Before
    fun servidorPrendido() {
        val arriba = runCatching {
            (URL(baseUrl + "salud").openConnection() as HttpURLConnection).run {
                connectTimeout = 2000; readTimeout = 2000
                responseCode == 200
            }
        }.getOrDefault(false)
        assumeTrue("El servidor no responde en $baseUrl; prueba omitida", arriba)
    }

    private fun <T> ok(r: Resultado<T>): T {
        assertTrue("Se esperaba éxito y llegó $r " + ((r as? Resultado.Error)?.error?.mensaje ?: ""), r is Resultado.Exito)
        return (r as Resultado.Exito).valor
    }

    private fun error(r: Resultado<*>): ErrorIdentidad {
        assertTrue("Se esperaba un error y llegó $r", r is Resultado.Error)
        return (r as Resultado.Error).error
    }

    private suspend fun datos(nombre: String = "Prueba Conexion", tel: String = telefono, mat: String = matricula): Pair<DatosRegistro, ConsentimientoPendiente> {
        val aviso = ok(remoto.avisoVigente())
        val institucion = ok(remoto.instituciones()).first()
        val escuela = ok(remoto.escuelas(institucion.id)).first()
        return DatosRegistro(
            nombre = nombre, telefonoMovil = tel, identificadorInstitucional = mat,
            tipoComunidad = TipoComunidad.ALUMNO, institucionId = institucion.id, escuelaId = escuela.id,
            carreraAdscripcion = "Ingenieria en Sistemas", contrasena = "Segura2026"
        ) to ConsentimientoPendiente(aviso.id, aviso.version, System.currentTimeMillis(), aceptaEntrenamientoIa = true)
    }

    @Test
    fun catalogosLleganConLosNombresQueEsperaLaApp() = runBlocking<Unit> {
        val aviso = ok(remoto.avisoVigente())
        assertTrue(aviso.version.isNotBlank() && aviso.contenidoHash.isNotBlank())
        val instituciones = ok(remoto.instituciones())
        assertTrue(instituciones.isNotEmpty())
        val escuelas = ok(remoto.escuelas(instituciones.first().id))
        assertTrue(escuelas.isNotEmpty())
        assertTrue(escuelas.all { it.institucionId == instituciones.first().id })
    }

    @Test
    fun registroPerfilSesionYBaja() = runBlocking<Unit> {
        val (d, c) = datos()

        // Registro: responde la sesión con el usuario.
        val sesion = ok(remoto.registrar(d, c, dispositivo))
        assertEquals(telefono, sesion.usuario.telefonoMovil)
        assertEquals(UsuarioRol.INTEGRANTE, sesion.usuario.rol)
        assertEquals(UsuarioEstado.ACTIVA, sesion.usuario.estado)
        assertTrue(sesion.usuario.aceptaEntrenamientoIa)
        assertTrue("expiraEn debe llegar como fecha", sesion.expiraEnMs > System.currentTimeMillis())
        almacen.guardarToken(sesion.token, sesion.expiraEnMs)

        // Perfil con el token.
        assertEquals(sesion.usuario.id, ok(remoto.perfil()).id)
        val cambiado = ok(remoto.actualizarPerfil(null, null, "Ciencia de Datos", false))
        assertEquals("Ciencia de Datos", cambiado.carreraAdscripcion)
        assertEquals(false, cambiado.aceptaEntrenamientoIa)

        // Repetir teléfono o matrícula: 409.
        val (otro, c2) = datos(tel = telefono, mat = "X" + Random.nextLong(10_000_000, 99_999_999))
        assertTrue(error(remoto.registrar(otro, c2, dispositivo.copy(id = UUID.randomUUID().toString()))) is ErrorIdentidad.Conflicto)

        // Cerrar sesión: el token deja de servir y la app lo ve como "sin sesión".
        ok(remoto.cerrarSesion())
        assertTrue(error(remoto.perfil()) is ErrorIdentidad.SinSesion)

        // Contraseña mala: credenciales inválidas con los intentos que quedan.
        val mala = error(remoto.iniciarSesion(telefono, "Incorrecta99", dispositivo))
        assertTrue(mala is ErrorIdentidad.CredencialesInvalidas)
        assertEquals(4, (mala as ErrorIdentidad.CredencialesInvalidas).intentosRestantes)

        // Login con la matrícula en minúsculas: entra y el contador se reinicia.
        val otraSesion = ok(remoto.iniciarSesion(matricula.lowercase(), "Segura2026", dispositivo))
        assertEquals(sesion.usuario.id, otraSesion.usuario.id)
        almacen.guardarToken(otraSesion.token, otraSesion.expiraEnMs)

        // Baja: con la contraseña correcta; después ya no puede entrar.
        ok(remoto.solicitarBaja("Segura2026"))
        assertTrue(error(remoto.perfil()) is ErrorIdentidad.SinSesion)
        assertTrue(error(remoto.iniciarSesion(telefono, "Segura2026", dispositivo)) is ErrorIdentidad.CuentaSuspendida)
    }

    @Test
    fun erroresDeValidacionTraenLasClavesDeCadaCampo() = runBlocking<Unit> {
        val (d, c) = datos()
        val malo = d.copy(nombre = "A1", telefonoMovil = "123", identificadorInstitucional = "x", contrasena = "corta")
        val e = error(remoto.registrar(malo, c, dispositivo))
        assertTrue(e is ErrorIdentidad.Validacion)
        assertEquals(setOf("nombre", "telefono", "identificador", "contrasena"), (e as ErrorIdentidad.Validacion).errores.keys)
    }

    @Test
    fun alQuintoIntentoLaCuentaSeBloquea() = runBlocking<Unit> {
        val (d, c) = datos()
        ok(remoto.registrar(d, c, dispositivo))
        repeat(4) { i ->
            val e = error(remoto.iniciarSesion(telefono, "Incorrecta99", dispositivo))
            assertEquals(4 - i, (e as ErrorIdentidad.CredencialesInvalidas).intentosRestantes)
        }
        val bloqueo = error(remoto.iniciarSesion(telefono, "Incorrecta99", dispositivo))
        assertTrue(bloqueo is ErrorIdentidad.CuentaBloqueada)
        assertTrue("Debe decir hasta cuándo", ((bloqueo as ErrorIdentidad.CuentaBloqueada).hastaMs ?: 0) > System.currentTimeMillis())
        // Ni con la contraseña correcta entra mientras dure el bloqueo.
        assertTrue(error(remoto.iniciarSesion(telefono, "Segura2026", dispositivo)) is ErrorIdentidad.CuentaBloqueada)
    }

    @Test
    fun unOperadorNoPuedeEntrarDesdeLaApp() = runBlocking<Unit> {
        // Cuenta de prueba del servidor local (R__datos_prueba): operador 5510000011 / sendero123.
        val e = error(remoto.iniciarSesion("5510000011", "sendero123", dispositivo))
        assertTrue("Llegó $e", e is ErrorIdentidad.PerfilNoPermitido)
        assertEquals(UsuarioRol.OPERADOR, (e as ErrorIdentidad.PerfilNoPermitido).rol)
    }

    // ------------------------------------------------------------------ P2

    /** Cada persona con su propio cliente (y su propio token), como en su teléfono. */
    private class Persona(val remoto: ServicioRemotoRetrofit, val id: String, val telefono: String)

    private suspend fun persona(tipo: TipoComunidad = TipoComunidad.ALUMNO): Persona {
        val suAlmacen = AlmacenEnMemoria()
        val suRemoto = ServicioRemotoRetrofit(ClienteHttp.crear(baseUrl, suAlmacen, false), ClienteHttp.json)
        val tel = "55" + Random.nextLong(10_000_000, 99_999_999)
        val (d, c) = datos(tel = tel, mat = "P" + tel.drop(2))
        val sesion = ok(suRemoto.registrar(d.copy(tipoComunidad = tipo), c, dispositivo.copy(id = UUID.randomUUID().toString())))
        suAlmacen.guardarToken(sesion.token, sesion.expiraEnMs)
        return Persona(suRemoto, sesion.usuario.id, tel)
    }

    @Test
    fun contactosDeConfianza() = runBlocking<Unit> {
        val ana = persona()
        val amiga = persona()
        val mama = Contacto(UUID.randomUUID().toString(), "Mamá", "55 1234 5678", 1)
        val conApp = Contacto(UUID.randomUUID().toString(), "Amiga", amiga.telefono, 2)

        val guardada = ok(ana.remoto.guardarContacto(mama))
        assertEquals("5512345678", guardada.telefono)
        assertEquals(NotificacionCanal.SMS, guardada.canalPreferido)
        assertTrue(!guardada.tieneApp)
        val vinculada = ok(ana.remoto.guardarContacto(conApp))
        assertTrue("El teléfono es de una cuenta", vinculada.tieneApp)
        assertEquals(NotificacionCanal.PUSH, vinculada.canalPreferido)

        // Mismo número con otro id: conflicto.
        assertTrue(error(ana.remoto.guardarContacto(mama.copy(id = UUID.randomUUID().toString()))) is ErrorIdentidad.Conflicto)
        val malo = error(ana.remoto.guardarContacto(Contacto(UUID.randomUUID().toString(), "X", "12", 3)))
        assertEquals(setOf("nombre", "telefono"), (malo as ErrorIdentidad.Validacion).errores.keys)

        val ordenados = ok(ana.remoto.reordenarContactos(listOf(conApp.id, mama.id)))
        assertEquals(listOf(conApp.id to 1, mama.id to 2), ordenados.map { it.id to it.prioridad })
        ok(ana.remoto.notificarVinculacion(AvisoVinculacion.ContactoAgregado(mama.id)))

        ok(ana.remoto.eliminarContacto(mama.id))
        ok(ana.remoto.eliminarContacto(mama.id)) // idempotente
        assertEquals(listOf(conApp.id), ok(ana.remoto.listarContactos()).map { it.id })
        assertTrue("Amiga no ve los contactos de Ana", ok(amiga.remoto.listarContactos()).isEmpty())
    }

    @Test
    fun circuloEscolar() = runBlocking<Unit> {
        val profe = persona(TipoComunidad.DOCENTE)
        val alumna = persona()
        val alumno = persona()

        assertNull(ok(profe.remoto.miCirculo()))
        assertTrue(error(alumna.remoto.crearCirculo(UUID.randomUUID().toString(), "Grupo 5CV1")) is ErrorIdentidad.SinPermiso)
        val circulo = ok(profe.remoto.crearCirculo(UUID.randomUUID().toString(), "Grupo 5CV1"))
        val codigo = circulo.codigoInvitacion!!
        assertEquals(CodigoEstado.VIGENTE, circulo.estadoCodigo)
        assertTrue("Vence en el futuro", (circulo.codigoVenceEnMs ?: 0) > System.currentTimeMillis())

        assertTrue(error(alumna.remoto.unirseACirculo("ZZZ-999")) is ErrorIdentidad.CodigoInvalido)
        val unida = ok(alumna.remoto.unirseACirculo(codigo))
        assertEquals(circulo.id, unida.id)
        assertNull("El código solo lo ve el coordinador", unida.codigoInvitacion)
        ok(alumno.remoto.unirseACirculo(codigo))
        ok(alumna.remoto.notificarVinculacion(AvisoVinculacion.NuevoIntegrante(circulo.id)))
        assertEquals(circulo.id, ok(alumna.remoto.miCirculo())?.id)

        val miembros = ok(alumna.remoto.miembros(circulo.id))
        assertEquals(setOf(profe.id, alumna.id, alumno.id), miembros.map { it.usuarioId }.toSet())

        assertTrue(error(alumna.remoto.regenerarCodigo(circulo.id)) is ErrorIdentidad.SinPermiso)
        val nuevo = ok(profe.remoto.regenerarCodigo(circulo.id)).codigoInvitacion
        assertNotEquals(codigo, nuevo)

        assertTrue(error(alumna.remoto.darDeBaja(circulo.id, alumno.id)) is ErrorIdentidad.SinPermiso)
        ok(profe.remoto.darDeBaja(circulo.id, alumno.id))
        ok(alumna.remoto.retirarse(circulo.id))
        assertNull(ok(alumna.remoto.miCirculo()))
        val estados = ok(profe.remoto.miembros(circulo.id)).associate { it.usuarioId to it.estado }
        assertEquals(MiembroEstado.ACTIVA, estados[profe.id])
        assertEquals(MiembroEstado.RETIRADA, estados[alumna.id])
        assertEquals(MiembroEstado.DADA_DE_BAJA, estados[alumno.id])
    }
}
