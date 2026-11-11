package com.escom.sendero_seguro.identidad

import com.escom.sendero_seguro.identidad.dominio.AvisoPrivacidad
import com.escom.sendero_seguro.identidad.dominio.AvisoVinculacion
import com.escom.sendero_seguro.identidad.dominio.Circulo
import com.escom.sendero_seguro.identidad.dominio.CodigoEstado
import com.escom.sendero_seguro.identidad.dominio.ConsentimientoPendiente
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.Escuela
import com.escom.sendero_seguro.identidad.dominio.EstadoBateria
import com.escom.sendero_seguro.identidad.dominio.InfoDispositivo
import com.escom.sendero_seguro.identidad.dominio.Institucion
import com.escom.sendero_seguro.identidad.dominio.Miembro
import com.escom.sendero_seguro.identidad.dominio.RespuestaSesion
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.SolicitudCancelacion
import com.escom.sendero_seguro.identidad.dominio.TokenGuardado
import com.escom.sendero_seguro.identidad.dominio.Usuario
import com.escom.sendero_seguro.identidad.dominio.UsuarioEstado
import com.escom.sendero_seguro.identidad.dominio.UsuarioRol
import com.escom.sendero_seguro.identidad.dominio.exito
import com.escom.sendero_seguro.identidad.dominio.falla
import com.escom.sendero_seguro.identidad.puertos.AlmacenSeguro
import com.escom.sendero_seguro.identidad.puertos.ColaCancelaciones
import com.escom.sendero_seguro.identidad.puertos.FuenteBateria
import com.escom.sendero_seguro.identidad.puertos.GestorEnergia
import com.escom.sendero_seguro.identidad.puertos.InfoHardware
import com.escom.sendero_seguro.identidad.puertos.RepositorioConfigLocal
import com.escom.sendero_seguro.identidad.puertos.RepositorioContactosLocal
import com.escom.sendero_seguro.identidad.puertos.ServicioRemotoIdentidad
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class ConfigEnMemoria : RepositorioConfigLocal {
    val datos = MutableStateFlow<Map<String, String>>(emptyMap())
    override suspend fun leer(clave: String) = datos.value[clave]
    override suspend fun escribir(clave: String, valor: String) { datos.value = datos.value + (clave to valor) }
    override suspend fun borrar(clave: String) { datos.value = datos.value - clave }
    override fun observarTodo(): Flow<Map<String, String>> = datos
}

class ContactosEnMemoria : RepositorioContactosLocal {
    private val datos = MutableStateFlow<Map<String, Contacto>>(emptyMap())
    override fun observar() = datos.map { it.values.sortedBy { c -> c.prioridad } }
    override suspend fun listar() = datos.value.values.sortedBy { it.prioridad }
    override suspend fun guardar(contacto: Contacto) { datos.value = datos.value + (contacto.id to contacto) }
    override suspend fun guardarTodos(contactos: List<Contacto>) { datos.value = datos.value + contactos.associateBy { it.id } }
    override suspend fun reemplazarTodos(contactos: List<Contacto>) { datos.value = contactos.associateBy { it.id } }
    override suspend fun eliminar(id: String) { datos.value = datos.value - id }
}

class AlmacenEnMemoria : AlmacenSeguro {
    var token: TokenGuardado? = null
    var hashPin: String? = null
    override fun guardarToken(token: String, expiraEnMs: Long) { this.token = TokenGuardado(token, expiraEnMs) }
    override fun leerToken() = token
    override fun borrarToken() { token = null }
    override fun guardarHashPin(hash: String) { hashPin = hash }
    override fun leerHashPin() = hashPin
    var hashPinCoaccion: String? = null
    override fun guardarHashPinCoaccion(hash: String) { hashPinCoaccion = hash }
    override fun leerHashPinCoaccion() = hashPinCoaccion
    override fun borrarHashPinCoaccion() { hashPinCoaccion = null }
    override fun borrarTodo() { token = null; hashPin = null; hashPinCoaccion = null }
}

class ColaEnMemoria : ColaCancelaciones {
    val encoladas = mutableListOf<SolicitudCancelacion>()
    override suspend fun encolar(solicitud: SolicitudCancelacion) { encoladas += solicitud }
    override suspend fun pendientes() = encoladas.toList()
    override suspend fun quitar(alertaId: String) { encoladas.removeAll { it.alertaId == alertaId } }
}

object HardwareFalso : InfoHardware {
    override fun modelo() = "Pixel de prueba"
    override fun androidApi() = 34
    override fun soportaWifiAware() = true
}

class BateriaFalsa(inicial: EstadoBateria) : FuenteBateria {
    val estado = MutableStateFlow(inicial)
    override fun observar(): Flow<EstadoBateria> = estado
}

class EnergiaFalsa(var exento: Boolean) : GestorEnergia {
    override fun estaExentoDeOptimizacion() = exento
}

