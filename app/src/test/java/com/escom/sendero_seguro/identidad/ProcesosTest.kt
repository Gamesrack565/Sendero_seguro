package com.escom.sendero_seguro.identidad

import com.escom.sendero_seguro.identidad.dominio.AvisoPrivacidad
import com.escom.sendero_seguro.identidad.dominio.AvisoVinculacion
import com.escom.sendero_seguro.identidad.dominio.CancelacionMotivo
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.EstadoBateria
import com.escom.sendero_seguro.identidad.dominio.EventoCancelacion
import com.escom.sendero_seguro.identidad.dominio.HashPin
import com.escom.sendero_seguro.identidad.dominio.MetodoAutenticacion
import com.escom.sendero_seguro.identidad.dominio.PoliticaEnergia
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.ResultadoCancelacion
import com.escom.sendero_seguro.identidad.dominio.TipoComunidad
import com.escom.sendero_seguro.identidad.dominio.UsuarioRol
import com.escom.sendero_seguro.identidad.procesos.ProcesoActivacionEnergia
import com.escom.sendero_seguro.identidad.procesos.ProcesoAutenticacion
import com.escom.sendero_seguro.identidad.procesos.ProcesoCancelacion
import com.escom.sendero_seguro.identidad.procesos.ProcesoRedApoyo
import com.escom.sendero_seguro.identidad.puertos.ClavesConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcesosTest {

    private val remoto = RemotoFalso()
    private val config = ConfigEnMemoria()
    private val almacen = AlmacenEnMemoria()
    private val contactos = ContactosEnMemoria()
    private val cola = ColaEnMemoria()
    private var ahora = 1_000_000L

    private val p1 = ProcesoAutenticacion(remoto, config, almacen, contactos, HardwareFalso, { ahora }, Dispatchers.Unconfined)
    private val p2 = ProcesoRedApoyo(remoto, config, contactos)
    private val p8 = ProcesoCancelacion(remoto, config, almacen, cola, { ahora }, Dispatchers.Unconfined)

    private val aviso = AvisoPrivacidad(3, "v3.1", "a".repeat(64))
    private val datos = DatosRegistro(
        "Mariana López Hernández", "55 1234 5678", "2023630417", TipoComunidad.ALUMNO,
        1, 1, "Ing. en Inteligencia Artificial", "segura2026"
    )

    private fun <T> Resultado<T>.ok(): T = (this as? Resultado.Exito)?.valor
        ?: throw AssertionError("Se esperaba éxito y llegó $this (${(this as Resultado.Error).error.mensaje})")

    private fun Resultado<*>.err(): ErrorIdentidad = (this as? Resultado.Error)?.error
        ?: throw AssertionError("Se esperaba error y llegó $this")

    private suspend fun iniciarSesion(rol: UsuarioRol = UsuarioRol.INTEGRANTE) {
        remoto.rolLogin = rol
        p1.autenticarPorRolYControlarIntentos("2023630417", "segura2026").ok()
    }

    // ------------------------------------------------------------ P1

    @Test
    fun sinAceptarAvisoNoSeGuardaNadaYNoSePuedeRegistrar() = runBlocking<Unit> {
        assertTrue(p1.registrarConsentimientoPrivacidad(aviso, false, true).err() is ErrorIdentidad.ConsentimientoRequerido)
        assertTrue(config.datos.value.isEmpty())
        assertTrue(p1.registrarCuentaPolitecnica(datos, "1234", "1234").err() is ErrorIdentidad.ConsentimientoRequerido)
    }

    @Test
    fun altaCompletaGuardaSesionPinYConsentimiento() = runBlocking<Unit> {
        val u = p1.ejecutarAlta(aviso, aceptaEntrenamientoIa = true, datos = datos, pin = "1234", pinConfirmacion = "1234").ok()
        assertEquals("u-1", u.id)
        val (enviado, consentimiento) = remoto.ultimoRegistro!!
        assertEquals("5512345678", enviado.telefonoMovil)
        assertEquals(3, consentimiento.avisoId)
        assertTrue(consentimiento.aceptaEntrenamientoIa)
        assertTrue(HashPin.verificar("1234", almacen.hashPin!!))
        assertEquals("tok", almacen.token!!.token)
        assertNull(p1.consentimientoPendiente()) // ya se envió
        val s = p1.sesionLocal()!!
        assertEquals(UsuarioRol.INTEGRANTE, s.rol)
        assertTrue(s.tokenVigente)
    }

    @Test
    fun registroConPinDistintoNoLlamaAlServidor() = runBlocking<Unit> {
        p1.registrarConsentimientoPrivacidad(aviso, true, false).ok()
        val e = p1.registrarCuentaPolitecnica(datos, "1234", "9999").err() as ErrorIdentidad.Validacion
        assertTrue(e.errores.containsKey("pinConfirmacion"))
        assertNull(remoto.ultimoRegistro)
    }

    @Test
    fun administradorNoEntraALaApp() = runBlocking<Unit> {
        remoto.rolLogin = UsuarioRol.ADMINISTRADOR
        val e = p1.autenticarPorRolYControlarIntentos("2023630417", "segura2026").err()
        assertTrue(e is ErrorIdentidad.PerfilNoPermitido)
        assertTrue(remoto.sesionCerrada)
        assertNull(p1.sesionLocal())
    }

    @Test
    fun loginEnTelefonoNuevoPideCrearPin() = runBlocking<Unit> {
        val r = p1.autenticarPorRolYControlarIntentos("55 1234 5678", "segura2026").ok()
        assertTrue(r.requiereCrearPin)
        p1.crearPinCancelacion("4321", "4321").ok()
        assertTrue(HashPin.verificar("4321", almacen.hashPin!!))
    }

    @Test
    fun bloqueoTemporalEvitaNuevosIntentosHastaQueVence() = runBlocking<Unit> {
        remoto.errorLogin = ErrorIdentidad.CuentaBloqueada(ahora + 60_000)
        assertTrue(p1.autenticarPorRolYControlarIntentos("2023630417", "mala1234").err() is ErrorIdentidad.CuentaBloqueada)
        remoto.errorLogin = null
        // Sigue bloqueada localmente aunque el servidor ya respondería bien
        assertTrue(p1.autenticarPorRolYControlarIntentos("2023630417", "segura2026").err() is ErrorIdentidad.CuentaBloqueada)
        ahora += 61_000
        p1.autenticarPorRolYControlarIntentos("2023630417", "segura2026").ok()
        assertNull(config.datos.value[ClavesConfig.BLOQUEADA_HASTA])
    }

    @Test
    fun sesionLocalFuncionaSinInternetYConTokenVencido() = runBlocking<Unit> {
        iniciarSesion()
        remoto.sinConexion = true
        almacen.token = almacen.token!!.copy(expiraEnMs = ahora - 1)
        val s = p1.sesionLocal()
        assertNotNull(s)
        assertFalse(s!!.tokenVigente)
    }

    @Test
    fun cerrarSesionBorraDatosLocales() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        p2.agregarContacto("Laura Hernández", "5588124410").ok()
        p1.cerrarSesion()
        assertNull(p1.sesionLocal())
        assertNull(almacen.hashPin)
        assertTrue(contactos.listar().isEmpty())
    }

    // ------------------------------------------------------------ P2

    @Test
    fun contactosSeInsertanPorPrioridadYSeNotifica() = runBlocking<Unit> {
        iniciarSesion()
        val laura = p2.agregarContacto("Laura Hernández (mamá)", "55 8812 4410").ok()
        val diego = p2.agregarContacto("Diego Ruiz", "5530319987").ok()
        val sofia = p2.agregarContacto("Sofía Pérez", "5567451283", prioridad = 1).ok()
        val lista = contactos.listar()
        assertEquals(listOf(sofia.id, laura.id, diego.id), lista.map { it.id })
        assertEquals(listOf(1, 2, 3), lista.map { it.prioridad })
        assertTrue(lista.none { it.pendienteSincronizar })
        assertTrue(laura.tieneApp) // el backend decide si tiene app
        assertEquals(listOf(sofia.id, laura.id, diego.id), remoto.ordenRecibido)
        assertEquals(3, remoto.avisos.count { it is AvisoVinculacion.ContactoAgregado })
    }

    @Test
    fun contactoSinSenalQuedaPendienteYSeSincroniza() = runBlocking<Unit> {
        iniciarSesion()
        remoto.sinConexion = true
        val c = p2.agregarContacto("Andrés Ortiz", "5544182231").ok()
        assertTrue(c.pendienteSincronizar)
        assertEquals(c.id, config.datos.value[ClavesConfig.CONTACTOS_PENDIENTES])
        assertTrue(remoto.contactos.isEmpty())

        remoto.sinConexion = false
        val lista = p2.sincronizarContactos().ok()
        assertEquals(1, lista.size)
        assertEquals(c.id, remoto.contactos.keys.single()) // mismo UUID: el reenvío no duplica
        assertNull(config.datos.value[ClavesConfig.CONTACTOS_PENDIENTES])
    }

    @Test
    fun eliminarRenumeraYReordenarExigeTodos() = runBlocking<Unit> {
        iniciarSesion()
        val a = p2.agregarContacto("Ana", "5511111111").ok()
        val b = p2.agregarContacto("Beto", "5522222222").ok()
        val c = p2.agregarContacto("Caro", "5533333333").ok()
        p2.eliminarContacto(a.id).ok()
        assertEquals(listOf(b.id to 1, c.id to 2), contactos.listar().map { it.id to it.prioridad })
        assertTrue(p2.reordenarContactos(listOf(c.id)).err() is ErrorIdentidad.Validacion)
        p2.reordenarContactos(listOf(c.id, b.id)).ok()
        assertEquals(listOf(c.id, b.id), contactos.listar().map { it.id })
    }

    @Test
    fun soloElCoordinadorCreaCirculoYDaDeBaja() = runBlocking<Unit> {
        iniciarSesion(UsuarioRol.INTEGRANTE)
        assertTrue(p2.crearCirculo("6IV1").err() is ErrorIdentidad.SinPermiso)
        assertTrue(p2.darDeBajaIntegrante("u-2").err() is ErrorIdentidad.SinPermiso)

        iniciarSesion(UsuarioRol.COORDINADOR)
        val circ = p2.crearCirculo("Grupo 6IV1").ok()
        assertEquals(circ.id, config.datos.value[ClavesConfig.CIRCULO_ID])
        assertEquals("ZZZ-999", p2.regenerarCodigo().ok().codigoInvitacion)
        assertTrue(p2.darDeBajaIntegrante("u-1").err() is ErrorIdentidad.Conflicto) // a sí mismo no
        p2.darDeBajaIntegrante("u-2").ok()
    }

    @Test
    fun unirseConCodigoNormalizaYAvisaAlCirculo() = runBlocking<Unit> {
        iniciarSesion()
        assertTrue(p2.unirseConCodigo("K7Q").err() is ErrorIdentidad.Validacion)
        assertTrue(p2.unirseConCodigo("AAA-111").err() is ErrorIdentidad.CodigoInvalido)
        val c = p2.unirseConCodigo("k7q42m").ok()
        assertEquals("c-1", config.datos.value[ClavesConfig.CIRCULO_ID])
        assertTrue(remoto.avisos.contains(AvisoVinculacion.NuevoIntegrante(c.id)))
    }

    // ------------------------------------------------------------ P3

    @Test
    fun configuracionDeActivacionYEscaneo() = runBlocking<Unit> {
        val bateria = BateriaFalsa(EstadoBateria(64, false))
        val p3 = ProcesoActivacionEnergia(config, bateria, EnergiaFalsa(false))
        assertEquals(5, p3.configActual().cuentaRegresivaSeg) // valor por defecto
        assertTrue(p3.definirCuentaRegresivaPreEnvio(7).err() is ErrorIdentidad.Validacion)
        p3.definirCuentaRegresivaPreEnvio(10).ok()
        p3.configurarAtajosSinDesbloquear(atajoActivo = false)
        val c = p3.configActual()
        assertEquals(10, c.cuentaRegresivaSeg)
        assertFalse(c.atajoActivo)
        assertTrue(c.widgetActivo)
        assertTrue(p3.requiereExclusionAhorroEnergia())

        assertEquals(PoliticaEnergia.INTERVALO_NORMAL_MS, p3.ajustarEscaneoMallaPorBateria().first().intervaloMs)
        bateria.estado.value = EstadoBateria(15, false)
        assertEquals(PoliticaEnergia.INTERVALO_AHORRO_MS, p3.ajustarEscaneoMallaPorBateria().first().intervaloMs)
        p3.configurarRedMalla(retransmitir = false)
        assertFalse(p3.ajustarEscaneoMallaPorBateria().first().retransmitirOtros)
    }

    // ------------------------------------------------------------ P8

    @Test
    fun pinCorrectoCancelaYAvisaAlModulo2() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        val evento = async(start = CoroutineStart.UNDISPATCHED) { p8.eventos.first() }
        val r = p8.cancelarConPin("alr-1", CancelacionMotivo.YA_ESTOY_A_SALVO, "1234").ok()
        assertEquals(ResultadoCancelacion.Cancelada("alr-1", sincronizada = true), r)
        val s = remoto.cancelaciones.single()
        assertTrue(s.exitoso)
        assertEquals(MetodoAutenticacion.PIN, s.metodo)
        assertEquals("u-1", s.usuarioId)
        assertEquals(EventoCancelacion.AlertaCancelada("alr-1", CancelacionMotivo.YA_ESTOY_A_SALVO), evento.await())
    }

    @Test
    fun pinIncorrectoMantieneLaAlertaYRegistraIntento() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        val eventos = async(start = CoroutineStart.UNDISPATCHED) { p8.eventos.take(1).toList() }
        val r = p8.cancelarConPin("alr-1", CancelacionMotivo.FALSA_ALARMA, "0000").ok()
        assertTrue(r is ResultadoCancelacion.NoCancelada)
        assertEquals(ResultadoCancelacion.MENSAJE_NEUTRO, (r as ResultadoCancelacion.NoCancelada).mensajeNeutro)
        assertFalse(remoto.cancelaciones.single().exitoso)
        assertEquals(listOf(EventoCancelacion.IntentoFallido("alr-1")), eventos.await())
        assertTrue(cola.encoladas.isEmpty())
    }

    @Test
    fun sinMotivoNoSeCancela() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        assertTrue(p8.cancelarConPin("alr-1", null, "1234").err() is ErrorIdentidad.Validacion)
        assertTrue(remoto.cancelaciones.isEmpty())
    }

    @Test
    fun sinSenalLaCancelacionVaALaColaYLosFallidosSeSincronizan() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        remoto.sinConexion = true
        p8.cancelarConPin("alr-1", CancelacionMotivo.FALSA_ALARMA, "9999").ok() // fallido sin señal
        val r = p8.cancelarConPin("alr-1", CancelacionMotivo.FALSA_ALARMA, "1234").ok() as ResultadoCancelacion.Cancelada
        assertFalse(r.sincronizada)
        assertTrue(cola.encoladas.single().exitoso)
        assertNotNull(config.datos.value[ClavesConfig.CANCELACIONES_FALLIDAS_PENDIENTES])

        remoto.sinConexion = false
        assertEquals(1, p8.sincronizarIntentosFallidosPendientes())
        assertFalse(remoto.cancelaciones.single().exitoso)
        assertEquals(CancelacionMotivo.FALSA_ALARMA, remoto.cancelaciones.single().motivo)
        assertNull(config.datos.value[ClavesConfig.CANCELACIONES_FALLIDAS_PENDIENTES])
    }

    @Test
    fun cancelarEnCuentaRegresivaNoLlamaAlServidor() = runBlocking<Unit> {
        iniciarSesion()
        val r = p8.cancelarConBiometria("alr-2", CancelacionMotivo.ACTIVACION_ACCIDENTAL, autenticada = true, alertaYaEnviada = false).ok()
        assertEquals(ResultadoCancelacion.Cancelada("alr-2", sincronizada = true), r)
        assertTrue(remoto.cancelaciones.isEmpty())
        assertTrue(cola.encoladas.isEmpty())
    }

    @Test
    fun laCancelacionSinSenalSeMandaCuandoVuelveInternet() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        remoto.sinConexion = true
        p8.cancelarConPin("alr-1", CancelacionMotivo.YA_ESTOY_A_SALVO, "1234").ok()
        p8.cancelarConPin("alr-2", CancelacionMotivo.FALSA_ALARMA, "1234").ok()
        assertEquals(0, p8.sincronizarCancelacionesPendientes())
        assertEquals("Sin red no se pierde nada", 2, cola.encoladas.size)

        // La alerta todavía no llega al servidor (va por la malla): se conserva.
        remoto.sinConexion = false
        remoto.errorCancelacion = ErrorIdentidad.NoEncontrado()
        assertEquals(0, p8.sincronizarCancelacionesPendientes())
        assertEquals(2, cola.encoladas.size)

        remoto.errorCancelacion = null
        assertEquals(2, p8.sincronizarCancelacionesPendientes())
        assertTrue(cola.encoladas.isEmpty())
        assertEquals(listOf("alr-1", "alr-2"), remoto.cancelaciones.map { it.alertaId })
        assertTrue(remoto.cancelaciones.all { it.exitoso })
    }

    @Test
    fun unaCancelacionQueElServidorNoAceptaSeQuitaDeLaCola() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        remoto.sinConexion = true
        p8.cancelarConPin("alr-1", CancelacionMotivo.YA_ESTOY_A_SALVO, "1234").ok()
        remoto.sinConexion = false
        remoto.errorCancelacion = ErrorIdentidad.Validacion(mapOf("motivo" to "Motivo desconocido"))
        assertEquals(0, p8.sincronizarCancelacionesPendientes())
        assertTrue(cola.encoladas.isEmpty())
    }

    @Test
    fun elPinDeCoaccionDebeSerDistintoDelDeCancelacion() = runBlocking<Unit> {
        iniciarSesion()
        assertTrue("Primero el PIN de cancelación", p1.crearPinCoaccion("9876", "9876").err() is ErrorIdentidad.PinNoConfigurado)
        p1.crearPinCancelacion("1234", "1234").ok()
        assertTrue(p1.crearPinCoaccion("1234", "1234").err() is ErrorIdentidad.Validacion)
        assertTrue(p1.crearPinCoaccion("98", "98").err() is ErrorIdentidad.Validacion)
        assertFalse(p1.tienePinCoaccion())
        p1.crearPinCoaccion("9876", "9876").ok()
        assertTrue(p1.tienePinCoaccion())
        assertTrue("El PIN de cancelación nuevo tampoco puede ser el de coacción", p1.cambiarPin("1234", "9876", "9876").err() is ErrorIdentidad.Validacion)
    }

    @Test
    fun conElPinDeCoaccionSeVeCanceladaPeroSigueActiva() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        p1.crearPinCoaccion("9876", "9876").ok()
        val eventos = async(start = CoroutineStart.UNDISPATCHED) { p8.eventos.take(1).toList() }
        val r = p8.cancelarConPin("alr-1", CancelacionMotivo.YA_ESTOY_A_SALVO, "9876").ok()
        // La pantalla recibe lo mismo que en una cancelación real.
        assertEquals(ResultadoCancelacion.Cancelada("alr-1", sincronizada = true), r)
        // Al servidor va como intento no exitoso: la alerta sigue y se marca la coacción.
        assertFalse(remoto.cancelaciones.single().exitoso)
        assertEquals(listOf(EventoCancelacion.CoaccionSilenciosa("alr-1")), eventos.await())
        assertTrue("No se encola una cancelación", cola.encoladas.isEmpty())

        // Un PIN que no es ninguno de los dos sigue siendo un intento fallido con mensaje neutro.
        assertTrue(p8.cancelarConPin("alr-1", CancelacionMotivo.YA_ESTOY_A_SALVO, "5555").ok() is ResultadoCancelacion.NoCancelada)
        p1.quitarPinCoaccion()
        assertTrue(p8.cancelarConPin("alr-1", CancelacionMotivo.YA_ESTOY_A_SALVO, "9876").ok() is ResultadoCancelacion.NoCancelada)
    }

    @Test
    fun laCoaccionSinSenalSeGuardaYSeMandaDespues() = runBlocking<Unit> {
        iniciarSesion()
        p1.crearPinCancelacion("1234", "1234").ok()
        p1.crearPinCoaccion("9876", "9876").ok()
        remoto.sinConexion = true
        val r = p8.cancelarConPin("alr-1", CancelacionMotivo.FALSA_ALARMA, "9876").ok()
        assertTrue(r is ResultadoCancelacion.Cancelada)
        remoto.sinConexion = false
        assertEquals(1, p8.sincronizarIntentosFallidosPendientes())
        assertFalse(remoto.cancelaciones.single().exitoso)
    }
}
