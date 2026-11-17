package com.escom.sendero_seguro.reportes

import android.content.Context
import android.util.Log
import com.escom.sendero_seguro.cola.ColaAlertaSyncWorker
import com.escom.sendero_seguro.cola.GestorColaSincronizacion
import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class CatalogoReportesDto(
    val versionCatalogoId: Int,
    val numero: Int,
    val publicadaEn: String,
    val tipos: List<TipoReporteDto>,
)

@Serializable
data class ArchivoEvidenciaSubidoDto(
    val clave: String,
    val url: String,
    val mime: String,
    val tamanoBytes: Long,
    val hashSha256: String,
)

data class CatalogoReportesDisponible(
    val catalogo: CatalogoReportesDto,
    val desdeCache: Boolean,
)

@Serializable
data class TipoReporteDto(
    val id: Int,
    val codigo: String,
    val nombre: String,
    val aplicaA: String,
    val activo: Boolean = true,
    /** FISICO, SOCIAL o DIGITAL; null en la genérica. A-05 agrupa los botones por categoría. */
    val categoria: String? = null,
    val prioridad: Int? = null,
    val esGenerica: Boolean = false,
)

@Serializable
data class EvidenciaReporteDto(
    val clave: String,
    val mime: String,
    val tamanoBytes: Long,
    val hashSha256: String,
)

@Serializable
data class ReporteEntradaDto(
    val id: String,
    val tipoIncidenteId: Int,
    val versionCatalogoId: Int,
    val descripcion: String,
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val esAnonimo: Boolean,
    val creadoEn: String,
    val evidencias: List<EvidenciaReporteDto> = emptyList(),
)

@Serializable
data class ReporteRecibidoDto(
    val id: String,
    val folio: String,
    val estado: String,
    val celda: String,
)

@Serializable
data class ErrorReporteDto(
    val codigo: String,
    val mensaje: String,
    val detalle: JsonObject? = null,
)

@Serializable
data class ElementoLoteReporteDto(
    val tipo: String = "REPORTE",
    val id: String,
    val creadoEn: String,
    val datos: JsonObject,
)

@Serializable
data class LoteReporteDto(
    val dispositivoId: String,
    val elementos: List<ElementoLoteReporteDto>,
)

@Serializable
data class ResultadoLoteReporteDto(val resultados: List<ResultadoSyncReporteDto>)

@Serializable
data class ResultadoSyncReporteDto(
    val id: String,
    val resultado: String,
    val error: ErrorReporteDto? = null,
    val referencia: String? = null,
)

@Serializable
data class ReporteComunitarioPublicoDto(
    val id: String,
    val tipoIncidenteId: Int,
    val tipo: String,
    val categoria: String? = null,
    val descripcion: String,
    val celda: String,
    val latitud: Double,
    val longitud: Double,
    val referencia: String? = null,
    val autor: String,
    val publicadoEn: String,
    val distanciaM: Double? = null,
)

@Serializable
data class PaginaFeedReportesDto(
    val reportes: List<ReporteComunitarioPublicoDto>,
    val siguiente: String? = null,
)

@Serializable
data class CacheFeedReportesDto(
    val reportes: List<ReporteComunitarioPublicoDto>,
    val guardadoEn: Long,
)

data class FeedReportesDisponible(
    val reportes: List<ReporteComunitarioPublicoDto>,
    val guardadoEn: Long,
    val desdeCache: Boolean,
)

interface ReportesApi {
    @GET("catalogo")
    suspend fun catalogo(@Query("aplicaA") aplicaA: String = "REPORTE"): CatalogoReportesDto

    @POST("reportes")
    suspend fun enviar(@Body reporte: ReporteEntradaDto): Response<ReporteRecibidoDto>

    @POST("archivos/evidencia")
    suspend fun subirEvidencia(
        @Header("Content-Type") mime: String,
        @Body archivo: RequestBody,
    ): ArchivoEvidenciaSubidoDto

