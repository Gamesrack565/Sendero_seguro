package com.escom.sendero_seguro.mapas

import android.content.Context
import android.util.Log
import com.escom.sendero_seguro.cola.BaseDatosLocal
import com.escom.sendero_seguro.cola.CacheMapaEntity
import com.escom.sendero_seguro.mapas.remoto.GrafoMapaDto
import com.escom.sendero_seguro.mapas.remoto.CapaRiesgoDto
import com.escom.sendero_seguro.mapas.remoto.MapasApi
import com.escom.sendero_seguro.mapas.remoto.PaqueteMapaDto
import com.escom.sendero_seguro.mapas.remoto.VersionesMapasDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

data class EstadoMapaP12(
    val capaRiesgo: CapaRiesgoDto? = null,
    val descargadaCapaEn: Long? = null,
    val grafoVial: GrafoMapaDto? = null,
    val versionGrafo: Int? = null,
    val descargadoGrafoEn: Long? = null,
    val cantidadNodosGrafo: Int? = null,
    val archivoMbtilesLocal: String? = null,
    val tamanoCapaBytes: Long = 0L,
    val tamanoGrafoBytes: Long = 0L,
    val tamanoPaquetesBytes: Long = 0L,
    val paquetesDescargados: Int = 0,
    val revisionPaquetesOffline: String = "",
    val sincronizando: Boolean = false,
    val ultimaComprobacion: Long? = null,
    val mensaje: String = "Cargando caché local",
    val error: String? = null,
) {
    val tamanoTotalBytes: Long
        get() = tamanoCapaBytes + tamanoGrafoBytes + tamanoPaquetesBytes
}

class ModuloMapas(context: Context, private val api: MapasApi) {
    private val appContext = context.applicationContext
    private val cacheDao = BaseDatosLocal.obtenerInstancia(appContext).cacheMapaDao()
    private val sincronizacionMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _estado = MutableStateFlow(EstadoMapaP12())
    val estado: StateFlow<EstadoMapaP12> = _estado.asStateFlow()

    suspend fun cargarCacheLocal() = withContext(Dispatchers.IO) {
        val filas = cacheDao.obtenerTodas()
        val capaLocal = filas.firstOrNull { it.tesela == CLAVE_CAPA }
        val grafoLocal = filas.firstOrNull { it.tesela == CLAVE_GRAFO }
        val paquetesLocales = filas.filter { it.tesela.startsWith(PREFIJO_PAQUETE) }
        val paquetesVectoriales = paquetesLocales.filter { fila ->
            fila.archivoLocal?.let(::File)?.let {
                it.isFile && it.extension.equals("mbtiles", ignoreCase = true)
            } == true
        }
        val archivosPaquetes = paquetesVectoriales.mapNotNull { it.archivoLocal?.let(::File) }
        val archivoMbtilesLocal = paquetesLocales
            .firstOrNull { it.tesela == clavePaquete(REGION_MAPA_VECTORIAL) }
            ?.archivoLocal
            ?.let(::File)
            ?.takeIf { it.isFile && it.extension.equals("mbtiles", ignoreCase = true) }
        var error: String? = null
        val capa = capaLocal?.geojsonRiesgo?.let { contenido ->
            try {
                json.decodeFromString<CapaRiesgoDto>(contenido)
            } catch (e: SerializationException) {
                Log.e(TAG, "La caché local del mapa de calor no se pudo leer.", e)
                cacheDao.eliminarTesela(CLAVE_CAPA)
                error = "La copia local del mapa estaba dañada; se volverá a descargar."
                null
            }
        }
        val grafo = grafoLocal?.grafo?.let { contenido ->
            try {
                json.decodeFromString<GrafoMapaDto>(contenido.toString(Charsets.UTF_8))
            } catch (e: SerializationException) {
                Log.e(TAG, "La caché local del grafo vial no se pudo leer.", e)
                cacheDao.eliminarTesela(CLAVE_GRAFO)
                error = listOfNotNull(
                    error,
                    "La copia local del grafo estaba dañada; se volverá a descargar.",
                ).joinToString(" ")
                null
            }
        }
        _estado.update {
            it.copy(
                capaRiesgo = capa,
                descargadaCapaEn = capaLocal?.descargadoEn.takeIf { capa != null },
                grafoVial = grafo,
                versionGrafo = grafoLocal?.versionContenido?.toIntOrNull().takeIf { grafo != null },
                descargadoGrafoEn = grafoLocal?.descargadoEn.takeIf { grafo != null },
                cantidadNodosGrafo = grafo?.nodos?.size,
                archivoMbtilesLocal = archivoMbtilesLocal?.absolutePath,
                tamanoCapaBytes = capa?.let {
                    capaLocal?.geojsonRiesgo?.toByteArray(Charsets.UTF_8)?.size?.toLong()
                } ?: 0L,
                tamanoGrafoBytes = if (grafo != null) grafoLocal?.grafo?.size?.toLong() ?: 0L else 0L,
                tamanoPaquetesBytes = archivosPaquetes.sumOf { it.length() },
                paquetesDescargados = archivosPaquetes.size,
                revisionPaquetesOffline = paquetesVectoriales.sortedBy { it.tesela }
                    .joinToString("|") { "${it.tesela}:${it.versionContenido}:${it.descargadoEn}" },
                mensaje = if (capa == null) "No hay mapa de calor descargado" else "Mapa disponible sin conexión",
                error = error,
            )
        }
    }

