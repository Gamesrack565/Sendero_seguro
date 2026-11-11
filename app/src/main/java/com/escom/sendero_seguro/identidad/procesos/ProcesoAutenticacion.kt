package com.escom.sendero_seguro.identidad.procesos

import com.escom.sendero_seguro.identidad.dominio.AvisoPrivacidad
import com.escom.sendero_seguro.identidad.dominio.ConsentimientoPendiente
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.Escuela
import com.escom.sendero_seguro.identidad.dominio.HashPin
import com.escom.sendero_seguro.identidad.dominio.InfoDispositivo
import com.escom.sendero_seguro.identidad.dominio.Institucion
import com.escom.sendero_seguro.identidad.dominio.ROLES_PERMITIDOS_EN_APP
import com.escom.sendero_seguro.identidad.dominio.RespuestaSesion
import com.escom.sendero_seguro.identidad.dominio.ResultadoAcceso
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.SesionLocal
import com.escom.sendero_seguro.identidad.dominio.Usuario
import com.escom.sendero_seguro.identidad.dominio.UsuarioRol
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import com.escom.sendero_seguro.identidad.dominio.exito
import com.escom.sendero_seguro.identidad.dominio.falla
import com.escom.sendero_seguro.identidad.puertos.AlmacenSeguro
import com.escom.sendero_seguro.identidad.puertos.ClavesConfig
import com.escom.sendero_seguro.identidad.puertos.InfoHardware
import com.escom.sendero_seguro.identidad.puertos.RepositorioConfigLocal
import com.escom.sendero_seguro.identidad.puertos.RepositorioContactosLocal
import com.escom.sendero_seguro.identidad.puertos.ServicioRemotoIdentidad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * P1. Aceptación de Privacidad, Registro y Autenticación por Perfil.
 * Interfaces: A-01, A-02, A-03, A-11 (W-01 es de la consola web).
 *
 * Composición: registrarConsentimientoPrivacidad() + registrarCuentaPolitecnica()
 *            + autenticarPorRolYControlarIntentos()
 */
