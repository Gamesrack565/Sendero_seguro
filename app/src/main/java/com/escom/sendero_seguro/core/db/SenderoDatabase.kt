package com.escom.sendero_seguro.core.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.escom.sendero_seguro.cola.CacheMapaDao
import com.escom.sendero_seguro.cola.CacheMapaEntity
import com.escom.sendero_seguro.cola.CatalogoLocalDao
import com.escom.sendero_seguro.cola.CatalogoLocalEntity
import com.escom.sendero_seguro.cola.ColaReporteDao
import com.escom.sendero_seguro.cola.ColaReporteEntity
import com.escom.sendero_seguro.cola.PaqueteVistoDao
import com.escom.sendero_seguro.cola.PaqueteVistoEntity
import kotlinx.coroutines.flow.Flow

/*
 * Base local del teléfono (ER-06, sendero_seguro_local_sqlite.sql).
 * Es compartida por todos los módulos: cada uno agrega aquí sus entidades y sube `version`
 * con una migración. El Módulo 1 usa config_local y contacto_local, y escribe en cola_alerta
 * solo las cancelaciones (tipo_elemento = CANCELACION).
 */

@Entity(tableName = "config_local")
data class ConfigLocalEntity(
    @PrimaryKey @ColumnInfo(name = "clave") val clave: String,
    @ColumnInfo(name = "valor") val valor: String,
    @ColumnInfo(name = "actualizado_en") val actualizadoEn: Long
)

@Entity(tableName = "contacto_local")
data class ContactoLocalEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "nombre") val nombre: String,
    @ColumnInfo(name = "telefono") val telefono: String,
    @ColumnInfo(name = "prioridad") val prioridad: Int,
    @ColumnInfo(name = "tiene_app") val tieneApp: Int
)

@Entity(tableName = "cola_alerta", indices = [Index(value = ["expira_en"], name = "ix_cola_alerta_expira")])
class ColaAlertaEntity(
    @PrimaryKey @ColumnInfo(name = "alerta_id") val alertaId: String,
    @ColumnInfo(name = "paquete", typeAffinity = ColumnInfo.BLOB) val paquete: ByteArray,
    @ColumnInfo(name = "tipo_elemento") val tipoElemento: String,
    @ColumnInfo(name = "ultimo_canal") val ultimoCanal: String?,
    @ColumnInfo(name = "reintentos") val reintentos: Int,
    @ColumnInfo(name = "creado_en") val creadoEn: Long,
    @ColumnInfo(name = "expira_en") val expiraEn: Long
)

@Dao
interface ConfigLocalDao {
    @Query("SELECT valor FROM config_local WHERE clave = :clave")
    suspend fun leer(clave: String): String?

    @Upsert
    suspend fun escribir(e: ConfigLocalEntity)

    @Query("DELETE FROM config_local WHERE clave = :clave")
    suspend fun borrar(clave: String)

    @Query("SELECT * FROM config_local")
    fun observarTodo(): Flow<List<ConfigLocalEntity>>
}

@Dao
abstract class ContactoLocalDao {
    @Query("SELECT * FROM contacto_local ORDER BY prioridad")
    abstract fun observar(): Flow<List<ContactoLocalEntity>>

    @Query("SELECT * FROM contacto_local ORDER BY prioridad")
    abstract suspend fun listar(): List<ContactoLocalEntity>

    @Upsert
    abstract suspend fun guardar(e: ContactoLocalEntity)

    @Upsert
    abstract suspend fun guardarTodos(e: List<ContactoLocalEntity>)

    @Query("DELETE FROM contacto_local WHERE id = :id")
    abstract suspend fun eliminar(id: String)

    @Query("DELETE FROM contacto_local")
    abstract suspend fun borrarTodos()

    @Transaction
    open suspend fun reemplazarTodos(e: List<ContactoLocalEntity>) {
        borrarTodos()
        guardarTodos(e)
    }
}

@Dao
interface ColaAlertaDao {
    @Query("SELECT * FROM cola_alerta WHERE alerta_id = :alertaId")
    suspend fun buscar(alertaId: String): ColaAlertaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun reemplazar(e: ColaAlertaEntity)

    @Query("DELETE FROM cola_alerta WHERE alerta_id = :alertaId")
    suspend fun eliminar(alertaId: String)

