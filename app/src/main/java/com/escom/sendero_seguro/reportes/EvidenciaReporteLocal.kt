package com.escom.sendero_seguro.reportes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

data class EvidenciaReporteLocal(
    val archivo: File,
    val mime: String,
    val nombre: String,
)

const val MAX_EVIDENCIA_REPORTE_BYTES = 10L * 1024L * 1024L

fun mimeEvidenciaLocal(archivo: File): String? =
    EXTENSIONES_EVIDENCIA.entries.firstOrNull { it.value == archivo.extension.lowercase() }?.key

fun crearArchivoAudioReporte(context: Context): File {
    val archivo = File(directorioEvidencia(context), "${UUID.randomUUID()}.m4a")
    if (!archivo.createNewFile()) throw IOException("No se pudo crear el archivo para grabar el audio.")
    return archivo
}

fun guardarEvidenciaReporte(context: Context, uri: Uri): EvidenciaReporteLocal {
    val resolver = context.contentResolver
    val tipoDetectado = resolver.getType(uri)?.substringBefore(';')?.lowercase()
        ?: throw IOException("No se pudo determinar el tipo del archivo.")
    val mime = if (tipoDetectado == "audio/x-m4a") "audio/mp4" else tipoDetectado
    val extension = EXTENSIONES_EVIDENCIA[mime]
        ?: throw IOException("Formato no permitido: $mime.")
    val nombre = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf { it.isNotBlank() } ?: "evidencia.$extension"
    val archivo = File(directorioEvidencia(context), "${UUID.randomUUID()}.$extension")
    try {
        val entrada = resolver.openInputStream(uri)
            ?: throw IOException("No se pudo leer el archivo seleccionado.")
        entrada.use { input ->
            archivo.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val leidos = input.read(buffer)
                    if (leidos < 0) break
                    total += leidos
                    if (total > MAX_EVIDENCIA_REPORTE_BYTES) {
                        throw IOException("Cada foto o audio puede pesar hasta 10 MB.")
                    }
                    output.write(buffer, 0, leidos)
                }
                if (total == 0L) throw IOException("El archivo seleccionado está vacío.")
            }
        }
    } catch (e: Exception) {
        if (archivo.exists() && !archivo.delete()) {
            throw IOException("Falló la copia del archivo y no se pudo limpiar el archivo parcial.", e)
        }
        throw e
    }
    return EvidenciaReporteLocal(archivo, mime, nombre)
}

private fun directorioEvidencia(context: Context): File {
    val directorio = File(context.filesDir, DIRECTORIO_EVIDENCIA)
    if (!directorio.exists() && !directorio.mkdirs()) {
        throw IOException("No se pudo crear el almacenamiento local de evidencias.")
    }
    if (!directorio.isDirectory) throw IOException("El almacenamiento local de evidencias no es un directorio.")
    return directorio
}

private const val DIRECTORIO_EVIDENCIA = "reportes_evidencia"

private val EXTENSIONES_EVIDENCIA = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
    "audio/mpeg" to "mp3",
    "audio/mp4" to "m4a",
    "audio/aac" to "aac",
    "audio/ogg" to "ogg",
)
