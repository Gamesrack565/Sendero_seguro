package com.escom.sendero_seguro.cola

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * ER-06: Base local del teléfono.
 * Sin llaves foráneas entre tablas: usan el mismo UUID que el servidor para que un reenvío no duplique.
 * Abstracción de SQLite mediante Room.
 */
@Database(
    entities = [
        ColaAlertaEntity::class,
        ColaReporteEntity::class,
        PaqueteVistoEntity::class,
        CacheMapaEntity::class,
        ConfigLocalEntity::class,
        CatalogoLocalEntity::class,
        ContactoLocalEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BaseDatosLocal : RoomDatabase() {

    abstract fun colaAlertaDao(): ColaAlertaDao
    abstract fun colaReporteDao(): ColaReporteDao
    abstract fun paqueteVistoDao(): PaqueteVistoDao
    abstract fun cacheMapaDao(): CacheMapaDao
    abstract fun configLocalDao(): ConfigLocalDao
    abstract fun catalogoLocalDao(): CatalogoLocalDao
    abstract fun contactoLocalDao(): ContactoLocalDao

    companion object {
        private const val NOMBRE_BASE_DATOS = "sendero_seguro_local.db"

        @Volatile
        private var INSTANCIA: BaseDatosLocal? = null

        fun obtenerInstancia(context: Context): BaseDatosLocal {
            return INSTANCIA ?: synchronized(this) {
                val instancia = Room.databaseBuilder(
                    context.applicationContext,
                    BaseDatosLocal::class.java,
                    NOMBRE_BASE_DATOS
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCIA = instancia
                instancia
            }
        }
    }
}