    @POST("sync/lote")
    suspend fun sincronizarLote(@Body lote: LoteReporteDto): Response<ResultadoLoteReporteDto>

    @GET("feed")
    suspend fun feed(
        @Query("dias") dias: Int = 30,
        @Query("limite") limite: Int = 100,
    ): PaginaFeedReportesDto
}

object ServidorReportesRetrofit {
    fun crear(baseUrl: String, tokenActual: () -> String?): ReportesApi {
        val auth = Interceptor { chain ->
            val request = chain.request().newBuilder().apply {
                tokenActual()?.let { header("Authorization", "Bearer $it") }
                header("Accept", "application/json")
            }.build()
            chain.proceed(request)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ClienteHttp.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ReportesApi::class.java)
    }
}

sealed interface ResultadoEnvioReporte {
    data class Recibido(
        val reporte: ReporteRecibidoDto,
        val payload: ReporteEntradaDto,
    ) : ResultadoEnvioReporte
    data class EnCola(
        val reporteId: String,
        val payload: ReporteEntradaDto,
    ) : ResultadoEnvioReporte
}

class ModuloReportes(
    context: Context,
    private val api: ReportesApi,
    private val obtenerDispositivoId: suspend () -> String?,
    private val obtenerUsuarioId: suspend () -> String?,
) {
    private val appContext = context.applicationContext
    private val cola = GestorColaSincronizacion(appContext)
    private val mutexSincronizacion = Mutex()
    private val preferenciasCatalogo = appContext.getSharedPreferences(PREFERENCIAS_CATALOGO, Context.MODE_PRIVATE)
    private val preferenciasFeed = appContext.getSharedPreferences(PREFERENCIAS_FEED, Context.MODE_PRIVATE)
    private val _feedPublico = MutableStateFlow<FeedReportesDisponible?>(null)
    val feedPublico: StateFlow<FeedReportesDisponible?> = _feedPublico.asStateFlow()

    suspend fun cargarCatalogo(): CatalogoReportesDisponible {
        val catalogo = try {
            api.catalogo()
        } catch (e: IOException) {
            return catalogoGuardado(e)
        } catch (e: HttpException) {
            if (!esErrorTransitorio(e.code())) throw e
            return catalogoGuardado(e)
        } catch (e: SerializationException) {
            Log.e(TAG, "El servidor devolvió un catálogo que no se pudo interpretar.", e)
            return catalogoGuardado(e)
        }

        if (catalogo.tipos.any { it.activo && (it.aplicaA == "REPORTE" || it.aplicaA == "AMBOS") }) {
            val contenido = ClienteHttp.json.encodeToString(CatalogoReportesDto.serializer(), catalogo)
            val guardado = withContext(Dispatchers.IO) {
                preferenciasCatalogo.edit().putString(CLAVE_CATALOGO, contenido).commit()
            }
            if (!guardado) Log.e(TAG, "No se pudo guardar el catálogo de reportes para uso sin conexión.")
        }
        return CatalogoReportesDisponible(catalogo, desdeCache = false)
    }

    /**
     * Tipos para la alerta (A-05): activos con aplica_a ALERTA o AMBOS. Se guardan en el teléfono para que la
     * cuenta regresiva muestre los botones aunque no haya señal. Sin catálogo guardado regresa una lista vacía
     * y la alerta sale como genérica (flujo 3a de CU-11).
     */
    suspend fun cargarCatalogoAlertas(): List<TipoReporteDto> {
        val catalogo = try {
            api.catalogo("ALERTA").also { remoto ->
                val contenido = ClienteHttp.json.encodeToString(CatalogoReportesDto.serializer(), remoto)
                withContext(Dispatchers.IO) { preferenciasCatalogo.edit().putString(CLAVE_CATALOGO_ALERTAS, contenido).commit() }
            }
        } catch (e: IOException) {
            catalogoAlertasGuardado()
        } catch (e: HttpException) {
            catalogoAlertasGuardado()
        } catch (e: SerializationException) {
            catalogoAlertasGuardado()
        }
        return catalogo?.tipos.orEmpty().filter { it.activo && (it.aplicaA == "ALERTA" || it.aplicaA == "AMBOS") }
    }

    private fun catalogoAlertasGuardado(): CatalogoReportesDto? =
        preferenciasCatalogo.getString(CLAVE_CATALOGO_ALERTAS, null)?.let {
            runCatching { ClienteHttp.json.decodeFromString(CatalogoReportesDto.serializer(), it) }.getOrNull()
        }

    private fun catalogoGuardado(errorServidor: Exception): CatalogoReportesDisponible {
        val contenido = preferenciasCatalogo.getString(CLAVE_CATALOGO, null) ?: throw errorServidor
        val catalogo = try {
            ClienteHttp.json.decodeFromString(CatalogoReportesDto.serializer(), contenido)
        } catch (e: SerializationException) {
            Log.e(TAG, "El catálogo de reportes guardado está dañado.", e)
            throw IOException("El catálogo guardado no se puede leer. Conéctate para descargarlo otra vez.", e)
        }
        return CatalogoReportesDisponible(catalogo, desdeCache = true)
    }

    suspend fun cargarFeedPublico(): FeedReportesDisponible {
        val pagina = try {
            api.feed()
        } catch (e: IOException) {
            return feedGuardado(e)
        } catch (e: HttpException) {
            if (!esErrorTransitorio(e.code())) throw e
            return feedGuardado(e)
        } catch (e: SerializationException) {
            Log.e(TAG, "El servidor devolvió un feed de reportes que no se pudo interpretar.", e)
            return feedGuardado(e)
        }

        val cache = CacheFeedReportesDto(pagina.reportes, System.currentTimeMillis())
        val contenido = ClienteHttp.json.encodeToString(CacheFeedReportesDto.serializer(), cache)
        val guardado = withContext(Dispatchers.IO) {
            preferenciasFeed.edit().putString(CLAVE_FEED, contenido).commit()
        }
        if (!guardado) Log.e(TAG, "No se pudo guardar el feed comunitario para uso sin conexión.")
        return FeedReportesDisponible(cache.reportes, cache.guardadoEn, desdeCache = false).also {
            _feedPublico.value = it
        }
    }

    private fun feedGuardado(errorServidor: Exception): FeedReportesDisponible {
        val contenido = preferenciasFeed.getString(CLAVE_FEED, null) ?: throw errorServidor
        val cache = try {
            ClienteHttp.json.decodeFromString(CacheFeedReportesDto.serializer(), contenido)
        } catch (e: SerializationException) {
            Log.e(TAG, "El feed comunitario guardado está dañado.", e)
            throw IOException("La copia local de los reportes no se puede leer. Conéctate para descargarla otra vez.", e)
        }
        return FeedReportesDisponible(cache.reportes, cache.guardadoEn, desdeCache = true).also {
            _feedPublico.value = it
        }
    }

    private suspend fun enviar(reporte: ReporteEntradaDto): ReporteRecibidoDto {
        val respuesta = api.enviar(reporte)
        if (!respuesta.isSuccessful) throw HttpException(respuesta)
        return respuesta.body() ?: throw IOException("El servidor respondió sin los datos del reporte")
    }

    suspend fun enviarOEncolar(
        reporte: ReporteEntradaDto,
        evidenciasLocales: List<EvidenciaReporteLocal> = emptyList(),
    ): ResultadoEnvioReporte {
        if (evidenciasLocales.size > MAX_EVIDENCIAS_POR_REPORTE) {
            throw IOException("Un reporte puede incluir hasta $MAX_EVIDENCIAS_POR_REPORTE fotos o audios.")
        }
        var payload = reporte
        if (payload.evidencias.size > evidenciasLocales.size) {
            throw IOException("No coinciden los archivos locales con las evidencias del reporte.")
        }
        try {
            for (indice in payload.evidencias.size until evidenciasLocales.size) {
                val local = evidenciasLocales[indice]
                val respuesta = try {
                    api.subirEvidencia(
                        local.mime,
                        local.archivo.asRequestBody(local.mime.toMediaType()),
                    )
                } catch (e: IOException) {
                    return encolarYResultado(payload, evidenciasLocales)
                } catch (e: HttpException) {
                    if (!esErrorTransitorio(e.code())) throw e
                    return encolarYResultado(payload, evidenciasLocales)
                }
                payload = payload.copy(
                    evidencias = payload.evidencias + EvidenciaReporteDto(
                        clave = respuesta.clave,
                        mime = respuesta.mime,
                        tamanoBytes = respuesta.tamanoBytes,
                        hashSha256 = respuesta.hashSha256,
                    )
                )
            }
            val recibido = enviar(payload)
            eliminarEvidenciasLocales(evidenciasLocales.map { it.archivo.path })
            return ResultadoEnvioReporte.Recibido(recibido, payload)
        } catch (e: IOException) {
            return encolarYResultado(payload, evidenciasLocales)
        } catch (e: HttpException) {
            if (!esErrorTransitorio(e.code())) throw e
            return encolarYResultado(payload, evidenciasLocales)
        }
    }

    suspend fun hayReportesPendientes(): Boolean {
        descartarYLimpiarReportesVencidos()
        return cola.obtenerReportesPendientes().isNotEmpty()
    }

    suspend fun sincronizarColaOffline(): Boolean = mutexSincronizacion.withLock {
        descartarYLimpiarReportesVencidos()
        val pendientes = cola.obtenerReportesPendientes()
        if (pendientes.isEmpty()) return@withLock true

        val dispositivoId = obtenerDispositivoId()
        if (dispositivoId.isNullOrBlank()) {
            Log.w(TAG, "Hay reportes pendientes, pero falta el dispositivoId de la sesión.")
            return@withLock false
        }
        val usuarioId = obtenerUsuarioId()
        if (usuarioId.isNullOrBlank()) {
            Log.w(TAG, "Hay reportes pendientes, pero falta el usuarioId de la sesión.")
            return@withLock false
        }

        var pendienteDeOtraSesion = false
        pendientesLoop@ for (pendiente in pendientes) {
            val rutasLocales = rutasEvidencia(pendiente.rutasEvidencia)
            if (pendiente.usuarioId != usuarioId) {
                Log.w(TAG, "El reporte ${pendiente.reporteId} espera la cuenta con la que fue creado.")
                pendienteDeOtraSesion = true
                continue
            }
            val reporte = try {
                ClienteHttp.json.decodeFromString(ReporteEntradaDto.serializer(), pendiente.datos)
            } catch (e: SerializationException) {
                Log.e(TAG, "Se descarta un reporte en cola con JSON inválido [ID: ${pendiente.reporteId}].", e)
                cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                eliminarEvidenciasLocales(rutasLocales)
                continue
            }
            if (reporte.id != pendiente.reporteId) {
                Log.e(TAG, "Se descarta un reporte cuyo ID no coincide con la fila de cola [ID: ${pendiente.reporteId}].")
                cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                eliminarEvidenciasLocales(rutasLocales)
                continue
            }
            if (reporte.evidencias.size > rutasLocales.size) {
                Log.e(TAG, "El reporte en cola tiene más evidencias subidas que archivos locales [ID: ${pendiente.reporteId}].")
                cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                eliminarEvidenciasLocales(rutasLocales)
                continue
            }

            var reporteConEvidencias = reporte
            for (indice in reporte.evidencias.size until rutasLocales.size) {
                val archivo = File(rutasLocales[indice])
                val mime = mimeEvidenciaLocal(archivo)
                if (!archivo.isFile || mime == null) {
                    Log.e(TAG, "No se puede leer una evidencia local del reporte ${pendiente.reporteId}.")
                    cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                    eliminarEvidenciasLocales(rutasLocales)
                    continue@pendientesLoop
                }
                val subida = try {
                    api.subirEvidencia(mime, archivo.asRequestBody(mime.toMediaType()))
                } catch (e: IOException) {
                    cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                    Log.w(TAG, "No se pudo subir una evidencia del reporte ${pendiente.reporteId}.", e)
                    return@withLock false
                } catch (e: HttpException) {
                    if (esErrorTransitorio(e.code())) {
                        cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                        Log.w(TAG, "El servidor respondió HTTP ${e.code()} al subir evidencia del reporte.", e)
                        return@withLock false
                    }
                    Log.e(TAG, "El servidor rechazó una evidencia del reporte ${pendiente.reporteId}.", e)
                    cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                    eliminarEvidenciasLocales(rutasLocales)
                    continue@pendientesLoop
                }
                reporteConEvidencias = reporteConEvidencias.copy(
                    evidencias = reporteConEvidencias.evidencias + EvidenciaReporteDto(
                        clave = subida.clave,
                        mime = subida.mime,
                        tamanoBytes = subida.tamanoBytes,
                        hashSha256 = subida.hashSha256,
                    )
                )
                val datosGuardados = ClienteHttp.json.encodeToString(
                    ReporteEntradaDto.serializer(),
                    reporteConEvidencias,
                )
                if (cola.actualizarDatosReporte(pendiente.reporteId, datosGuardados) == 0) {
                    cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                    Log.w(TAG, "No se pudo persistir el avance de evidencia del reporte ${pendiente.reporteId}.")
                    return@withLock false
                }
            }

            val datos = ClienteHttp.json.encodeToJsonElement(
                ReporteEntradaDto.serializer(),
                reporteConEvidencias,
            ) as? JsonObject
            if (datos == null) {
                cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                Log.w(TAG, "No se pudo convertir a objeto JSON el reporte ${pendiente.reporteId}.")
                return@withLock false
            }
            val respuesta = try {
                api.sincronizarLote(
                    LoteReporteDto(
                        dispositivoId = dispositivoId,
                        elementos = listOf(
                            ElementoLoteReporteDto(
                                id = pendiente.reporteId,
                                creadoEn = reporteConEvidencias.creadoEn,
                                datos = datos,
                            )
                        ),
                    )
                )
            } catch (e: IOException) {
                cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                Log.w(TAG, "No se pudo sincronizar el reporte en cola [ID: ${pendiente.reporteId}].", e)
                return@withLock false
            }

            if (!respuesta.isSuccessful) {
                cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                Log.w(TAG, "El servidor respondió HTTP ${respuesta.code()} al sincronizar un reporte en cola.")
                if (esErrorTransitorio(respuesta.code())) return@withLock false
                cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                eliminarEvidenciasLocales(rutasLocales)
                continue
            }

            val resultado = respuesta.body()?.resultados?.singleOrNull { it.id == pendiente.reporteId }
            if (resultado == null) {
                cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                Log.w(TAG, "La respuesta del servidor no incluyó el resultado del reporte ${pendiente.reporteId}.")
                return@withLock false
            }

            when (resultado.resultado) {
                "ACEPTADO", "DUPLICADO" -> {
                    cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                    eliminarEvidenciasLocales(rutasLocales)
                    AvisoReporteUsuario.guardarYNotificar(
                        appContext,
                        pendiente.reporteId,
                        resultado.resultado,
                    )
                }
                "VENCIDO" -> {
                    cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                    eliminarEvidenciasLocales(rutasLocales)
                }
                "RECHAZADO" -> {
                    if (resultado.error?.esTemporal() == true) {
                        cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                        Log.w(TAG, "El servidor pidió reintentar el reporte ${pendiente.reporteId}.")
                        return@withLock false
                    }
                    Log.e(
                        TAG,
                        "El servidor rechazó permanentemente el reporte ${pendiente.reporteId}: " +
                            (resultado.error?.mensaje ?: "sin detalle")
                    )
                    cola.marcarSincronizadoYEliminar(pendiente.reporteId, esAlerta = false)
                    eliminarEvidenciasLocales(rutasLocales)
                }
                else -> {
                    cola.registrarFalloReintento(pendiente.reporteId, esAlerta = false)
                    Log.w(TAG, "Resultado desconocido al sincronizar el reporte ${pendiente.reporteId}.")
                    return@withLock false
                }
            }
        }
        !pendienteDeOtraSesion && cola.obtenerReportesPendientes().isEmpty()
    }

    private suspend fun encolarYResultado(
        reporte: ReporteEntradaDto,
        evidenciasLocales: List<EvidenciaReporteLocal>,
    ): ResultadoEnvioReporte.EnCola {
        val usuarioId = obtenerUsuarioId()
            ?: throw IOException("No hay una cuenta de sesión para asociar el reporte a la cola")
        val datos = ClienteHttp.json.encodeToString(ReporteEntradaDto.serializer(), reporte)
        cola.encolarReporteComunitario(
            reporteId = reporte.id,
            usuarioId = usuarioId,
            datosJson = datos,
            rutasEvidencia = evidenciasLocales.map { it.archivo.path },
            tamanoBytes = datos.toByteArray(Charsets.UTF_8).size.toLong() +
                evidenciasLocales.sumOf { it.archivo.length() },
        )
        ColaAlertaSyncWorker.programarInmediato(appContext)
        return ResultadoEnvioReporte.EnCola(reporte.id, reporte)
    }

    private suspend fun descartarYLimpiarReportesVencidos() {
        cola.obtenerReportesExpirados().forEach { expirado ->
            eliminarEvidenciasLocales(rutasEvidencia(expirado.rutasEvidencia))
        }
        cola.descartarYRegistrarVencidos()
    }

    private fun eliminarEvidenciasLocales(rutas: List<String>) {
        val directorio = try {
            File(appContext.filesDir, DIRECTORIO_EVIDENCIA).canonicalFile
        } catch (e: IOException) {
            Log.e(TAG, "No se pudo resolver el directorio de evidencias locales.", e)
            return
        }
        rutas.forEach { ruta ->
            try {
                val archivo = File(ruta).canonicalFile
                if (archivo.parentFile != directorio) {
                    Log.w(TAG, "Se omitió limpiar una ruta de evidencia fuera del directorio privado de la app.")
                } else if (archivo.exists() && !archivo.delete()) {
                    Log.e(TAG, "No se pudo borrar una evidencia local después de terminar su sincronización.")
                }
            } catch (e: IOException) {
                Log.e(TAG, "No se pudo resolver una ruta de evidencia local para limpiarla.", e)
            }
        }
    }

    private fun rutasEvidencia(valor: String?): List<String> =
        valor?.split(";")?.filter(String::isNotBlank).orEmpty()

    companion object {
        private const val TAG = "P11_REPORTE_SYNC"
        private const val MAX_EVIDENCIAS_POR_REPORTE = 3
        private const val DIRECTORIO_EVIDENCIA = "reportes_evidencia"
        private const val PREFERENCIAS_CATALOGO = "catalogo_reportes_offline"
        private const val CLAVE_CATALOGO = "ultimo_catalogo"
        private const val CLAVE_CATALOGO_ALERTAS = "ultimo_catalogo_alertas"
        private const val PREFERENCIAS_FEED = "feed_reportes_offline"
        private const val CLAVE_FEED = "reportes_30_dias"
    }

    private fun esErrorTransitorio(codigo: Int): Boolean =
        codigo == 408 || codigo == 429 || codigo == 401 || codigo == 403 || codigo >= 500

    private fun ErrorReporteDto.esTemporal(): Boolean =
        (detalle?.get("temporal") as? JsonPrimitive)?.booleanOrNull == true
}
