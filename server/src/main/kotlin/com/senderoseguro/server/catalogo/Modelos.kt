package com.senderoseguro.server.catalogo

import kotlinx.serialization.Serializable

/** Catálogo vigente que descarga la app (CU-08 paso 5). `versionCatalogoId` y `tipos[].id` van en `POST /alertas`. */
@Serializable
data class CatalogoVigente(
    val versionCatalogoId: Int,
    val numero: Int,
    val publicadaEn: String,
    val tipos: List<TipoCatalogo>,
)

@Serializable
data class TipoCatalogo(
    val id: Int,
    val codigo: String,
    val nombre: String,
    /** FISICO, SOCIAL o DIGITAL; null solo en la genérica. */
    val categoria: String? = null,
    /** 1 = máxima. */
    val prioridad: Int,
    val aplicaA: String,
    val datosMinimos: List<String>,
    val esGenerica: Boolean,
    val activo: Boolean = true,
    val pesoRiesgo: Double = 1.0,
)

@Serializable
data class VersionResumen(
    val id: Int,
    val numero: Int,
    val estado: String,
    val creadaEn: String,
    val publicadaEn: String? = null,
)

@Serializable
data class PasoEscalamiento(
    val orden: Int,
    /** CORPORACION, OPERADOR o COORDINADOR_CIRCULO. */
    val destinoTipo: String,
    val corporacionId: Int? = null,
    val corporacion: String? = null,
    val operadorId: String? = null,
)

@Serializable
data class Regla(
    val corporacionId: Int,
    val corporacion: String,
    val tiempoMaxAcuseSeg: Int,
    val pasos: List<PasoEscalamiento>,
)

@Serializable
data class TipoConRegla(val tipo: TipoCatalogo, val regla: Regla? = null)

/** Un problema de la validación A-08. `tipo` es el código del tipo, si el problema es de uno. */
@Serializable
data class Problema(val codigo: String, val mensaje: String, val tipo: String? = null)

@Serializable
data class VersionDetalle(
    val version: VersionResumen,
    val tipos: List<TipoConRegla>,
    val errores: List<Problema>,
    val advertencias: List<Problema>,
)

/** W-06: un tipo dentro de la versión en edición. Si el código no existe, se crea un tipo nuevo. */
@Serializable
data class TipoEntrada(
    val nombre: String,
    val categoria: String? = null,
    val prioridad: Int,
    val aplicaA: String,
    val datosMinimos: List<String> = emptyList(),
    val pesoRiesgo: Double? = null,
    val activo: Boolean = true,
)

@Serializable
data class PasoEntrada(val destinoTipo: String, val corporacionId: Int? = null, val operadorId: String? = null)

/** W-07: regla de un tipo. */
@Serializable
data class ReglaEntrada(val corporacionId: Int, val tiempoMaxAcuseSeg: Int, val pasos: List<PasoEntrada>)
