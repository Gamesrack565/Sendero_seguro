package com.senderoseguro.server.comunidad

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Una foto o audio ya subido con `POST /archivos/evidencia` (lo que respondió esa ruta). */
@Serializable
data class EvidenciaEntrada(val clave: String, val mime: String, val tamanoBytes: Long, val hashSha256: String)

/** Reporte comunitario (CU-28, A-15). El `id` lo genera el teléfono; `creadoEn` es su hora. */
@Serializable
data class ReporteEntrada(
    val id: String,
    val tipoIncidenteId: Int,
    val versionCatalogoId: Int? = null,
    val descripcion: String,
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val esAnonimo: Boolean = false,
    val creadoEn: String,
    val evidencias: List<EvidenciaEntrada> = emptyList(),
    /** Sugerencia de clasificación que hizo el teléfono (IA-1), si la hubo. */
    val tipoSugeridoId: Int? = null,
    val confianzaSugerencia: Double? = null,
)

@Serializable
data class ReporteRecibido(val id: String, val folio: String, val estado: String, val celda: String)

@Serializable
data class Evidencia(val clave: String, val url: String, val mime: String, val tamanoBytes: Long)

/** Un reporte propio (con nombre), con su estado de moderación. */
@Serializable
data class ReportePropio(
    val id: String,
    val folio: String,
    val tipo: String,
    val descripcion: String,
    val estado: String,
    val creadoEn: String,
    val moderadoEn: String? = null,
)

/** Un reporte en la cola de moderación (W-05): contenido, evidencia, ubicación exacta y la pública. */
@Serializable
data class ReporteModeracion(
    val id: String,
    val folio: String,
    val tipoIncidenteId: Int,
    val tipo: String,
    val categoria: String? = null,
    val descripcion: String,
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val celda: String,
    val latitudPublica: Double,
    val longitudPublica: Double,
    /** Nombre del autor, o «Anónimo». */
    val autor: String,
    val esAnonimo: Boolean,
    val estado: String,
    val creadoEn: String,
    val recibidoEn: String,
    val evidencias: List<Evidencia>,
    val tipoSugeridoId: Int? = null,
    val confianzaSugerencia: Double? = null,
    val decisiones: List<Decision> = emptyList(),
)

@Serializable
data class Decision(val decision: String, val motivo: String? = null, val decididoEn: String, val unidoAFolio: String? = null)

/** CU-30: PUBLICAR, RECHAZAR (con motivo) o RETIRAR (uno publicado, con motivo). */
@Serializable
data class DecisionEntrada(
    val decision: String,
    val motivo: String? = null,
    /** «Publicar unido a REP-…»: id del reporte publicado que ya cuenta lo mismo. */
    val unidoAId: String? = null,
    /** «Publicar ocultando teléfono»: quita teléfonos y correos de la descripción pública. */
    val ocultaDatosPersonales: Boolean = false,
    /** Referencia del lugar que se muestra en el feed, por ejemplo «Zacatenco, cerca de Av. Wilfrido Massieu». */
    val referencia: String? = null,
)

/** CU-31: la justificación (20 caracteres o más) queda en la bitácora. */
@Serializable
data class SolicitudIdentidad(val justificacion: String)

@Serializable
data class IdentidadAutor(val usuarioId: String, val nombre: String, val identificador: String, val telefono: String, val escuela: String)

/** Una tarjeta del feed (A-16): nunca trae al autor anónimo ni la ubicación exacta. */
@Serializable
data class ReportePublico(
    val id: String,
    val tipoIncidenteId: Int,
    val tipo: String,
    val categoria: String? = null,
    val descripcion: String,
    val celda: String,
    val latitud: Double,
    val longitud: Double,
    val referencia: String? = null,
    val autor: String,
    val publicadoEn: String,
    val distanciaM: Double? = null,
)

@Serializable
data class PaginaFeed(val reportes: List<ReportePublico>, val siguiente: String? = null)

/** Zona del mapa de calor (A-17). `geometria` es GeoJSON Polygon en [longitud, latitud]. */
@Serializable
data class Zona(
    val id: Long,
    val nivel: String,
    val tipoIncidenteId: Int? = null,
    val periodoDias: Int,
    val densidadPonderada: Double,
    val numReportes: Int,
    val centroLatitud: Double,
    val centroLongitud: Double,
    val geometria: JsonObject,
)

