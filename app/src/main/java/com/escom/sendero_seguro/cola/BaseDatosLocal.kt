package com.escom.sendero_seguro.cola

import android.content.Context
import com.escom.sendero_seguro.core.db.SenderoDatabase

/**
 * Acceso del P11 a la base Room compartida del teléfono. La cola y la identidad deben usar
 * la misma instancia para no abrir el mismo archivo con esquemas Room incompatibles.
 */
class BaseDatosLocal private constructor(private val db: SenderoDatabase) {

    fun colaAlertaDao(): ColaAlertaDao = db.colaAlertaDaoOffline()
    fun colaReporteDao(): ColaReporteDao = db.colaReporteDao()
    fun paqueteVistoDao(): PaqueteVistoDao = db.paqueteVistoDao()
    fun cacheMapaDao(): CacheMapaDao = db.cacheMapaDao()
    fun catalogoLocalDao(): CatalogoLocalDao = db.catalogoLocalDao()

    companion object {
        @Volatile
        private var instancia: BaseDatosLocal? = null

        fun obtenerInstancia(context: Context): BaseDatosLocal = instancia ?: synchronized(this) {
            instancia ?: BaseDatosLocal(SenderoDatabase.obtener(context.applicationContext))
                .also { instancia = it }
        }
    }
}