    @Query("SELECT * FROM cola_alerta WHERE tipo_elemento = :tipo ORDER BY creado_en")
    suspend fun listarPorTipo(tipo: String): List<ColaAlertaEntity>

    @Query("DELETE FROM cola_alerta WHERE expira_en <= :ahora")
    suspend fun borrarVencidos(ahora: Long)
}

@Database(
    entities = [
        ConfigLocalEntity::class,
        ContactoLocalEntity::class,
        ColaAlertaEntity::class,
        ColaReporteEntity::class,
        PaqueteVistoEntity::class,
        CacheMapaEntity::class,
        CatalogoLocalEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class SenderoDatabase : RoomDatabase() {
    abstract fun configLocalDao(): ConfigLocalDao
    abstract fun contactoLocalDao(): ContactoLocalDao
    abstract fun colaAlertaDao(): ColaAlertaDao
    abstract fun colaAlertaDaoOffline(): com.escom.sendero_seguro.cola.ColaAlertaDao
    abstract fun colaReporteDao(): ColaReporteDao
    abstract fun paqueteVistoDao(): PaqueteVistoDao
    abstract fun cacheMapaDao(): CacheMapaDao
    abstract fun catalogoLocalDao(): CatalogoLocalDao

    companion object {
        private const val NOMBRE = "sendero_seguro_local.db"

        private val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = migrarColaCompartida(db)
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = migrarColaCompartida(db)
        }

        private fun migrarColaCompartida(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE cola_alerta RENAME TO cola_alerta_anterior")
            db.execSQL(
                """CREATE TABLE cola_alerta (
                    alerta_id TEXT NOT NULL,
                    paquete BLOB NOT NULL,
                    tipo_elemento TEXT NOT NULL,
                    ultimo_canal TEXT,
                    reintentos INTEGER NOT NULL,
                    creado_en INTEGER NOT NULL,
                    expira_en INTEGER NOT NULL,
                    PRIMARY KEY(alerta_id)
                )""".trimIndent()
            )
            db.execSQL(
                """INSERT INTO cola_alerta
                    (alerta_id, paquete, tipo_elemento, ultimo_canal, reintentos, creado_en, expira_en)
                    SELECT alerta_id, COALESCE(paquete, X''), tipo_elemento, ultimo_canal,
                           reintentos, creado_en, expira_en
                    FROM cola_alerta_anterior""".trimIndent()
            )
            db.execSQL("DROP TABLE cola_alerta_anterior")
            db.execSQL("CREATE INDEX IF NOT EXISTS ix_cola_alerta_expira ON cola_alerta (expira_en)")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS cola_reporte (
                    reporte_id TEXT NOT NULL,
                    datos TEXT NOT NULL,
                    rutas_evidencia TEXT,
                    tamano_bytes INTEGER NOT NULL,
                    reintentos INTEGER NOT NULL,
                    creado_en INTEGER NOT NULL,
                    expira_en INTEGER NOT NULL,
                    PRIMARY KEY(reporte_id)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS paquete_visto (
                    paquete_id TEXT NOT NULL,
                    alerta_id TEXT NOT NULL,
                    visto_en INTEGER NOT NULL,
                    retransmitido INTEGER NOT NULL,
                    PRIMARY KEY(paquete_id)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS cache_mapa (
                    tesela TEXT NOT NULL,
                    version_capa INTEGER NOT NULL,
                    geojson_riesgo TEXT,
                    grafo BLOB,
                    descargado_en INTEGER NOT NULL,
                    PRIMARY KEY(tesela)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS catalogo_local (
                    tipo_incidente_id INTEGER NOT NULL,
                    version_catalogo_id INTEGER NOT NULL,
                    nombre TEXT NOT NULL,
                    categoria TEXT NOT NULL,
                    prioridad INTEGER NOT NULL,
                    aplica_a TEXT NOT NULL,
                    es_generica INTEGER NOT NULL,
                    PRIMARY KEY(tipo_incidente_id)
                )""".trimIndent()
            )
        }

        @Volatile
        private var instancia: SenderoDatabase? = null

        fun obtener(context: Context): SenderoDatabase = instancia ?: synchronized(this) {
            instancia ?: Room.databaseBuilder(context.applicationContext, SenderoDatabase::class.java, NOMBRE)
                .addMigrations(MIGRATION_1_3, MIGRATION_2_3)
                .build()
                .also { instancia = it }
        }
    }
}
