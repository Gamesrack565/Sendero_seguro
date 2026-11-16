package com.senderoseguro.server.cuentas

import kotlinx.serialization.Serializable

// Contrato del Módulo 1 (P1): docs/modulo1/modulo1_contrato_api.md, con los nombres en camelCase como el resto del servidor.

@Serializable
data class AvisoPrivacidad(val id: Int, val version: String, val contenidoHash: String)

@Serializable
data class Institucion(val id: Int, val nombre: String, val siglas: String? = null)

@Serializable
data class Escuela(val id: Int, val institucionId: Int, val nombre: String)

/** El teléfono desde el que se registra o inicia sesión. El `id` lo genera la app y no cambia entre sesiones. */
@Serializable
data class DispositivoEntrada(
    val id: String,
    val modelo: String? = null,
    val androidApi: Int? = null,
    val soportaWifiAware: Boolean = false,
)

@Serializable
data class ConsentimientoEntrada(val avisoId: Int, val aceptadoEn: String? = null)

@Serializable
data class RegistroEntrada(
    val nombre: String,
    val telefonoMovil: String,
    val identificadorInstitucional: String,
    val tipoComunidad: String,
    val escuelaId: Int,
    val carreraAdscripcion: String? = null,
    val contrasena: String,
    val aceptaEntrenamientoIa: Boolean = false,
    val consentimiento: ConsentimientoEntrada,
    val dispositivo: DispositivoEntrada,
)

/** `usuario` es el teléfono (10 dígitos) o la matrícula. Sin `dispositivo` es la consola web. */
@Serializable
data class LoginEntrada(val usuario: String, val contrasena: String, val dispositivo: DispositivoEntrada? = null)

@Serializable
data class UsuarioSalida(
    val id: String,
    val nombre: String,
    val telefonoMovil: String,
    val identificadorInstitucional: String,
    val escuelaId: Int,
    val carreraAdscripcion: String? = null,
    val rol: String,
    val estado: String,
    val aceptaEntrenamientoIa: Boolean,
)

@Serializable
data class Sesion(val token: String, val expiraEn: String, val usuario: UsuarioSalida)

@Serializable
data class PerfilCambio(
    val telefonoMovil: String? = null,
    val identificadorInstitucional: String? = null,
    val carreraAdscripcion: String? = null,
    val aceptaEntrenamientoIa: Boolean? = null,
)

@Serializable
data class BajaEntrada(val contrasena: String)