fun usuarioDePrueba(rol: UsuarioRol = UsuarioRol.INTEGRANTE) = Usuario(
    id = "u-1", nombre = "Mariana López Hernández", telefonoMovil = "5512345678",
    identificadorInstitucional = "2023630417", escuelaId = 1, carreraAdscripcion = "IIA",
    rol = rol, estado = UsuarioEstado.ACTIVA, aceptaEntrenamientoIa = true
)

/** Backend falso: guarda lo que recibe y permite simular falta de señal o errores. */
class RemotoFalso : ServicioRemotoIdentidad {
    var sinConexion = false
    var rolLogin = UsuarioRol.INTEGRANTE
    var errorLogin: ErrorIdentidad? = null
    var errorCancelacion: ErrorIdentidad? = null
    var ultimoRegistro: Pair<DatosRegistro, ConsentimientoPendiente>? = null
    val contactos = mutableMapOf<String, Contacto>()
    var ordenRecibido: List<String> = emptyList()
    val cancelaciones = mutableListOf<SolicitudCancelacion>()
    val avisos = mutableListOf<AvisoVinculacion>()
    var circulo: Circulo? = null
    var sesionCerrada = false

    private fun <T> resp(f: () -> Resultado<T>): Resultado<T> = if (sinConexion) falla(ErrorIdentidad.SinConexion) else f()

    override suspend fun avisoVigente() = resp { exito(AvisoPrivacidad(3, "v3.1", "a".repeat(64))) }
    override suspend fun instituciones() = resp { exito(listOf(Institucion(1, "Instituto Politécnico Nacional", "IPN"))) }
    override suspend fun escuelas(institucionId: Int) = resp { exito(listOf(Escuela(1, 1, "ESCOM"))) }
    override suspend fun registrar(datos: DatosRegistro, consentimiento: ConsentimientoPendiente, dispositivo: InfoDispositivo) =
        resp { ultimoRegistro = datos to consentimiento; exito(RespuestaSesion(usuarioDePrueba(), "tok", Long.MAX_VALUE)) }
    override suspend fun iniciarSesion(usuario: String, contrasena: String, dispositivo: InfoDispositivo) = resp {
        errorLogin?.let { falla(it) } ?: exito(RespuestaSesion(usuarioDePrueba(rolLogin), "tok", Long.MAX_VALUE))
    }
    override suspend fun cerrarSesion() = resp { sesionCerrada = true; exito(Unit) }
    override suspend fun perfil() = resp { exito(usuarioDePrueba()) }
    override suspend fun actualizarPerfil(telefonoMovil: String?, identificadorInstitucional: String?, carreraAdscripcion: String?, aceptaEntrenamientoIa: Boolean?) =
        resp { exito(usuarioDePrueba().copy(aceptaEntrenamientoIa = aceptaEntrenamientoIa ?: true)) }
    override suspend fun solicitarBaja(contrasena: String) = resp { exito(Unit) }

    override suspend fun listarContactos() = resp { exito(contactos.values.sortedBy { it.prioridad }) }
    override suspend fun guardarContacto(contacto: Contacto) = resp {
        val c = contacto.copy(tieneApp = contacto.telefono.endsWith("0"))
        contactos[c.id] = c; exito(c)
    }
    override suspend fun eliminarContacto(id: String) = resp { contactos.remove(id); exito(Unit) }
    override suspend fun reordenarContactos(idsEnOrden: List<String>) = resp {
        ordenRecibido = idsEnOrden
        idsEnOrden.forEachIndexed { i, id -> contactos[id]?.let { contactos[id] = it.copy(prioridad = i + 1) } }
        exito(contactos.values.sortedBy { it.prioridad })
    }
    override suspend fun miCirculo() = resp { exito(circulo) }
    override suspend fun crearCirculo(id: String, nombreGrupo: String) = resp {
        val c = Circulo(id, nombreGrupo, 1, "u-1", "K7Q-42M", CodigoEstado.VIGENTE, 0L)
        circulo = c; exito(c)
    }
    override suspend fun unirseACirculo(codigo: String) = resp {
        if (codigo == "K7Q-42M") exito(Circulo("c-1", "6IV1", 1, "u-9", null, CodigoEstado.VIGENTE, null))
        else falla(ErrorIdentidad.CodigoInvalido)
    }
    override suspend fun regenerarCodigo(circuloId: String) = resp { exito(circulo!!.copy(codigoInvitacion = "ZZZ-999")) }
    override suspend fun miembros(circuloId: String) = resp { exito(emptyList<Miembro>()) }
    override suspend fun darDeBaja(circuloId: String, usuarioId: String) = resp { exito(Unit) }
    override suspend fun retirarse(circuloId: String) = resp { exito(Unit) }
    override suspend fun notificarVinculacion(aviso: AvisoVinculacion) = resp { avisos += aviso; exito(Unit) }
    override suspend fun registrarCancelacion(solicitud: SolicitudCancelacion) =
        resp { errorCancelacion?.let { falla(it) } ?: run { cancelaciones += solicitud; exito(Unit) } }
}
