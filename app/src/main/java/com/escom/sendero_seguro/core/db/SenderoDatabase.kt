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
}

@Database(
    entities = [ConfigLocalEntity::class, ContactoLocalEntity::class, ColaAlertaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SenderoDatabase : RoomDatabase() {
    abstract fun configLocalDao(): ConfigLocalDao
    abstract fun contactoLocalDao(): ContactoLocalDao
    abstract fun colaAlertaDao(): ColaAlertaDao

    companion object {
        private const val NOMBRE = "sendero_seguro_local.db"

        @Volatile
        private var instancia: SenderoDatabase? = null

        fun obtener(context: Context): SenderoDatabase = instancia ?: synchronized(this) {
            instancia ?: Room.databaseBuilder(context.applicationContext, SenderoDatabase::class.java, NOMBRE)
                .build()
                .also { instancia = it }
        }
    }
}
