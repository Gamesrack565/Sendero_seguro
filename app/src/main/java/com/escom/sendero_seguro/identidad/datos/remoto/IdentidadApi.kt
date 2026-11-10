package com.escom.sendero_seguro.identidad.datos.remoto

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Endpoints del backend (Ktor) que consume el Módulo 1. Nombres de campos en camelCase, como el resto
 * del servidor (docs/api/openapi.yaml). Fechas en ISO-8601 (timestamptz).
 * Contrato completo: docs/modulo1_contrato_api.md
 */
interface IdentidadApi {
    // ---------- P1
    @GET("avisos-privacidad/vigente") suspend fun avisoVigente(): AvisoDto
    @GET("instituciones") suspend fun instituciones(): List<InstitucionDto>
    @GET("instituciones/{id}/escuelas") suspend fun escuelas(@Path("id") institucionId: Int): List<EscuelaDto>
    @POST("auth/registro") suspend fun registrar(@Body body: RegistroDto): SesionDto
    @POST("auth/login") suspend fun iniciarSesion(@Body body: LoginDto): SesionDto
    @POST("auth/logout") suspend fun cerrarSesion()
    @GET("usuarios/me") suspend fun perfil(): UsuarioDto
    @PATCH("usuarios/me") suspend fun actualizarPerfil(@Body body: PerfilCambioDto): UsuarioDto
    @POST("usuarios/me/baja") suspend fun solicitarBaja(@Body body: BajaDto)

    // ---------- P2
    @GET("contactos") suspend fun listarContactos(): List<ContactoDto>
    @PUT("contactos/{id}") suspend fun guardarContacto(@Path("id") id: String, @Body body: ContactoDto): ContactoDto
    @DELETE("contactos/{id}") suspend fun eliminarContacto(@Path("id") id: String)
    @PUT("contactos/orden") suspend fun reordenarContactos(@Body body: OrdenDto): List<ContactoDto>
    @GET("circulos/mio") suspend fun miCirculo(): CirculoRespuestaDto
    @POST("circulos") suspend fun crearCirculo(@Body body: CrearCirculoDto): CirculoDto
    @POST("circulos/unirse") suspend fun unirse(@Body body: UnirseDto): CirculoDto
    @POST("circulos/{id}/codigo") suspend fun regenerarCodigo(@Path("id") id: String): CirculoDto
    @GET("circulos/{id}/miembros") suspend fun miembros(@Path("id") id: String): List<MiembroDto>
    @DELETE("circulos/{id}/miembros/{usuarioId}") suspend fun darDeBaja(@Path("id") id: String, @Path("usuarioId") usuarioId: String)
    @POST("circulos/{id}/retirarme") suspend fun retirarse(@Path("id") id: String)
    @POST("red-apoyo/avisos") suspend fun notificarVinculacion(@Body body: AvisoVinculacionDto)

    // ---------- P8
    @POST("alertas/{id}/cancelaciones") suspend fun registrarCancelacion(@Path("id") alertaId: String, @Body body: CancelacionDto)
}

@Serializable data class AvisoDto(val id: Int, val version: String, val contenidoHash: String)
@Serializable data class InstitucionDto(val id: Int, val nombre: String, val siglas: String? = null)
@Serializable data class EscuelaDto(val id: Int, val institucionId: Int, val nombre: String)

@Serializable
data class DispositivoDto(
    val id: String,
    val modelo: String,
    val androidApi: Int,
    val soportaWifiAware: Boolean
)

@Serializable
data class ConsentimientoDto(
    val avisoId: Int,
    val aceptadoEn: String
)

@Serializable
data class RegistroDto(
    val nombre: String,
    val telefonoMovil: String,
    val identificadorInstitucional: String,
    val tipoComunidad: String,
    val escuelaId: Int,
    val carreraAdscripcion: String,
    val contrasena: String,
    val aceptaEntrenamientoIa: Boolean,
    val consentimiento: ConsentimientoDto,
    val dispositivo: DispositivoDto
)

@Serializable
data class LoginDto(val usuario: String, val contrasena: String, val dispositivo: DispositivoDto)

@Serializable
data class UsuarioDto(
    val id: String,
    val nombre: String,
    val telefonoMovil: String,
    val identificadorInstitucional: String,
    val escuelaId: Int,
    val carreraAdscripcion: String? = null,
    val rol: String,
    val estado: String,
    val aceptaEntrenamientoIa: Boolean = false
)

@Serializable
data class SesionDto(val token: String, val expiraEn: String, val usuario: UsuarioDto)

@Serializable
data class PerfilCambioDto(
    val telefonoMovil: String? = null,
    val identificadorInstitucional: String? = null,
    val carreraAdscripcion: String? = null,
    val aceptaEntrenamientoIa: Boolean? = null
)

@Serializable data class BajaDto(val contrasena: String)

@Serializable
data class ContactoDto(
    val id: String,
    val nombre: String,
    val telefono: String,
    val prioridad: Int,
    val canalPreferido: String = "SMS",
    val usuarioVinculadoId: String? = null,
    val activo: Boolean = true
)

@Serializable data class OrdenDto(val ids: List<String>)

@Serializable
data class CirculoDto(
    val id: String,
    val nombreGrupo: String,
    val escuelaId: Int,
    val coordinadorId: String,
    val codigoInvitacion: String? = null,
    val estadoCodigo: String = "VIGENTE",
    val codigoVenceEn: String? = null
)

/** GET circulos/mio responde {"circulo": null} si la persona no pertenece a ninguno. */
@Serializable data class CirculoRespuestaDto(val circulo: CirculoDto? = null)

@Serializable data class CrearCirculoDto(val id: String, val nombreGrupo: String)
@Serializable data class UnirseDto(val codigo: String)

@Serializable
data class MiembroDto(val usuarioId: String, val nombre: String, val estado: String)

@Serializable
data class AvisoVinculacionDto(
    val tipo: String, // CONTACTO_AGREGADO | NUEVO_INTEGRANTE (notificacion_tipo)
    val contactoId: String? = null,
    val circuloId: String? = null
)

@Serializable
data class CancelacionDto(
    val dispositivoId: String?,
    val motivo: String?,
    val metodo: String,
    val exitoso: Boolean,
    val intentadoEn: String
)

/**
 * Error del servidor: {"codigo", "mensaje", "detalle"}. Los datos extra de cada error vienen dentro de `detalle`
 * (errores por campo, intentos restantes, fin del bloqueo, rol).
 */
@Serializable
data class ErrorDto(
    val codigo: String = "",
    val mensaje: String = "",
    val detalle: DetalleErrorDto? = null
)

@Serializable
data class DetalleErrorDto(
    val errores: Map<String, String> = emptyMap(),
    val intentosRestantes: Int? = null,
    val bloqueadaHasta: String? = null,
    val rol: String? = null
)
