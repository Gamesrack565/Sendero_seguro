package com.senderoseguro.server.redapoyo

import kotlinx.serialization.Serializable

// Contrato del Módulo 1 (P2): docs/modulo1/modulo1_contrato_api.md, con los nombres en camelCase como el resto del servidor.

/** Contacto de confianza, como lo responde el servidor. */
@Serializable
data class Contacto(
    val id: String,
    val nombre: String,
    val telefono: String,
    val prioridad: Int,
    val canalPreferido: String,
    val usuarioVinculadoId: String?,
    val activo: Boolean,
)

/**
 * Lo que manda la app al guardar. El `id` lo genera el teléfono. El servidor decide `usuarioVinculadoId` (si el
 * teléfono es de una cuenta) y `canalPreferido` (PUSH si hay cuenta vinculada); si la app los manda, se ignoran.
 */
@Serializable
data class ContactoEntrada(val id: String, val nombre: String, val telefono: String, val prioridad: Int)

@Serializable
data class OrdenContactos(val ids: List<String>)

/** `codigoInvitacion` y `codigoVenceEn` solo le llegan al coordinador; a los integrantes, null. */
@Serializable
data class Circulo(
    val id: String,
    val nombreGrupo: String,
    val escuelaId: Int,
    val coordinadorId: String,
    val codigoInvitacion: String? = null,
    val estadoCodigo: String,
    val codigoVenceEn: String? = null,
)

@Serializable
data class MiCirculo(val circulo: Circulo?)

@Serializable
data class CrearCirculo(val id: String, val nombreGrupo: String)

@Serializable
data class Unirse(val codigo: String)

@Serializable
data class Miembro(val usuarioId: String, val nombre: String, val estado: String)

/** `tipo`: CONTACTO_AGREGADO (con `contactoId`) o NUEVO_INTEGRANTE (con `circuloId`). */
@Serializable
data class AvisoVinculacion(val tipo: String, val contactoId: String? = null, val circuloId: String? = null)
