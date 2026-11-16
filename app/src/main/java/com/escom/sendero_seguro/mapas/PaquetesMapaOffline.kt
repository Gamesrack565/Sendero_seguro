package com.escom.sendero_seguro.mapas

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException

internal object PaquetesMapaOffline {
    private val firmaSqlite = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun directorio(context: Context): File =
        File(context.applicationContext.filesDir, "p12/mapas")

    fun validarArchivoMbtiles(archivo: File) {
        validarFirmaMbtiles(archivo)
        val database = try {
            SQLiteDatabase.openDatabase(archivo.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            throw IOException("El paquete vectorial no es una base MBTiles SQLite válida.", e)
        }

        try {
            val formato = database.valorMetadata("format")
            if (!formato.equals("pbf", ignoreCase = true)) {
                throw IOException("El MBTiles debe contener teselas vectoriales PBF (metadata format=pbf).")
            }
            val cantidadTeselas = database.rawQuery("SELECT COUNT(*) FROM tiles", null).use { cursor ->
                if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
            }
            if (cantidadTeselas == 0L) {
                throw IOException("El MBTiles no contiene teselas.")
            }
            val jsonMetadata = database.valorMetadata("json")
                ?: throw IOException("El MBTiles no declara sus capas vectoriales en metadata json.")
            validarMetadataVectorial(jsonMetadata)
        } catch (e: SerializationException) {
            throw IOException("El MBTiles contiene metadatos vectoriales JSON inválidos.", e)
        } catch (e: SQLiteException) {
            throw IOException("El MBTiles no contiene la estructura vectorial esperada.", e)
        } finally {
            database.close()
        }
    }

    internal fun validarMetadataVectorial(jsonMetadata: String) {
        val metadata = Json.parseToJsonElement(jsonMetadata) as? JsonObject
            ?: throw IOException("El MBTiles no declara metadata vectorial JSON válida.")
        val capas = metadata["vector_layers"] as? JsonArray
            ?: throw IOException("El MBTiles no declara vector_layers.")
        val capasVectoriales = capas.mapNotNull { it as? JsonObject }
        val nombresCapas = capasVectoriales.mapNotNull { capa ->
            (capa["id"] as? JsonPrimitive)?.contentOrNull
        }.toSet()
        if (!nombresCapas.containsAll(setOf("transportation", "transportation_name", "water"))) {
            throw IOException(
                "El MBTiles debe incluir las capas OpenMapTiles transportation, transportation_name y water.",
            )
        }
        val camposNombres = capasVectoriales
            .firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == "transportation_name" }
            ?.get("fields") as? JsonObject
        if (camposNombres == null || !camposNombres.containsKey("name") && !camposNombres.containsKey("name:es")) {
            throw IOException("La capa transportation_name debe contener nombres de calles.")
        }
    }

    internal fun validarFirmaMbtiles(archivo: File) {
        val firma = ByteArray(firmaSqlite.size)
        val bytesLeidos = FileInputStream(archivo).use { input ->
            var total = 0
            while (total < firma.size) {
                val leidos = input.read(firma, total, firma.size - total)
                if (leidos < 0) break
                total += leidos
            }
            total
        }
        if (bytesLeidos != firmaSqlite.size || !firma.contentEquals(firmaSqlite)) {
            throw IOException("El paquete vectorial debe ser un archivo MBTiles SQLite, no ZIP ni PNG.")
        }
    }

    private fun SQLiteDatabase.valorMetadata(nombre: String): String? =
        rawQuery("SELECT value FROM metadata WHERE name = ?", arrayOf(nombre)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