    suspend fun obtenerGrafoOfflineP7(): GrafoOfflineP7? = withContext(Dispatchers.IO) {
        val local = cacheDao.obtenerPorTesela(CLAVE_GRAFO) ?: return@withContext null
        val bytes = local.grafo ?: return@withContext null
        val grafo = try {
            json.decodeFromString<GrafoMapaDto>(bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw IOException("El grafo guardado no se puede interpretar para P7.", e)
        }
        val versionGrafo = local.versionContenido?.toIntOrNull()
            ?: grafo.versionGrafo
            ?: throw IOException("El grafo guardado no tiene una versión válida.")
        grafo.aGrafoOfflineP7(versionGrafo, local.versionCapa)
    }

    suspend fun sincronizar(): EstadoMapaP12 = sincronizacionMutex.withLock {
        _estado.update { it.copy(sincronizando = true, error = null, mensaje = "Comprobando versiones") }
        try {
            val versiones = api.versiones()
            var mapaActualizado = false
            var grafoActualizado = false
            var paquetesActualizados = 0

            val capaLocal = cacheDao.obtenerPorTesela(CLAVE_CAPA)
            if (versiones.capaRiesgo != null &&
                (capaLocal?.versionCapa != versiones.capaRiesgo || capaLocal.geojsonRiesgo == null)
            ) {
                val respuesta = api.mapaCalor(PERIODO_MAPA)
                if (respuesta.code() == 404) {
                    respuesta.errorBody()?.close()
                    throw IOException("El servidor anunció una capa de riesgo, pero no se pudo descargar.")
                } else {
                    val capa = respuesta.cuerpoValido("mapa de calor")
                    if (capa.version != versiones.capaRiesgo) {
                        throw IOException("La versión del mapa cambió mientras se descargaba; se reintentará.")
                    }
                    cacheDao.guardarTesela(
                        CacheMapaEntity(
                            tesela = CLAVE_CAPA,
                            versionCapa = capa.version,
                            geojsonRiesgo = json.encodeToString(capa),
                            grafo = null,
                            versionContenido = capa.generadaEn,
                        )
                    )
                    mapaActualizado = true
                }
            }

            val versionGrafo = versiones.grafo ?: 0
            val grafoLocal = cacheDao.obtenerPorTesela(CLAVE_GRAFO)
            if (versionGrafo > 0 && grafoLocal?.versionContenido?.toIntOrNull() != versionGrafo) {
                val grafo = api.grafo(
                    minLatitud = CAJA_MIN_LAT,
                    minLongitud = CAJA_MIN_LON,
                    maxLatitud = CAJA_MAX_LAT,
                    maxLongitud = CAJA_MAX_LON,
                ).cuerpoValido("grafo vial")
                if (grafo.versionGrafo != null && grafo.versionGrafo != versionGrafo) {
                    throw IOException("La versión del grafo cambió mientras se descargaba; se reintentará.")
                }
                cacheDao.guardarTesela(
                    CacheMapaEntity(
                        tesela = CLAVE_GRAFO,
                        versionCapa = grafo.capaVersion ?: 0,
                        geojsonRiesgo = null,
                        grafo = json.encodeToString(grafo).toByteArray(Charsets.UTF_8),
                        versionContenido = versionGrafo.toString(),
                    )
                )
                grafoActualizado = true
            }

            if (cambiaronPaquetes(versiones)) {
                val paquetes = api.paquetes()
                validarVersionesPaquetes(versiones, paquetes)
                for (paquete in paquetes) {
                    if (descargarPaqueteSiCambio(paquete)) paquetesActualizados++
                }
                eliminarPaquetesRetirados(paquetes)
            }

            cargarCacheLocal()
            val cambios = listOfNotNull(
                "mapa".takeIf { mapaActualizado },
                "grafo".takeIf { grafoActualizado },
                "$paquetesActualizados paquete(s)".takeIf { paquetesActualizados > 0 },
            )
            _estado.update {
                it.copy(
                    sincronizando = false,
                    ultimaComprobacion = System.currentTimeMillis(),
                    mensaje = if (cambios.isEmpty()) "Todo está actualizado" else "Actualizado: ${cambios.joinToString()}",
                    error = null,
                )
            }
            _estado.value
        } catch (e: CancellationException) {
            _estado.update { it.copy(sincronizando = false) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo sincronizar el mapa P12.", e)
            _estado.update {
                it.copy(sincronizando = false, mensaje = "Se conserva la última copia local", error = "No se pudo comprobar el servidor: ${e.message ?: "error de conexión"}")
            }
            throw e
        }
    }

    private suspend fun cambiaronPaquetes(versiones: VersionesMapasDto): Boolean {
        val locales = cacheDao.obtenerTodas().filter { it.tesela.startsWith(PREFIJO_PAQUETE) }
            .associateBy { it.tesela }
        val disponibles = versiones.paquetes.associateBy { clavePaquete(it.region) }
        if (locales.keys != disponibles.keys) return true
        return disponibles.any { (clave, paquete) ->
            val local = locales[clave]
            local == null || local.versionContenido != paquete.version ||
                local.archivoLocal?.let(::File)?.let {
                    it.isFile && it.extension.equals("mbtiles", ignoreCase = true)
                } != true
        }
    }

    private fun validarVersionesPaquetes(
        versiones: VersionesMapasDto,
        paquetes: List<PaqueteMapaDto>,
    ) {
        val disponibles = versiones.paquetes.associate { clavePaquete(it.region) to it.version }
        val publicados = paquetes.associate { clavePaquete(it.region) to it.version }
        if (disponibles != publicados) {
            throw IOException("Las versiones de paquetes cambiaron durante la consulta; se reintentará.")
        }
    }

    private suspend fun descargarPaqueteSiCambio(paquete: PaqueteMapaDto): Boolean {
        val clave = clavePaquete(paquete.region)
        val local = cacheDao.obtenerPorTesela(clave)
        val archivoLocal = local?.archivoLocal?.let(::File)
        if (local?.versionContenido == paquete.version && archivoLocal?.isFile == true) return false

        if (paquete.tamanoBytes <= 0L || paquete.tamanoBytes > MAX_PAQUETE_BYTES) {
            throw IOException("El paquete de ${paquete.region} tiene un tamaño no permitido.")
        }
        val nombre = nombreArchivo(paquete.url)
        val raiz = PaquetesMapaOffline.directorio(appContext).apply { mkdirs() }
        val extension = nombre.substringAfterLast('.')
        if (!extension.equals("mbtiles", ignoreCase = true)) {
            throw IOException("El paquete de mapa debe ser vectorial MBTiles; no se aceptan ZIP raster.")
        }
        val versionSegura = paquete.version.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
        val destino = File(raiz, "${sha256(paquete.region)}-$versionSegura.$extension").canonicalFile
        if (!destino.path.startsWith(raiz.canonicalPath + File.separator)) {
            throw IOException("La ruta del paquete de ${paquete.region} no es válida.")
        }
        val temporal = File(destino.parentFile, "${destino.name}.tmp")
        val respuesta = api.descargarPaquete(nombre)
        if (!respuesta.isSuccessful) {
            respuesta.errorBody()?.close()
            throw IOException("El servidor respondió HTTP ${respuesta.code()} al descargar el mapa ${paquete.region}.")
        }
        val cuerpo = respuesta.body() ?: throw IOException("El servidor devolvió vacío el paquete ${paquete.region}.")
        val (bytes, hash) = try {
            cuerpo.guardarTemporalmente(temporal)
        } catch (e: IOException) {
            temporal.delete()
            throw e
        }
        if (bytes != paquete.tamanoBytes || !hash.equals(paquete.hashSha256, ignoreCase = true)) {
            temporal.delete()
            throw IOException("El tamaño o SHA-256 del mapa ${paquete.region} no coincide con el servidor.")
        }
        try {
            PaquetesMapaOffline.validarArchivoMbtiles(temporal)
        } catch (e: IOException) {
            temporal.delete()
            throw e
        }
        if (!temporal.renameTo(destino)) {
            temporal.delete()
            throw IOException("No se pudo guardar el paquete de mapa ${paquete.region}.")
        }
        cacheDao.guardarTesela(
            CacheMapaEntity(
                tesela = clave,
                versionCapa = 0,
                geojsonRiesgo = null,
                grafo = null,
                versionContenido = paquete.version,
                archivoLocal = destino.path,
            )
        )
        local?.archivoLocal?.let(::File)?.takeIf { it != destino && it.isFile }?.delete()
        return true
    }

    private suspend fun eliminarPaquetesRetirados(paquetes: List<PaqueteMapaDto>) {
        val activos = paquetes.mapTo(mutableSetOf()) { clavePaquete(it.region) }
        cacheDao.obtenerTodas()
            .filter { it.tesela.startsWith(PREFIJO_PAQUETE) && it.tesela !in activos }
            .forEach { fila ->
                fila.archivoLocal?.let(::File)?.takeIf { it.isFile }?.delete()
                cacheDao.eliminarTesela(fila.tesela)
            }
    }

    private fun nombreArchivo(url: String): String {
        val prefijo = "/archivos/mapa/"
        val nombre = url.takeIf { it.startsWith(prefijo) }?.removePrefix(prefijo)
        if (nombre == null || !NOMBRE_PAQUETE.matches(nombre)) {
            throw IOException("El servidor devolvió una ruta de paquete inválida.")
        }
        return nombre
    }

    private fun Response<CapaRiesgoDto>.cuerpoValido(nombre: String): CapaRiesgoDto =
        cuerpoValido(this, nombre)

    private fun Response<GrafoMapaDto>.cuerpoValido(nombre: String): GrafoMapaDto =
        cuerpoValido(this, nombre)

    private fun <T : Any> cuerpoValido(respuesta: Response<T>, nombre: String): T {
        if (!respuesta.isSuccessful) {
            respuesta.errorBody()?.close()
            throw IOException("El servidor respondió HTTP ${respuesta.code()} al descargar el $nombre.")
        }
        return respuesta.body() ?: throw IOException("El servidor devolvió vacío el $nombre.")
    }

    private fun ResponseBody.guardarTemporalmente(destino: File): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        byteStream().use { entrada ->
            FileOutputStream(destino).use { salida ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val leidos = entrada.read(buffer)
                    if (leidos < 0) break
                    total += leidos
                    if (total > MAX_PAQUETE_BYTES) throw IOException("El paquete excede el máximo permitido.")
                    digest.update(buffer, 0, leidos)
                    salida.write(buffer, 0, leidos)
                }
            }
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(texto: String): String =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "P12_Mapas"
        const val PERIODO_MAPA = 30
        const val CLAVE_CAPA = "p12:mapa-calor:30"
        const val CLAVE_GRAFO = "p12:grafo:escom"
        const val PREFIJO_PAQUETE = "p12:paquete:"
        const val MAX_PAQUETE_BYTES = 200L * 1024 * 1024
        const val CAJA_MIN_LAT = 19.4945
        const val CAJA_MIN_LON = -99.1569
        const val CAJA_MAX_LAT = 19.5145
        const val CAJA_MAX_LON = -99.1369
        const val REGION_MAPA_VECTORIAL = "CDMX"
        val NOMBRE_PAQUETE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(mbtiles|zip|bin)$")

        fun clavePaquete(region: String): String =
            "$PREFIJO_PAQUETE${MessageDigest.getInstance("SHA-256").digest(region.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }}"
    }
}
