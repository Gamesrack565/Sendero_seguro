package com.senderoseguro.server.archivos

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.exigirRol
import com.senderoseguro.server.plataforma.usuarioActual
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Qué se puede subir, quién y de qué tamaño. La carpeta del almacén es el nombre en minúsculas. */
enum class TipoArchivo(val roles: Set<Rol>, val mimes: Map<String, String>) {
    /** Fotos y audios de un reporte comunitario (tabla `evidencia`, RF-19). Cualquier cuenta. */
    EVIDENCIA(
        Rol.entries.toSet(),
        mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp",
            "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg",
        ),
    ),

    /** Paquetes de mapas y ruteo offline (tabla `paquete_mapa`, P12). Solo el Administrador los publica. */
    MAPA(
        setOf(Rol.ADMINISTRADOR),
        mapOf("application/zip" to "zip", "application/octet-stream" to "bin"),
    ),
    ;

    val carpeta = name.lowercase()
}

/** Tamaño máximo de cada tipo, en bytes. */
data class LimitesArchivos(val evidenciaBytes: Long, val mapaBytes: Long) {
    fun de(tipo: TipoArchivo) = when (tipo) {
        TipoArchivo.EVIDENCIA -> evidenciaBytes
        TipoArchivo.MAPA -> mapaBytes
    }
}

@Serializable
data class ArchivoGuardado(
    /** Lo que se guarda en `evidencia.url_objeto` o `paquete_mapa.ruta_archivo`. */
    val clave: String,
    /** Ruta para descargarlo, relativa a `/api/v1`. */
    val url: String,
    val mime: String,
    val tamanoBytes: Long,
    /** Lo que se guarda en `hash_sha256`. */
    val hashSha256: String,
)

private val NOMBRE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{2,5}$")

/**
 * - `POST /archivos/{tipo}` (`evidencia` o `mapa`): sube un archivo. El cuerpo es el archivo tal cual, con su
 *   `Content-Type`. Responde 201 con la clave, el tamaño y el SHA-256 para guardarlos en la tabla del módulo.
 * - `GET /archivos/{tipo}/{nombre}`: lo descarga. Cualquier cuenta con sesión.
 */
fun Route.rutasArchivos(almacen: Almacen, limites: LimitesArchivos) {
    authenticate(AUTH_JWT) {
        post("/archivos/{tipo}") {
            val tipo = tipo(call.parameters["tipo"])
            call.exigirRol(*tipo.roles.toTypedArray())
            val mime = call.request.contentType().withoutParameters().toString().lowercase()
            val extension = tipo.mimes[mime] ?: throw ApiException(
                HttpStatusCode.UnsupportedMediaType, "TIPO_NO_PERMITIDO",
                "Content-Type no permitido para ${tipo.carpeta}. Permitidos: ${tipo.mimes.keys.joinToString()}",
            )
            val maximo = limites.de(tipo)
            call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let { if (it > maximo) throw muyGrande(maximo) }

            val clave = "${tipo.carpeta}/${UUID.randomUUID()}.$extension"
            val guardado = withContext(Dispatchers.IO) {
                val temporal = Files.createTempFile("sendero-archivo-", ".$extension")
                try {
                    val (tamano, hash) = call.receiveStream().use { copiarConLimite(it, temporal, maximo) }
                    if (tamano == 0L) throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El archivo está vacío")
                    almacen.guardar(clave, mime, temporal)
                    ArchivoGuardado(clave, "/archivos/$clave", mime, tamano, hash)
                } finally {
                    Files.deleteIfExists(temporal)
                }
            }
            call.respond(HttpStatusCode.Created, guardado)
        }

        get("/archivos/{tipo}/{nombre}") {
            call.usuarioActual()
            val tipo = tipo(call.parameters["tipo"])
            val nombre = call.parameters["nombre"].orEmpty()
            val extension = nombre.substringAfterLast('.')
            val mime = tipo.mimes.entries.firstOrNull { it.value == extension }?.key
            if (!NOMBRE.matches(nombre) || mime == null) throw noExiste()
            val archivo = withContext(Dispatchers.IO) { almacen.abrir("${tipo.carpeta}/$nombre") } ?: throw noExiste()
            // El nombre es único y nunca se reescribe: el teléfono puede guardarlo en caché.
            call.response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
            call.respondOutputStream(ContentType.parse(mime), contentLength = archivo.tamanoBytes.takeIf { it >= 0 }) {
                archivo.contenido.use { it.transferTo(this) }
            }
        }
    }
}

private fun tipo(valor: String?): TipoArchivo =
    TipoArchivo.entries.firstOrNull { it.carpeta == valor }
        ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "Tipo de archivo desconocido: usa evidencia o mapa")

private fun noExiste() = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "El archivo no existe")

private fun muyGrande(maximo: Long) = ApiException(
    HttpStatusCode.PayloadTooLarge, "MUY_GRANDE", "El archivo pasa del máximo de ${maximo / (1024 * 1024)} MB",
)

/** Copia [entrada] a [destino] sin pasar de [maximo] bytes. Regresa el tamaño y el SHA-256 en hexadecimal. */
private fun copiarConLimite(entrada: InputStream, destino: Path, maximo: Long): Pair<Long, String> {
    val sha = MessageDigest.getInstance("SHA-256")
    var total = 0L
    Files.newOutputStream(destino).use { salida ->
        val bufer = ByteArray(64 * 1024)
        while (true) {
            val n = entrada.read(bufer)
            if (n < 0) break
            total += n
            if (total > maximo) throw muyGrande(maximo)
            sha.update(bufer, 0, n)
            salida.write(bufer, 0, n)
        }
    }
    return total to HexFormat.of().formatHex(sha.digest())
}
