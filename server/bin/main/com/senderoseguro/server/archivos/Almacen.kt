package com.senderoseguro.server.archivos

import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

/** Un archivo abierto para leerlo. Quien lo recibe cierra [contenido]. */
class ArchivoAbierto(val tamanoBytes: Long, val contenido: InputStream)

/**
 * Dónde viven los archivos: fotos y audios de los reportes (`evidencia`) y mapas offline (`paquete_mapa`).
 * La base solo guarda la clave, por ejemplo `evidencia/3f2a….jpg`.
 */
interface Almacen {
    val nombre: String

    /** Guarda [archivo] con esa [clave]. Si ya existía, lo reemplaza. */
    fun guardar(clave: String, mime: String, archivo: Path)

    /** Abre el archivo de esa [clave], o null si no existe. */
    fun abrir(clave: String): ArchivoAbierto?
}

/** En una carpeta del disco: para desarrollo y pruebas. */
class AlmacenCarpeta(private val carpeta: Path) : Almacen {
    override val nombre = "carpeta"

    private fun ruta(clave: String): Path = carpeta.resolve(clave).normalize().also {
        require(it.startsWith(carpeta.normalize())) { "Clave fuera de la carpeta: $clave" }
    }

    override fun guardar(clave: String, mime: String, archivo: Path) {
        val destino = ruta(clave)
        Files.createDirectories(destino.parent)
        Files.copy(archivo, destino, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun abrir(clave: String): ArchivoAbierto? {
        val origen = ruta(clave)
        if (!Files.isRegularFile(origen)) return null
        return ArchivoAbierto(Files.size(origen), Files.newInputStream(origen))
    }
}

/**
 * En un contenedor de Azure Blob Storage, con la API REST y un token SAS del contenedor (permisos de leer, crear y
 * escribir). [urlContenedor] es `https://<cuenta>.blob.core.windows.net/<contenedor>`; [sas] es el token sin el `?`.
 */
class AlmacenAzureBlob(
    urlContenedor: String,
    sas: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : Almacen {
    override val nombre = "azure"

    private val base = urlContenedor.trimEnd('/')
    private val sas = sas.trim().removePrefix("?")

    private fun uri(clave: String): URI {
        val ruta = clave.split('/').joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
        return URI.create("$base/$ruta?$sas")
    }

    override fun guardar(clave: String, mime: String, archivo: Path) {
        // Put Blob sube el archivo completo en una sola petición (hasta 5000 MiB con esta versión de la API).
        val respuesta = http.send(
            HttpRequest.newBuilder(uri(clave))
                .timeout(Duration.ofMinutes(5))
                .header("x-ms-version", VERSION_API)
                .header("x-ms-blob-type", "BlockBlob")
                .header("x-ms-blob-content-type", mime)
                .PUT(HttpRequest.BodyPublishers.ofFile(archivo))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(respuesta.statusCode() == 201) {
            "Azure Blob respondió ${respuesta.statusCode()} al guardar $clave: ${respuesta.body().take(300)}"
        }
    }

    override fun abrir(clave: String): ArchivoAbierto? {
        val respuesta = http.send(
            HttpRequest.newBuilder(uri(clave))
                .timeout(Duration.ofMinutes(5))
                .header("x-ms-version", VERSION_API)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        return when (respuesta.statusCode()) {
            200 -> ArchivoAbierto(
                respuesta.headers().firstValueAsLong("Content-Length").orElse(-1),
                respuesta.body(),
            )
            404 -> null.also { respuesta.body().close() }
            else -> {
                val error = respuesta.body().use { String(it.readNBytes(300)) }
                error("Azure Blob respondió ${respuesta.statusCode()} al leer $clave: $error")
            }
        }
    }

    private companion object {
        const val VERSION_API = "2023-11-03"
    }
}
