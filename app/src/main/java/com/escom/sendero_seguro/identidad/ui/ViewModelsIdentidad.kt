package com.escom.sendero_seguro.identidad.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.escom.sendero_seguro.identidad.ModuloIdentidad
import com.escom.sendero_seguro.identidad.dominio.AvisoPrivacidad
import com.escom.sendero_seguro.identidad.dominio.CancelacionMotivo
import com.escom.sendero_seguro.identidad.dominio.Circulo
import com.escom.sendero_seguro.identidad.dominio.ConfigActivacion
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.Escuela
import com.escom.sendero_seguro.identidad.dominio.Institucion
import com.escom.sendero_seguro.identidad.dominio.Miembro
import com.escom.sendero_seguro.identidad.dominio.PoliticaEscaneo
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.ResultadoCancelacion
import com.escom.sendero_seguro.identidad.dominio.SesionLocal
import com.escom.sendero_seguro.identidad.dominio.Usuario
import com.escom.sendero_seguro.identidad.procesos.ProcesoActivacionEnergia
import com.escom.sendero_seguro.identidad.procesos.ProcesoAutenticacion
import com.escom.sendero_seguro.identidad.procesos.ProcesoCancelacion
import com.escom.sendero_seguro.identidad.procesos.ProcesoRedApoyo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * ViewModels que consume el equipo de Frontend. Cada pantalla observa un StateFlow
 * y llama funciones; toda la lógica vive en los procesos.
 *
 *   val m = (LocalContext.current.applicationContext as SenderoApp).identidad
 *   val vm: AutenticacionViewModel = viewModel(factory = m.fabricaViewModels())
 */

fun ModuloIdentidad.fabricaViewModels(): ViewModelProvider.Factory = viewModelFactory {
    initializer { AutenticacionViewModel(autenticacion) }
    initializer { RedApoyoViewModel(redApoyo) }
    initializer { ActivacionViewModel(activacion) }
    initializer { CancelacionViewModel(cancelacion) }
}

/** Estado común: errores por campo (para pintarlos junto al control) o un mensaje general. */
data class Retro(
    val cargando: Boolean = false,
    val errores: Map<String, String> = emptyMap(),
    val mensaje: String? = null
) {
    companion object {
        fun de(e: ErrorIdentidad) =
            if (e is ErrorIdentidad.Validacion) Retro(errores = e.errores) else Retro(mensaje = e.mensaje)
    }
}

// =============================================================== P1 · A-01, A-02, A-03, A-11

data class EstadoAutenticacion(
    val aviso: AvisoPrivacidad? = null,
    val avisoAceptado: Boolean = false,
    val instituciones: List<Institucion> = emptyList(),
    val escuelas: List<Escuela> = emptyList(),
    val sesion: SesionLocal? = null,
    val usuario: Usuario? = null,
    val requiereCrearPin: Boolean = false,
    /** A-01 «No acepto»: la UI cierra la app. */
    val salirDeLaApp: Boolean = false,
    val retro: Retro = Retro()
)