@Serializable
data class CapaRiesgo(
    val version: Int,
    val generadaEn: String,
    val periodoDias: Int,
    val zonas: List<Zona>,
    val leyenda: String = "Información orientativa: zonas con reportes comunitarios publicados, no una predicción.",
)

@Serializable
data class MotivoZona(val tipo: String, val reportes: Int)

@Serializable
data class ZonaDetalle(val zona: Zona, val motivos: List<MotivoZona>, val ultimoReporteEn: String? = null)

@Serializable
data class GeneracionCapa(val version: Int, val zonas: Int, val reportes: Int, val tramos: Int)

// ---------------------------------------------------------------- Ruta segura y grafo (P12, A-07)

@Serializable
data class Coordenada(val latitud: Double, val longitud: Double)

@Serializable
data class SolicitudRuta(val origen: Coordenada, val destino: Coordenada)

@Serializable
data class Ruta(
    val tipo: String,
    val puntos: List<Coordenada>,
    val distanciaM: Double,
    val duracionMin: Int,
    /** Promedio del riesgo de los tramos, ponderado por su largo (0 a 1). */
    val riesgoMedio: Double,
)

@Serializable
data class Advertencia(val zonaId: Long, val nivel: String, val mensaje: String)

@Serializable
data class RutasSugeridas(
    val segura: Ruta,
    val masCorta: Ruta,
    val advertencias: List<Advertencia>,
    val capaVersion: Int? = null,
    val calculadaEn: String = "SERVIDOR",
    val explicacion: String,
)

@Serializable
data class NodoEntrada(val id: Long, val latitud: Double, val longitud: Double)

@Serializable
data class AristaEntrada(
    val origen: Long,
    val destino: Long,
    val nombreVia: String? = null,
    val tipoVia: String? = null,
    val bidireccional: Boolean = true,
    val osmWayId: Long? = null,
)

/** Carga del grafo peatonal (nodos y tramos de OpenStreetMap, ya recortados a la zona). */
@Serializable
data class GrafoEntrada(val nodos: List<NodoEntrada>, val aristas: List<AristaEntrada>)

@Serializable
data class GrafoCargado(val nodos: Int, val aristas: Int, val tramosConRiesgo: Int)

@Serializable
data class Arista(val id: Long, val origen: Long, val destino: Long, val longitudM: Double, val bidireccional: Boolean, val riesgo: Double)

/** Grafo de una caja para la caché del teléfono (CU-36), con el riesgo de cada tramo en la capa vigente. */
@Serializable
data class Grafo(val capaVersion: Int? = null, val nodos: List<NodoEntrada>, val aristas: List<Arista>)

// ---------------------------------------------------------------- Mapas offline, puntos de interés y zonas

@Serializable
data class PaqueteMapa(
    val id: Int,
    val region: String,
    val version: String,
    val minLatitud: Double,
    val minLongitud: Double,
    val maxLatitud: Double,
    val maxLongitud: Double,
    val tamanoBytes: Long,
    val hashSha256: String,
    val url: String,
    val publicadoEn: String,
)

@Serializable
data class PaqueteEntrada(
    val region: String,
    val version: String,
    val minLatitud: Double,
    val minLongitud: Double,
    val maxLatitud: Double,
    val maxLongitud: Double,
    /** Lo que respondió `POST /archivos/mapa`. */
    val clave: String,
    val tamanoBytes: Long,
    val hashSha256: String,
)

/** Lo que la app compara con su caché para saber qué descargar (CU-36 1a). */
@Serializable
data class VersionesMapas(val capaRiesgo: Int? = null, val catalogo: Int? = null, val paquetes: List<PaqueteVersion>)

@Serializable
data class PaqueteVersion(val region: String, val version: String)

@Serializable
data class PuntoInteres(val id: Int, val nombre: String, val tipo: String, val latitud: Double, val longitud: Double)

@Serializable
data class PuntoInteresEntrada(val nombre: String, val tipo: String, val latitud: Double, val longitud: Double)

@Serializable
data class ZonaGeografica(val id: Int, val nombre: String, val minLatitud: Double, val minLongitud: Double, val maxLatitud: Double, val maxLongitud: Double)

@Serializable
data class ZonaGeograficaEntrada(val nombre: String, val minLatitud: Double, val minLongitud: Double, val maxLatitud: Double, val maxLongitud: Double)
