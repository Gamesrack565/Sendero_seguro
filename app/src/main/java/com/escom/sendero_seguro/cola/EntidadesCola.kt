package com.escom.sendero_seguro.cola

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ER-06: Tabla cola_reporte
 * Almacena los reportes comunitarios y sus evidencias pendientes de sincronizar con el servidor de Azure.
 * TTL estándar: 6 días para reportes comunitarios (P11).
 */
@Entity(tableName = "cola_reporte")
data class ColaReporteEntity(
    @PrimaryKey
    @ColumnInfo(name = "reporte_id")
    val reporteId: String,

    @ColumnInfo(name = "datos")
    val datos: String, // Payload en JSON con descripción, lat, lon, folio, tipo_incidente_id, etc.

    @ColumnInfo(name = "rutas_evidencia")
    val rutasEvidencia: String?, // JSON o rutas separadas por comas de archivos de fotos/audios locales

    @ColumnInfo(name = "tamano_bytes")
    val tamanoBytes: Long = 0L,

    @ColumnInfo(name = "reintentos")
    val reintentos: Int = 0,

    @ColumnInfo(name = "creado_en")
    val creadoEn: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "expira_en")
    val expiraEn: Long
)

/**
 * ER-06: Tabla paquete_visto
 * Control de duplicados en la red de malla para evitar retransmitir paquetes ya vistos.
 */
@Entity(tableName = "paquete_visto")
data class PaqueteVistoEntity(
    @PrimaryKey
    @ColumnInfo(name = "paquete_id")
    val paqueteId: String,

    @ColumnInfo(name = "alerta_id")
    val alertaId: String,

    @ColumnInfo(name = "visto_en")
    val vistoEn: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "retransmitido")
    val retransmitido: Int = 0 // 0 = No retransmitido, 1 = Retransmitido
)

/**
 * ER-06: Tabla cache_mapa
 * Soporte para P12 (Descarga y Actualización de Mapas/Rutas Offline).
 */
@Entity(tableName = "cache_mapa")
data class CacheMapaEntity(
    @PrimaryKey
    @ColumnInfo(name = "tesela")
    val tesela: String,

    @ColumnInfo(name = "version_capa")
    val versionCapa: Int = 1,

    @ColumnInfo(name = "geojson_riesgo")
    val geojsonRiesgo: String?,

    @ColumnInfo(name = "grafo", typeAffinity = ColumnInfo.BLOB)
    val grafo: ByteArray?,

    @ColumnInfo(name = "descargado_en")
    val descargadoEn: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CacheMapaEntity
        if (tesela != other.tesela) return false
        if (versionCapa != other.versionCapa) return false
        if (geojsonRiesgo != other.geojsonRiesgo) return false
        if (grafo != null) {
            if (other.grafo == null) return false
            if (!grafo.contentEquals(other.grafo)) return false
        } else if (other.grafo != null) return false
        if (descargadoEn != other.descargadoEn) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tesela.hashCode()
        result = 31 * result + versionCapa
        result = 31 * result + (geojsonRiesgo?.hashCode() ?: 0)
        result = 31 * result + (grafo?.contentHashCode() ?: 0)
        result = 31 * result + descargadoEn.hashCode()
        return result
    }
}

/**
 * ER-06: Tabla catalogo_local
 * Catálogo offline de tipos de incidentes sincronizados desde el servidor.
 */
@Entity(tableName = "catalogo_local")
data class CatalogoLocalEntity(
    @PrimaryKey
    @ColumnInfo(name = "tipo_incidente_id")
    val tipoIncidenteId: Int,

    @ColumnInfo(name = "version_catalogo_id")
    val versionCatalogoId: Int,

    @ColumnInfo(name = "nombre")
    val nombre: String,

    @ColumnInfo(name = "categoria")
    val categoria: String,

    @ColumnInfo(name = "prioridad")
    val prioridad: Int,

    @ColumnInfo(name = "aplica_a")
    val aplicaA: String,

    @ColumnInfo(name = "es_generica")
    val esGenerica: Int // 0 o 1
)

