package com.escom.sendero_seguro.emergencias

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * E3 ER-06 (Pág. 13 y 27): Tabla `cola_alerta` en la base local del teléfono.
 * Sin llaves foráneas; usa el mismo UUID generado en el teléfono para evitar duplicados.
 */
data class EntidadColaAlertaER06(
    val alertaId: String,          // PK TEXT: UUID de la alerta o registro
    val paquete: ByteArray,        // BLOB: Paquete cifrado y firmado listo para enviar
    val tipoElemento: String,      // TEXT: ALERTA, UBICACION, ACUSE o CANCELACION
    val ultimoCanal: String?,      // TEXT: INTERNET, MALLA o SMS
    val reintentos: Int = 0,       // INTEGER >= 0
    val creadoEn: Long,            // INTEGER: Milisegundos Unix
    val expiraEn: Long             // INTEGER: 2 h para ACUSE, 72 h para ALERTA/UBICACION
)

/**
 * E3 ER-06 (Pág. 13 y 27): Tabla `paquete_visto` para deduplicación de la red malla.
 */
data class EntidadPaqueteVistoER06(
    val paqueteId: String,         // PK TEXT: ID del paquete de la malla
    val alertaId: String,          // TEXT: Alerta que transporta
    val vistoEn: Long,             // INTEGER: Primera vez que llegó (se borra a las 24 h)
    val retransmitido: Int         // INTEGER: 0 o 1 (si este teléfono lo reenvió)
)

class GestorColaLocalSQLiteER06(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "sendero_seguro_local.db"
        private const val DB_VERSION = 1

        const val EXPIRACION_ACUSE_MS = 2 * 60 * 60 * 1000L       // 2 horas (E3 Pág. 27)
        const val EXPIRACION_ALERTA_MS = 72 * 60 * 60 * 1000L     // 72 horas (E3 Pág. 27)
        const val EXPIRACION_PAQUETE_VISTO_MS = 24 * 60 * 60 * 1000L // 24 horas (E3 Pág. 27)
    }

    private val _pendientesCola = MutableStateFlow<List<EntidadColaAlertaER06>>(emptyList())
    val pendientesCola: StateFlow<List<EntidadColaAlertaER06>> = _pendientesCola.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        // Script oficial compatible con sendero_seguro_local_sqlite.sql (ER-06)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cola_alerta (
                alerta_id TEXT PRIMARY KEY,
                paquete BLOB NOT NULL,
                tipo_elemento TEXT NOT NULL CHECK(tipo_elemento IN ('ALERTA', 'UBICACION', 'ACUSE', 'CANCELACION')),
                ultimo_canal TEXT,
                reintentos INTEGER NOT NULL DEFAULT 0,
                creado_en INTEGER NOT NULL,
                expira_en INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS paquete_visto (
                paquete_id TEXT PRIMARY KEY,
                alerta_id TEXT NOT NULL,
                visto_en INTEGER NOT NULL,
                retransmitido INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /**
     * E3 P11: Guarda en SQLite local los envíos pendientes asignándoles vigencia (2 h o 72 h).
     */
    fun encolarPendienteConVigencia(
        alertaId: String,
        payloadBytes: ByteArray,
        tipoElemento: String = "ALERTA",
        ultimoCanal: String = "MALLA"
    ) {
        val ahora = System.currentTimeMillis()
        val vigencia = if (tipoElemento == "ACUSE") EXPIRACION_ACUSE_MS else EXPIRACION_ALERTA_MS
        val valores = ContentValues().apply {
            put("alerta_id", alertaId)
            put("paquete", payloadBytes)
            put("tipo_elemento", tipoElemento)
            put("ultimo_canal", ultimoCanal)
            put("reintentos", 0)
            put("creado_en", ahora)
            put("expira_en", ahora + vigencia)
        }
        writableDatabase.insertWithOnConflict("cola_alerta", null, valores, SQLiteDatabase.CONFLICT_REPLACE)
        refrescarFlujoPendientes()
    }

    /**
     * E3 P5.1 / ER-06: Registra un paquete de malla en `paquete_visto` y limpia los mayores a 24 h.
     */
    fun registrarPaqueteMallaVisto(paqueteId: String, alertaId: String, fueRetransmitido: Boolean) {
        val ahora = System.currentTimeMillis()
        writableDatabase.delete("paquete_visto", "visto_en < ?", arrayOf((ahora - EXPIRACION_PAQUETE_VISTO_MS).toString()))

        val valores = ContentValues().apply {
            put("paquete_id", paqueteId)
            put("alerta_id", alertaId)
            put("visto_en", ahora)
            put("retransmitido", if (fueRetransmitido) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("paquete_visto", null, valores, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun refrescarFlujoPendientes() {
        val ahora = System.currentTimeMillis()
        // Purgar elementos cuya vigencia ya venció (estado EXPIRADA en E-01)
        writableDatabase.delete("cola_alerta", "expira_en <= ?", arrayOf(ahora.toString()))

        val lista = mutableListOf<EntidadColaAlertaER06>()
        readableDatabase.rawQuery("SELECT * FROM cola_alerta ORDER BY creado_en DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                lista.add(
                    EntidadColaAlertaER06(
                        alertaId = cursor.getString(cursor.getColumnIndexOrThrow("alerta_id")),
                        paquete = cursor.getBlob(cursor.getColumnIndexOrThrow("paquete")),
                        tipoElemento = cursor.getString(cursor.getColumnIndexOrThrow("tipo_elemento")),
                        ultimoCanal = cursor.getString(cursor.getColumnIndexOrThrow("ultimo_canal")),
                        reintentos = cursor.getInt(cursor.getColumnIndexOrThrow("reintentos")),
                        creadoEn = cursor.getLong(cursor.getColumnIndexOrThrow("creado_en")),
                        expiraEn = cursor.getLong(cursor.getColumnIndexOrThrow("expira_en"))
                    )
                )
            }
        }
        _pendientesCola.value = lista
    }
}