package com.senderoseguro.server.administracion

import kotlinx.serialization.Serializable

/** Una cuenta en la búsqueda de Usuarios y perfiles (W-09). */
@Serializable
data class UsuarioAdmin(
    val id: String,
    val nombre: String,
    val identificador: String,
    val telefono: String,
    val escuela: String,
    val rol: String,
    val estado: String,
    val unidadId: String? = null,
    val unidad: String? = null,
)

/** CU-07: nuevo perfil. `unidadId` es obligatoria si el perfil es OPERADOR. */
@Serializable
data class CambioPerfil(val rol: String, val unidadId: String? = null, val motivo: String)

/** W-09: desbloquear (ACTIVA) o suspender (SUSPENDIDA) una cuenta. */
@Serializable
data class CambioEstadoCuenta(val estado: String, val motivo: String)

@Serializable
data class Corporacion(
    val id: Int,
    val nombre: String,
    val tipo: String,
    val canalCodigo: String,
    val activa: Boolean,
    val unidades: Int,
)

@Serializable
data class CorporacionEntrada(val nombre: String, val tipo: String, val canalCodigo: String)

@Serializable
data class CorporacionCambio(val nombre: String? = null, val tipo: String? = null, val activa: Boolean? = null)

@Serializable
data class OperadorUnidad(val usuarioId: String, val nombre: String, val activo: Boolean)

/** Una unidad de atención (W-08). `tipos` son los códigos del catálogo, por ejemplo ASALTO_ROBO. */
@Serializable
data class Unidad(
    val id: String,
    val corporacionId: Int,
    val corporacion: String,
    val nombre: String,
    val estado: String,
    val activa: Boolean,
    val tipos: List<String>,
    val latitud: Double? = null,
    val longitud: Double? = null,
    val ubicacionEn: String? = null,
    val operadores: List<OperadorUnidad> = emptyList(),
)

@Serializable
data class UnidadEntrada(val corporacionId: Int, val nombre: String, val tipos: List<String>)

/** Cambios de la unidad. `activa: false` es la baja (CU-10 2a): deja de recibir asignaciones. */
@Serializable
data class UnidadCambio(val nombre: String? = null, val tipos: List<String>? = null, val activa: Boolean? = null)

@Serializable
data class OperadoresEntrada(val operadores: List<String>)

/** El operador abre o cierra su turno y manda la ubicación de su unidad. */
@Serializable
data class TurnoCambio(val estado: String? = null, val latitud: Double? = null, val longitud: Double? = null)