class AutenticacionViewModel(private val p1: ProcesoAutenticacion) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoAutenticacion())
    val estado: StateFlow<EstadoAutenticacion> = _estado.asStateFlow()

    init {
        viewModelScope.launch {
            _estado.update { it.copy(sesion = p1.sesionLocal(), avisoAceptado = p1.consentimientoPendiente() != null) }
        }
    }

    fun cargarAviso() = ejecutar(p1::cargarAvisoVigente) { s, a -> s.copy(aviso = a) }

    fun responderAviso(aceptado: Boolean, aceptaEntrenamientoIa: Boolean) {
        val aviso = _estado.value.aviso ?: return
        ejecutar({ p1.registrarConsentimientoPrivacidad(aviso, aceptado, aceptaEntrenamientoIa) }) { s, _ ->
            s.copy(avisoAceptado = true)
        }
        if (!aceptado) _estado.update { it.copy(salirDeLaApp = true) }
    }

    fun cargarInstituciones() = ejecutar(p1::instituciones) { s, l -> s.copy(instituciones = l) }

    fun seleccionarInstitucion(id: Int) = ejecutar({ p1.escuelas(id) }) { s, l -> s.copy(escuelas = l) }

    /** Paso 1 de A-02: true si se puede continuar a crear el PIN. */
    fun validarPasoDatos(datos: DatosRegistro): Boolean {
        val e = p1.validarPasoDatos(datos)
        _estado.update { it.copy(retro = Retro(errores = e)) }
        return e.isEmpty()
    }

    fun registrar(datos: DatosRegistro, pin: String, pinConfirmacion: String) =
        ejecutar({ p1.registrarCuentaPolitecnica(datos, pin, pinConfirmacion) }) { s, u ->
            s.copy(usuario = u, requiereCrearPin = false)
        }

    fun iniciarSesion(usuario: String, contrasena: String) =
        ejecutar({ p1.autenticarPorRolYControlarIntentos(usuario, contrasena) }) { s, r ->
            s.copy(usuario = r.usuario, requiereCrearPin = r.requiereCrearPin)
        }

    fun crearPin(pin: String, confirmacion: String) =
        ejecutar({ p1.crearPinCancelacion(pin, confirmacion) }) { s, _ -> s.copy(requiereCrearPin = false) }

    fun cargarPerfil() = ejecutar(p1::perfil) { s, u -> s.copy(usuario = u) }

    fun actualizarPerfil(telefono: String?, matricula: String?, carrera: String?, aceptaIa: Boolean?) =
        ejecutar({ p1.actualizarPerfil(telefono, matricula, carrera, aceptaIa) }) { s, u -> s.copy(usuario = u) }

    fun cambiarPin(actual: String, nuevo: String, confirmacion: String) =
        ejecutar({ p1.cambiarPin(actual, nuevo, confirmacion) }) { s, _ ->
            s.copy(retro = Retro(mensaje = "PIN actualizado"))
        }

    fun solicitarBaja(contrasena: String) =
        ejecutar({ p1.solicitarBaja(contrasena) }) { s, _ -> EstadoAutenticacion(retro = s.retro) }

    fun cerrarSesion() {
        viewModelScope.launch {
            p1.cerrarSesion()
            _estado.value = EstadoAutenticacion()
        }
    }

    fun limpiarRetro() = _estado.update { it.copy(retro = Retro()) }

    private fun <T> ejecutar(
        accion: suspend () -> Resultado<T>,
        alExito: (EstadoAutenticacion, T) -> EstadoAutenticacion
    ) {
        viewModelScope.launch {
            _estado.update { it.copy(retro = Retro(cargando = true)) }
            when (val r = accion()) {
                is Resultado.Exito -> _estado.update { alExito(it.copy(retro = Retro()), r.valor) }
                is Resultado.Error -> _estado.update { it.copy(retro = Retro.de(r.error)) }
            }
            _estado.update { it.copy(sesion = p1.sesionLocal()) }
        }
    }
}

// =============================================================== P2 · A-09, A-10

data class EstadoRedApoyo(
    val circulo: Circulo? = null,
    val miembros: List<Miembro> = emptyList(),
    val retro: Retro = Retro()
)

class RedApoyoViewModel(private val p2: ProcesoRedApoyo) : ViewModel() {
    /** Lista ordenada por prioridad; sale de Room, así que funciona sin conexión. */
    val contactos: StateFlow<List<Contacto>> =
        p2.observarContactos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _estado = MutableStateFlow(EstadoRedApoyo())
    val estado: StateFlow<EstadoRedApoyo> = _estado.asStateFlow()

    fun sincronizar() = ejecutar({ p2.sincronizarContactos() }) { s, _ -> s }

    fun agregarContacto(nombre: String, telefono: String, prioridad: Int?) =
        ejecutar({ p2.agregarContacto(nombre, telefono, prioridad) }) { s, c ->
            s.copy(retro = Retro(mensaje = if (c.pendienteSincronizar) "Guardado. Se enviará al recuperar señal." else null))
        }

    fun editarContacto(id: String, nombre: String, telefono: String) =
        ejecutar({ p2.editarContacto(id, nombre, telefono) }) { s, _ -> s }

    fun eliminarContacto(id: String) = ejecutar({ p2.eliminarContacto(id) }) { s, _ -> s }

    fun reordenar(idsEnOrden: List<String>) = ejecutar({ p2.reordenarContactos(idsEnOrden) }) { s, _ -> s }

    fun cargarCirculo() {
        viewModelScope.launch {
            when (val r = p2.cargarMiCirculo()) {
                is Resultado.Exito -> {
                    _estado.update { it.copy(circulo = r.valor) }
                    if (r.valor != null) cargarMiembros()
                }
                is Resultado.Error -> _estado.update { it.copy(retro = Retro.de(r.error)) }
            }
        }
    }

    fun crearCirculo(nombreGrupo: String) =
        ejecutar({ p2.crearCirculo(nombreGrupo) }, despues = ::cargarMiembros) { s, c -> s.copy(circulo = c) }

    fun unirse(codigo: String) =
        ejecutar({ p2.unirseConCodigo(codigo) }, despues = ::cargarMiembros) { s, c -> s.copy(circulo = c) }

