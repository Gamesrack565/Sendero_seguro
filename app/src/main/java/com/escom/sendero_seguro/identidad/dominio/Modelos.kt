package com.escom.sendero_seguro.identidad.dominio

/*
 * Modelos de dominio del Módulo 1 (P1, P2, P3, P8).
 * Los enumerados replican los tipos de PostgreSQL (sendero_seguro_postgres.sql)
 * para que el JSON del backend se mapee 1:1 por nombre.
 */

enum class UsuarioRol { INTEGRANTE, COORDINADOR, OPERADOR, ADMINISTRADOR }

enum class UsuarioEstado { ACTIVA, BLOQUEADA_TEMPORAL, SUSPENDIDA, BAJA_SOLICITADA, ELIMINADA }

enum class TipoComunidad { ALUMNO, DOCENTE, PERSONAL }

enum class NotificacionCanal { PUSH, SMS }

enum class MiembroEstado { ACTIVA, RETIRADA, DADA_DE_BAJA }

enum class CodigoEstado { VIGENTE, VENCIDO }

enum class MetodoAutenticacion { PIN, BIOMETRIA }

enum class CancelacionMotivo(val etiqueta: String) {
    YA_ESTOY_A_SALVO("Ya estoy a salvo"),
    FALSA_ALARMA("Falsa alarma"),
    ACTIVACION_ACCIDENTAL("La activé por accidente")
}

/** Perfiles que pueden usar la app Android. ADMINISTRADOR solo entra por la consola web (W-01). */
val ROLES_PERMITIDOS_EN_APP = setOf(UsuarioRol.INTEGRANTE, UsuarioRol.COORDINADOR, UsuarioRol.OPERADOR)

// ---------- P1 ----------

data class AvisoPrivacidad(
    val id: Int,
    val version: String,
    val contenidoHash: String
)

/** Aceptación guardada en el teléfono entre A-01 y A-02; se envía junto con el registro. */
data class ConsentimientoPendiente(
    val avisoId: Int,
    val avisoVersion: String,
    val aceptadoEnMs: Long,
    val aceptaEntrenamientoIa: Boolean
)

data class Institucion(val id: Int, val nombre: String, val siglas: String?)

data class Escuela(val id: Int, val institucionId: Int, val nombre: String)

data class DatosRegistro(
    val nombre: String,
    val telefonoMovil: String,
    val identificadorInstitucional: String,
    val tipoComunidad: TipoComunidad,
    val institucionId: Int?,
    val escuelaId: Int?,
    val carreraAdscripcion: String,
    val contrasena: String
)

data class Usuario(
    val id: String,
    val nombre: String,
    val telefonoMovil: String,
    val identificadorInstitucional: String,
    val escuelaId: Int,
    val carreraAdscripcion: String?,
    val rol: UsuarioRol,
    val estado: UsuarioEstado,
    val aceptaEntrenamientoIa: Boolean
)

data class InfoDispositivo(
    val id: String,
    val modelo: String,
    val androidApi: Int,
    val soportaWifiAware: Boolean
)

data class RespuestaSesion(
    val usuario: Usuario,
    val token: String,
    val expiraEnMs: Long
)

data class TokenGuardado(val token: String, val expiraEnMs: Long)

/** Sesión persistente en el teléfono. Sigue siendo válida para el botón de pánico aunque el token haya vencido. */
data class SesionLocal(
    val usuarioId: String,
    val rol: UsuarioRol,
    val tokenVigente: Boolean
)

data class ResultadoAcceso(
    val usuario: Usuario,
    /** true si el teléfono no tiene PIN de cancelación (p. ej. inicio de sesión en un teléfono nuevo). */
    val requiereCrearPin: Boolean
)

// ---------- P2 ----------

data class Contacto(
    val id: String,
    val nombre: String,
    val telefono: String,
    val prioridad: Int,
    val canalPreferido: NotificacionCanal = NotificacionCanal.SMS,
    val tieneApp: Boolean = false,
    /** true mientras el alta o el cambio no ha llegado al servidor. */
    val pendienteSincronizar: Boolean = false
)

data class Circulo(
    val id: String,
    val nombreGrupo: String,
    val escuelaId: Int,
    val coordinadorId: String,
    val codigoInvitacion: String?,
    val estadoCodigo: CodigoEstado,
    val codigoVenceEnMs: Long?
)

data class Miembro(
    val usuarioId: String,
    val nombre: String,
    val estado: MiembroEstado
)

sealed class AvisoVinculacion {
    data class ContactoAgregado(val contactoId: String) : AvisoVinculacion()
    data class NuevoIntegrante(val circuloId: String) : AvisoVinculacion()
}

// ---------- P3 ----------

data class ConfigActivacion(
    val atajoActivo: Boolean = true,
    val widgetActivo: Boolean = true,
    val cuentaRegresivaSeg: Int = 5,
    val retransmitirMalla: Boolean = true,
    val ahorroBateria: Boolean = true,
    val mapasSoloWifi: Boolean = false
)

data class EstadoBateria(val nivelPorcentaje: Int, val cargando: Boolean)

data class PoliticaEscaneo(
    val intervaloMs: Long,
    val retransmitirOtros: Boolean,
    val descripcion: String
)

// ---------- P8 ----------

data class SolicitudCancelacion(
    val alertaId: String,
    val usuarioId: String,
    val dispositivoId: String?,
    val motivo: CancelacionMotivo?,
    val metodo: MetodoAutenticacion,
    val exitoso: Boolean,
    val intentadoEnMs: Long
)

sealed class ResultadoCancelacion {
    data class Cancelada(val alertaId: String, val sincronizada: Boolean) : ResultadoCancelacion()

    /** Mensaje neutro (flujo 3a de CU-21): no revela que el PIN falló a quien vea la pantalla. */
    data class NoCancelada(val mensajeNeutro: String = MENSAJE_NEUTRO) : ResultadoCancelacion()

    companion object {
        const val MENSAJE_NEUTRO = "No fue posible cancelar en este momento. La alerta sigue activa."
    }
}

sealed class EventoCancelacion {
    /** Lo escucha el Módulo 2 para detener la cuenta regresiva, el envío de ubicación y la malla. */
    data class AlertaCancelada(val alertaId: String, val motivo: CancelacionMotivo?) : EventoCancelacion()

    /** Intento fallido: la alerta sigue activa y el backend lo marca como posible coacción. */
    data class IntentoFallido(val alertaId: String) : EventoCancelacion()
}
