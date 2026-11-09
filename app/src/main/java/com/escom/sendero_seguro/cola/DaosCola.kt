package com.escom.sendero_seguro.cola

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ColaAlertaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarOActualizar(alerta: ColaAlertaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarTodas(alertas: List<ColaAlertaEntity>)

    @Query("SELECT * FROM cola_alerta ORDER BY creado_en ASC LIMIT 1")
    suspend fun obtenerSiguienteElemento(): ColaAlertaEntity?

    @Query("SELECT * FROM cola_alerta WHERE expira_en > :ahora ORDER BY creado_en ASC LIMIT 1")
    suspend fun obtenerSiguienteAlertaNoVencida(ahora: Long): ColaAlertaEntity?

    @Query("SELECT * FROM cola_alerta WHERE alerta_id = :alertaId LIMIT 1")
    suspend fun buscarPorId(alertaId: String): ColaAlertaEntity?

    @Query("SELECT * FROM cola_alerta WHERE expira_en > :ahora ORDER BY creado_en ASC")
    suspend fun obtenerAlertasPendientes(ahora: Long): List<ColaAlertaEntity>

    @Query("SELECT * FROM cola_alerta WHERE expira_en <= :ahora")
    suspend fun obtenerAlertasExpiradas(ahora: Long): List<ColaAlertaEntity>

    @Query("DELETE FROM cola_alerta WHERE alerta_id = :alertaId")
    suspend fun eliminarPorId(alertaId: String): Int

    @Query("DELETE FROM cola_alerta WHERE expira_en <= :ahora")
    suspend fun purgarExpiradas(ahora: Long): Int

    @Query("UPDATE cola_alerta SET reintentos = reintentos + 1 WHERE alerta_id = :alertaId")
    suspend fun incrementarReintentos(alertaId: String): Int

    @Query("SELECT COUNT(*) FROM cola_alerta WHERE expira_en > :ahora")
    suspend fun contarPendientes(ahora: Long): Int

    @Query("SELECT SUM(reintentos) FROM cola_alerta")
    suspend fun sumarTotalReintentos(): Int?

    @Query("SELECT MIN(expira_en) FROM cola_alerta WHERE expira_en > :ahora")
    suspend fun obtenerProximoVencimiento(ahora: Long): Long?

    @Query("SELECT * FROM cola_alerta WHERE expira_en > :ahora ORDER BY creado_en ASC")
    fun flujoAlertasPendientes(ahora: Long): Flow<List<ColaAlertaEntity>>
}

@Dao
interface ColaReporteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertarOActualizar(reporte: ColaReporteEntity)

    @Query("SELECT * FROM cola_reporte ORDER BY creado_en ASC LIMIT 1")
    suspend fun obtenerSiguienteElemento(): ColaReporteEntity?

    @Query("SELECT * FROM cola_reporte WHERE expira_en > :ahora ORDER BY creado_en ASC LIMIT 1")
    suspend fun obtenerSiguienteReporteNoVencido(ahora: Long): ColaReporteEntity?

    @Query("SELECT * FROM cola_reporte WHERE reporte_id = :reporteId LIMIT 1")
    suspend fun buscarPorId(reporteId: String): ColaReporteEntity?

    @Query("SELECT * FROM cola_reporte WHERE expira_en > :ahora ORDER BY creado_en ASC")
    suspend fun obtenerReportesPendientes(ahora: Long): List<ColaReporteEntity>

    @Query("SELECT * FROM cola_reporte WHERE expira_en <= :ahora")
    suspend fun obtenerReportesExpirados(ahora: Long): List<ColaReporteEntity>

    @Query("DELETE FROM cola_reporte WHERE reporte_id = :reporteId")
    suspend fun eliminarPorId(reporteId: String): Int

    @Query("DELETE FROM cola_reporte WHERE expira_en <= :ahora")
    suspend fun purgarExpirados(ahora: Long): Int

    @Query("UPDATE cola_reporte SET reintentos = reintentos + 1 WHERE reporte_id = :reporteId")
    suspend fun incrementarReintentos(reporteId: String): Int

    @Query("SELECT COUNT(*) FROM cola_reporte WHERE expira_en > :ahora")
    suspend fun contarPendientes(ahora: Long): Int

    @Query("SELECT SUM(tamano_bytes) FROM cola_reporte WHERE expira_en > :ahora")
    suspend fun obtenerTamanoTotalBytes(ahora: Long): Long?

    @Query("SELECT SUM(reintentos) FROM cola_reporte")
    suspend fun sumarTotalReintentos(): Int?

    @Query("SELECT MIN(expira_en) FROM cola_reporte WHERE expira_en > :ahora")
    suspend fun obtenerProximoVencimiento(ahora: Long): Long?

    @Query("SELECT * FROM cola_reporte WHERE expira_en > :ahora ORDER BY creado_en ASC")
    fun flujoReportesPendientes(ahora: Long): Flow<List<ColaReporteEntity>>
}

@Dao
interface PaqueteVistoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun registrarPaquete(paquete: PaqueteVistoEntity): Long

    @Query("SELECT COUNT(*) > 0 FROM paquete_visto WHERE paquete_id = :paqueteId")
    suspend fun existePaquete(paqueteId: String): Boolean

    @Query("UPDATE paquete_visto SET retransmitido = 1 WHERE paquete_id = :paqueteId")
    suspend fun marcarRetransmitido(paqueteId: String): Int

    @Query("DELETE FROM paquete_visto WHERE visto_en < :antiguedadLimiteMs")
    suspend fun limpiarAntiguos(antiguedadLimiteMs: Long): Int
}

@Dao
interface CacheMapaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarTesela(mapa: CacheMapaEntity)

    @Query("SELECT * FROM cache_mapa WHERE tesela = :tesela LIMIT 1")
    suspend fun obtenerPorTesela(tesela: String): CacheMapaEntity?

    @Query("SELECT version_capa FROM cache_mapa WHERE tesela = :tesela LIMIT 1")
    suspend fun obtenerVersionCapa(tesela: String): Int?
}

@Dao
interface ConfigLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarConfig(config: ConfigLocalEntity)

    @Query("SELECT valor FROM config_local WHERE clave = :clave LIMIT 1")
    suspend fun obtenerValor(clave: String): String?
}

@Dao
interface CatalogoLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarCatalogo(items: List<CatalogoLocalEntity>)

    @Query("SELECT * FROM catalogo_local ORDER BY prioridad ASC")
    suspend fun obtenerCatalogoCompleto(): List<CatalogoLocalEntity>
}

@Dao
interface ContactoLocalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarContactos(contactos: List<ContactoLocalEntity>)

    @Query("SELECT * FROM contacto_local ORDER BY prioridad ASC")
    suspend fun obtenerContactosPriorizados(): List<ContactoLocalEntity>
}