    fun regenerarCodigo() = ejecutar({ p2.regenerarCodigo() }) { s, c -> s.copy(circulo = c) }

    fun cargarMiembros() = ejecutar({ p2.cargarMiembros() }) { s, l -> s.copy(miembros = l) }

    fun darDeBaja(usuarioId: String) = ejecutar({ p2.darDeBajaIntegrante(usuarioId) }) { s, l -> s.copy(miembros = l) }

    fun retirarme() = ejecutar({ p2.retirarme() }) { _, _ -> EstadoRedApoyo() }

    private fun <T> ejecutar(
        accion: suspend () -> Resultado<T>,
        despues: (() -> Unit)? = null,
        alExito: (EstadoRedApoyo, T) -> EstadoRedApoyo
    ) {
        viewModelScope.launch {
            _estado.update { it.copy(retro = Retro(cargando = true)) }
            when (val r = accion()) {
                is Resultado.Exito -> {
                    _estado.update { alExito(it.copy(retro = Retro()), r.valor) }
                    despues?.invoke()
                }
                is Resultado.Error -> _estado.update { it.copy(retro = Retro.de(r.error)) }
            }
        }
    }
}

// =============================================================== P3 · A-12

class ActivacionViewModel(private val p3: ProcesoActivacionEnergia) : ViewModel() {
    val config: StateFlow<ConfigActivacion> =
        p3.observarConfig().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConfigActivacion())

    /** «Frecuencia de escaneo actual» de A-12. */
    val politica: StateFlow<PoliticaEscaneo?> =
        p3.ajustarEscaneoMallaPorBateria().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Mostrar el aviso «Si activas el ahorro de energía de Android…» y lanzar GestorEnergiaAndroid.intentSolicitarExclusion(). */
    fun requiereExclusionAhorroEnergia(): Boolean = p3.requiereExclusionAhorroEnergia()

    fun atajoBotonEncendido(activo: Boolean) = viewModelScope.launch { p3.configurarAtajosSinDesbloquear(atajoActivo = activo) }
    fun widget(activo: Boolean) = viewModelScope.launch { p3.configurarAtajosSinDesbloquear(widgetActivo = activo) }
    fun retransmitirMalla(activo: Boolean) = viewModelScope.launch { p3.configurarRedMalla(retransmitir = activo) }
    fun escaneoSegunBateria(activo: Boolean) = viewModelScope.launch { p3.configurarRedMalla(ahorroBateria = activo) }
    fun mapasSoloWifi(activo: Boolean) = viewModelScope.launch { p3.configurarMapasSoloWifi(activo) }

    fun cuentaRegresiva(segundos: Int) = viewModelScope.launch {
        val r = p3.definirCuentaRegresivaPreEnvio(segundos)
        _error.value = (r as? Resultado.Error)?.error?.mensaje
    }
}

// =============================================================== P8 · A-08 (y A-05)

data class EstadoCancelacion(
    val motivo: CancelacionMotivo? = null,
    val procesando: Boolean = false,
    val resultado: ResultadoCancelacion? = null,
    val retro: Retro = Retro()
)

class CancelacionViewModel(private val p8: ProcesoCancelacion) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoCancelacion())
    val estado: StateFlow<EstadoCancelacion> = _estado.asStateFlow()

    val motivos: List<CancelacionMotivo> = p8.motivosDisponibles()

    fun elegirMotivo(m: CancelacionMotivo) = _estado.update { it.copy(motivo = m, retro = Retro()) }

    fun confirmarConPin(alertaId: String, pin: String, alertaYaEnviada: Boolean = true) =
        procesar { p8.cancelarConPin(alertaId, _estado.value.motivo, pin, alertaYaEnviada) }

    /** Llamar después de AutenticadorBiometrico.autenticar(actividad). */
    fun confirmarConBiometria(alertaId: String, autenticada: Boolean, alertaYaEnviada: Boolean = true) =
        procesar { p8.cancelarConBiometria(alertaId, _estado.value.motivo, autenticada, alertaYaEnviada) }

    private fun procesar(accion: suspend () -> Resultado<ResultadoCancelacion>) {
        if (_estado.value.procesando) return
        viewModelScope.launch {
            _estado.update { it.copy(procesando = true, retro = Retro()) }
            when (val r = accion()) {
                is Resultado.Exito -> _estado.update { it.copy(procesando = false, resultado = r.valor) }
                is Resultado.Error -> _estado.update { it.copy(procesando = false, retro = Retro.de(r.error)) }
            }
        }
    }
}