class ProcesoAutenticacion(
    private val remoto: ServicioRemotoIdentidad,
    private val config: RepositorioConfigLocal,
    private val almacen: AlmacenSeguro,
    private val contactosLocal: RepositorioContactosLocal,
    private val hardware: InfoHardware,
    private val reloj: () -> Long = System::currentTimeMillis,
    /** PBKDF2 es costoso: se calcula fuera del hilo principal. */
    private val cpu: CoroutineDispatcher = Dispatchers.Default
) {

    // ------------------------------------------------------------------ A-01

    suspend fun cargarAvisoVigente(): Resultado<AvisoPrivacidad> = remoto.avisoVigente()

    /**
     * Guarda la aceptación del aviso vigente y el permiso opcional de entrenamiento de IA.
     * Si la persona no acepta no se guarda nada (flujo alterno 2a de CU-01).
     */
    suspend fun registrarConsentimientoPrivacidad(
        aviso: AvisoPrivacidad,
        aceptado: Boolean,
        aceptaEntrenamientoIa: Boolean
    ): Resultado<ConsentimientoPendiente> {
        if (!aceptado) {
            borrarConsentimientoPendiente()
            return falla(ErrorIdentidad.ConsentimientoRequerido)
        }
        val c = ConsentimientoPendiente(aviso.id, aviso.version, reloj(), aceptaEntrenamientoIa)
        config.escribir(ClavesConfig.CONSENTIMIENTO_AVISO_ID, c.avisoId.toString())
        config.escribir(ClavesConfig.CONSENTIMIENTO_AVISO_VERSION, c.avisoVersion)
        config.escribir(ClavesConfig.CONSENTIMIENTO_ACEPTADO_EN, c.aceptadoEnMs.toString())
        config.escribir(ClavesConfig.ACEPTA_ENTRENAMIENTO_IA, c.aceptaEntrenamientoIa.toString())
        return exito(c)
    }

    suspend fun consentimientoPendiente(): ConsentimientoPendiente? {
        val id = config.leer(ClavesConfig.CONSENTIMIENTO_AVISO_ID)?.toIntOrNull() ?: return null
        val version = config.leer(ClavesConfig.CONSENTIMIENTO_AVISO_VERSION) ?: return null
        val en = config.leer(ClavesConfig.CONSENTIMIENTO_ACEPTADO_EN)?.toLongOrNull() ?: return null
        val ia = config.leer(ClavesConfig.ACEPTA_ENTRENAMIENTO_IA)?.toBoolean() ?: false
        return ConsentimientoPendiente(id, version, en, ia)
    }

    // ------------------------------------------------------------------ A-02

    suspend fun instituciones(): Resultado<List<Institucion>> = remoto.instituciones()

    suspend fun escuelas(institucionId: Int): Resultado<List<Escuela>> = remoto.escuelas(institucionId)

    /** Valida el paso 1 de A-02 sin llamar al servidor (para habilitar «Continuar: crear PIN»). */
    fun validarPasoDatos(datos: DatosRegistro): Map<String, String> = Validaciones.validarRegistro(datos)

    /**
     * Alta del integrante politécnico (A-02, pasos 1 y 2). Valida los datos y el PIN,
     * crea la cuenta en el backend con el consentimiento y deja la sesión y el PIN en el teléfono.
     */
    suspend fun registrarCuentaPolitecnica(
        datos: DatosRegistro,
        pin: String,
        pinConfirmacion: String
    ): Resultado<Usuario> {
        val consentimiento = consentimientoPendiente()
            ?: return falla(ErrorIdentidad.ConsentimientoRequerido)

        val errores = Validaciones.validarRegistro(datos) + Validaciones.validarPin(pin, pinConfirmacion)
        if (errores.isNotEmpty()) return falla(ErrorIdentidad.Validacion(errores))

        val normalizados = datos.copy(
            nombre = datos.nombre.trim().replace(Regex("\\s+"), " "),
            telefonoMovil = Validaciones.normalizarTelefono(datos.telefonoMovil),
            identificadorInstitucional = datos.identificadorInstitucional.trim().uppercase(),
            carreraAdscripcion = datos.carreraAdscripcion.trim()
        )

        return when (val r = remoto.registrar(normalizados, consentimiento, infoDispositivo())) {
            is Resultado.Error -> r
            is Resultado.Exito -> {
                almacen.guardarHashPin(withContext(cpu) { HashPin.generar(pin) })
                guardarSesion(r.valor)
                borrarConsentimientoPendiente()
                exito(r.valor.usuario)
            }
        }
    }

    // ------------------------------------------------------------------ A-03

    /**
     * Verifica credenciales, respeta el bloqueo temporal tras 5 intentos fallidos (lo cuenta el backend
     * en usuario.intentos_fallidos) y comprueba que el perfil pueda usar la app móvil.
     */
    suspend fun autenticarPorRolYControlarIntentos(usuario: String, contrasena: String): Resultado<ResultadoAcceso> {
        val errores = Validaciones.validarInicioSesion(usuario, contrasena)
        if (errores.isNotEmpty()) return falla(ErrorIdentidad.Validacion(errores))

        // Si el backend ya nos dijo que la cuenta está bloqueada, no gastamos más intentos.
        val bloqueadaHasta = config.leer(ClavesConfig.BLOQUEADA_HASTA)?.toLongOrNull()
        if (bloqueadaHasta != null && bloqueadaHasta > reloj()) {
            return falla(ErrorIdentidad.CuentaBloqueada(bloqueadaHasta))
        }

        val r = remoto.iniciarSesion(Validaciones.normalizarUsuario(usuario), contrasena, infoDispositivo())
        return when (r) {
            is Resultado.Error -> {
                val e = r.error
                if (e is ErrorIdentidad.CuentaBloqueada && e.hastaMs != null) {
                    config.escribir(ClavesConfig.BLOQUEADA_HASTA, e.hastaMs.toString())
                }
                r
            }
            is Resultado.Exito -> {
                val u = r.valor.usuario
                if (u.rol !in ROLES_PERMITIDOS_EN_APP) {
                    remoto.cerrarSesion() // no dejamos abierta una sesión que la app no usará
                    return falla(ErrorIdentidad.PerfilNoPermitido(u.rol))
                }
                config.borrar(ClavesConfig.BLOQUEADA_HASTA)
                guardarSesion(r.valor)
                exito(ResultadoAcceso(u, requiereCrearPin = almacen.leerHashPin() == null))
            }
        }
    }

    /** Crea el PIN cuando se inicia sesión en un teléfono que aún no lo tiene. */
    suspend fun crearPinCancelacion(pin: String, confirmacion: String): Resultado<Unit> {
        if (sesionLocal() == null) return falla(ErrorIdentidad.SinSesion)
        val e = Validaciones.validarPin(pin, confirmacion)
        if (e.isNotEmpty()) return falla(ErrorIdentidad.Validacion(e))
        almacen.guardarHashPin(withContext(cpu) { HashPin.generar(pin) })
        return exito(Unit)
    }

    /**
     * PIN de coacción (P3, RF-13): un segundo PIN, distinto del de cancelación. Si alguien obliga a la persona a
     * apagar la alerta, ella usa este: la pantalla muestra la alerta como cancelada y la alerta sigue activa.
     * Solo se guarda su hash.
     */
    suspend fun crearPinCoaccion(pin: String, confirmacion: String): Resultado<Unit> {
        if (sesionLocal() == null) return falla(ErrorIdentidad.SinSesion)
        val cancelacion = almacen.leerHashPin() ?: return falla(ErrorIdentidad.PinNoConfigurado)
        val e = Validaciones.validarPin(pin, confirmacion)
        if (e.isNotEmpty()) return falla(ErrorIdentidad.Validacion(e))
        if (withContext(cpu) { HashPin.verificar(pin, cancelacion) }) {
            return falla(ErrorIdentidad.Validacion(mapOf(Validaciones.PIN to "Debe ser distinto de tu PIN de cancelación")))
        }
        almacen.guardarHashPinCoaccion(withContext(cpu) { HashPin.generar(pin) })
        return exito(Unit)
    }

    /** Quita el PIN de coacción: un PIN distinto del de cancelación vuelve a ser solo un intento fallido. */
    fun quitarPinCoaccion() = almacen.borrarHashPinCoaccion()

    fun tienePinCoaccion(): Boolean = almacen.leerHashPinCoaccion() != null

    /**
     * Sesión guardada en el teléfono. Funciona sin internet: el botón de pánico solo necesita
     * saber quién es la persona; tokenVigente indica si hay que renovar al recuperar señal.
     */
    suspend fun sesionLocal(): SesionLocal? {
        val usuarioId = config.leer(ClavesConfig.USUARIO_ID) ?: return null
        val rol = config.leer(ClavesConfig.PERFIL)?.let { runCatching { UsuarioRol.valueOf(it) }.getOrNull() }
            ?: return null
        val token = almacen.leerToken()
        return SesionLocal(usuarioId, rol, tokenVigente = token != null && token.expiraEnMs > reloj())
    }

    /** Composición de P1 para el flujo completo de alta (A-01 → A-02). */
    suspend fun ejecutarAlta(
        aviso: AvisoPrivacidad,
        aceptaEntrenamientoIa: Boolean,
        datos: DatosRegistro,
        pin: String,
        pinConfirmacion: String
    ): Resultado<Usuario> {
        val c = registrarConsentimientoPrivacidad(aviso, aceptado = true, aceptaEntrenamientoIa = aceptaEntrenamientoIa)
        if (c is Resultado.Error) return c
        return registrarCuentaPolitecnica(datos, pin, pinConfirmacion)
    }

    // ------------------------------------------------------------------ A-11

    suspend fun perfil(): Resultado<Usuario> = remoto.perfil()

    suspend fun actualizarPerfil(
        telefonoMovil: String?,
        identificadorInstitucional: String?,
        carreraAdscripcion: String?,
        aceptaEntrenamientoIa: Boolean?
    ): Resultado<Usuario> {
        val e = mutableMapOf<String, String>()
        telefonoMovil?.let { Validaciones.validarTelefono(it)?.let { m -> e[Validaciones.TELEFONO] = m } }
        identificadorInstitucional?.let { Validaciones.validarIdentificador(it)?.let { m -> e[Validaciones.IDENTIFICADOR] = m } }
        carreraAdscripcion?.let { Validaciones.validarCarrera(it)?.let { m -> e[Validaciones.CARRERA] = m } }
        if (e.isNotEmpty()) return falla(ErrorIdentidad.Validacion(e))
        val r = remoto.actualizarPerfil(
            telefonoMovil?.let(Validaciones::normalizarTelefono),
            identificadorInstitucional?.trim()?.uppercase(),
            carreraAdscripcion?.trim(),
            aceptaEntrenamientoIa
        )
        if (r is Resultado.Exito) {
            config.escribir(ClavesConfig.ACEPTA_ENTRENAMIENTO_IA, r.valor.aceptaEntrenamientoIa.toString())
        }
        return r
    }

    /** Cambiar PIN (A-11): PIN actual + nuevo ×2. Solo local; el servidor nunca conoce el PIN. */
    suspend fun cambiarPin(actual: String, nuevo: String, confirmacion: String): Resultado<Unit> {
        val guardado = almacen.leerHashPin() ?: return falla(ErrorIdentidad.PinNoConfigurado)
        if (!withContext(cpu) { HashPin.verificar(actual, guardado) }) {
            return falla(ErrorIdentidad.Validacion(mapOf("pinActual" to "El PIN actual no coincide")))
        }
        val e = Validaciones.validarPin(nuevo, confirmacion)
        if (e.isNotEmpty()) return falla(ErrorIdentidad.Validacion(e))
        val coaccion = almacen.leerHashPinCoaccion()
        if (coaccion != null && withContext(cpu) { HashPin.verificar(nuevo, coaccion) }) {
            return falla(ErrorIdentidad.Validacion(mapOf(Validaciones.PIN to "Debe ser distinto de tu PIN de coacción")))
        }
        almacen.guardarHashPin(withContext(cpu) { HashPin.generar(nuevo) })
        return exito(Unit)
    }

    /** Solicitar baja (A-11): requiere contraseña; la cuenta pasa a BAJA_SOLICITADA y se cierra la sesión local. */
    suspend fun solicitarBaja(contrasena: String): Resultado<Unit> {
        if (contrasena.isEmpty()) {
            return falla(ErrorIdentidad.Validacion(mapOf(Validaciones.CONTRASENA to "Escribe tu contraseña")))
        }
        val r = remoto.solicitarBaja(contrasena)
        if (r is Resultado.Exito) limpiarDatosDeSesion()
        return r
    }

    /** Cierra la sesión en el servidor (si hay señal) y borra token, PIN y contactos del teléfono. */
    suspend fun cerrarSesion() {
        remoto.cerrarSesion() // mejor esfuerzo: si no hay señal, el token caduca solo
        limpiarDatosDeSesion()
    }

    // ------------------------------------------------------------------ internos

    suspend fun infoDispositivo(): InfoDispositivo {
        val id = config.leer(ClavesConfig.DISPOSITIVO_ID) ?: UUID.randomUUID().toString().also {
            config.escribir(ClavesConfig.DISPOSITIVO_ID, it)
        }
        return InfoDispositivo(id, hardware.modelo(), hardware.androidApi(), hardware.soportaWifiAware())
    }

    private suspend fun guardarSesion(s: RespuestaSesion) {
        almacen.guardarToken(s.token, s.expiraEnMs)
        config.escribir(ClavesConfig.USUARIO_ID, s.usuario.id)
        config.escribir(ClavesConfig.PERFIL, s.usuario.rol.name)
        config.escribir(ClavesConfig.ACEPTA_ENTRENAMIENTO_IA, s.usuario.aceptaEntrenamientoIa.toString())
    }

    private suspend fun borrarConsentimientoPendiente() {
        config.borrar(ClavesConfig.CONSENTIMIENTO_AVISO_ID)
        config.borrar(ClavesConfig.CONSENTIMIENTO_AVISO_VERSION)
        config.borrar(ClavesConfig.CONSENTIMIENTO_ACEPTADO_EN)
    }

    private suspend fun limpiarDatosDeSesion() {
        almacen.borrarTodo()
        contactosLocal.reemplazarTodos(emptyList())
        listOf(
            ClavesConfig.USUARIO_ID, ClavesConfig.PERFIL, ClavesConfig.CIRCULO_ID,
            ClavesConfig.CONTACTOS_PENDIENTES, ClavesConfig.CONTACTOS_ELIMINADOS_PENDIENTES,
            ClavesConfig.ACEPTA_ENTRENAMIENTO_IA
        ).forEach { config.borrar(it) }
    }
}
