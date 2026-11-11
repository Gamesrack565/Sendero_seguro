package com.escom.sendero_seguro.identidad.puertos

import com.escom.sendero_seguro.identidad.dominio.AvisoPrivacidad
import com.escom.sendero_seguro.identidad.dominio.AvisoVinculacion
import com.escom.sendero_seguro.identidad.dominio.Circulo
import com.escom.sendero_seguro.identidad.dominio.ConsentimientoPendiente
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
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
import kotlinx.coroutines.flow.Flow

/*
 * Los procesos (P1, P2, P3, P8) solo dependen de estas interfaces.
 * Las implementaciones Android (Room, Keystore, Retrofit, BatteryManager) están en datos/ y plataforma/;
 * en las pruebas se sustituyen por dobles en memoria.
 */

/** Claves de la tabla config_local (ER-06). Las marcadas «extensión» no están en el diccionario del E3. */
object ClavesConfig {
    const val USUARIO_ID = "usuario_id"
    const val PERFIL = "perfil"
    const val CUENTA_REGRESIVA_SEG = "cuenta_regresiva_seg"
    const val ATAJO_ACTIVO = "atajo_activo"
    const val WIDGET_ACTIVO = "widget_activo"
    const val RETRANSMITIR_MALLA = "retransmitir_malla"
    const val AHORRO_BATERIA = "ahorro_bateria"
    const val MAPAS_SOLO_WIFI = "mapas_solo_wifi"

    // Extensiones usadas por el Módulo 1
    const val DISPOSITIVO_ID = "dispositivo_id"
    const val CONSENTIMIENTO_AVISO_ID = "consentimiento_aviso_id"
    const val CONSENTIMIENTO_AVISO_VERSION = "consentimiento_aviso_version"
    const val CONSENTIMIENTO_ACEPTADO_EN = "consentimiento_aceptado_en"
    const val ACEPTA_ENTRENAMIENTO_IA = "acepta_entrenamiento_ia"
    const val BLOQUEADA_HASTA = "bloqueada_hasta"
    const val CIRCULO_ID = "circulo_id"
    const val CONTACTOS_PENDIENTES = "contactos_pendientes"
    const val CONTACTOS_ELIMINADOS_PENDIENTES = "contactos_eliminados_pendientes"
    const val CANCELACIONES_FALLIDAS_PENDIENTES = "cancelaciones_fallidas_pendientes"
}

interface RepositorioConfigLocal {
    suspend fun leer(clave: String): String?
    suspend fun escribir(clave: String, valor: String)
    suspend fun borrar(clave: String)
    fun observarTodo(): Flow<Map<String, String>>
}

interface RepositorioContactosLocal {
    fun observar(): Flow<List<Contacto>>
    suspend fun listar(): List<Contacto>
    suspend fun guardar(contacto: Contacto)
    suspend fun guardarTodos(contactos: List<Contacto>)
    suspend fun reemplazarTodos(contactos: List<Contacto>)
    suspend fun eliminar(id: String)
}

/** Token de sesión y hash del PIN, cifrados con una llave del Android Keystore. */
interface AlmacenSeguro {
    fun guardarToken(token: String, expiraEnMs: Long)
    fun leerToken(): TokenGuardado?
    fun borrarToken()
    fun guardarHashPin(hash: String)
    fun leerHashPin(): String?

    /** PIN de coacción (P3, RF-13): otro PIN que la persona elige; al usarlo la pantalla simula la cancelación. */
    fun guardarHashPinCoaccion(hash: String) {}
    fun leerHashPinCoaccion(): String? = null
    fun borrarHashPinCoaccion() {}
    fun borrarTodo()
}

/**
 * Salida de una cancelación cuando no hay internet. La implementación Room la deja en cola_alerta
 * (tipo_elemento = CANCELACION) para que el despachador multicanal del Módulo 2/3 la envíe.
 */
interface ColaCancelaciones {
    suspend fun encolar(solicitud: SolicitudCancelacion)

    /** Las cancelaciones que siguen en cola y no han vencido. */
    suspend fun pendientes(): List<SolicitudCancelacion> = emptyList()

    /** Quita de la cola la cancelación de esa alerta (ya llegó al servidor o el servidor no la acepta). */
    suspend fun quitar(alertaId: String) {}
}

interface InfoHardware {
    fun modelo(): String
    fun androidApi(): Int
    fun soportaWifiAware(): Boolean
}

/** Estado de la batería del teléfono (BatteryManager en Android). */
interface FuenteBateria {
    fun observar(): Flow<EstadoBateria>
}

/** Exclusión de la optimización de batería de Android (Doze) para no perder alertas de la malla. */
interface GestorEnergia {
    fun estaExentoDeOptimizacion(): Boolean
}

/** API del backend (Ktor) que usa el Módulo 1. El contrato HTTP está en docs/modulo1_contrato_api.md. */
interface ServicioRemotoIdentidad {
    // P1
    suspend fun avisoVigente(): Resultado<AvisoPrivacidad>
    suspend fun instituciones(): Resultado<List<Institucion>>
    suspend fun escuelas(institucionId: Int): Resultado<List<Escuela>>
    suspend fun registrar(
        datos: DatosRegistro,
        consentimiento: ConsentimientoPendiente,
        dispositivo: InfoDispositivo
    ): Resultado<RespuestaSesion>
    suspend fun iniciarSesion(usuario: String, contrasena: String, dispositivo: InfoDispositivo): Resultado<RespuestaSesion>
    suspend fun cerrarSesion(): Resultado<Unit>
    suspend fun perfil(): Resultado<Usuario>
    suspend fun actualizarPerfil(
        telefonoMovil: String?,
        identificadorInstitucional: String?,
        carreraAdscripcion: String?,
        aceptaEntrenamientoIa: Boolean?
    ): Resultado<Usuario>
    suspend fun solicitarBaja(contrasena: String): Resultado<Unit>

    // P2
    suspend fun listarContactos(): Resultado<List<Contacto>>
    suspend fun guardarContacto(contacto: Contacto): Resultado<Contacto>
    suspend fun eliminarContacto(id: String): Resultado<Unit>
    suspend fun reordenarContactos(idsEnOrden: List<String>): Resultado<List<Contacto>>
    suspend fun miCirculo(): Resultado<Circulo?>
    suspend fun crearCirculo(id: String, nombreGrupo: String): Resultado<Circulo>
    suspend fun unirseACirculo(codigo: String): Resultado<Circulo>
    suspend fun regenerarCodigo(circuloId: String): Resultado<Circulo>
    suspend fun miembros(circuloId: String): Resultado<List<Miembro>>
    suspend fun darDeBaja(circuloId: String, usuarioId: String): Resultado<Unit>
    suspend fun retirarse(circuloId: String): Resultado<Unit>
    suspend fun notificarVinculacion(aviso: AvisoVinculacion): Resultado<Unit>

    // P8
    suspend fun registrarCancelacion(solicitud: SolicitudCancelacion): Resultado<Unit>
}
