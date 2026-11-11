package com.escom.sendero_seguro.emergencias.remoto

import com.escom.sendero_seguro.emergencias.AlertaEmergencia
import com.escom.sendero_seguro.emergencias.CanalRecepcion
import com.escom.sendero_seguro.emergencias.DatosTelemetria
import com.escom.sendero_seguro.emergencias.TipoDetonador
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

// P4 y P6: registro de la alerta en el servidor (Módulo 4) y sus ubicaciones mientras sigue activa, contrato
// `POST /alertas` y `POST /alertas/{id}/ubicaciones` de docs/api/openapi.yaml.

@Serializable
data class UbicacionNuevaDto(
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val bateria: Int? = null,
    val fuente: String = "GNSS",
    val capturadaEn: String,
)

@Serializable
data class AlertaNuevaDto(
    val id: String,
    val tipoIncidenteId: Int? = null,
    val descripcion: String? = null,
    val canalRecepcion: String = "INTERNET",
    val detonador: String,
    val nivelBateria: Int? = null,
    val activadaEn: String,
    val enviadaEn: String,
    val ubicacion: UbicacionNuevaDto,
)

@Serializable
data class AlertaRegistradaDto(
    val id: String,
    val folio: String,
    val estado: String,
    val registradaEn: String,
    val enlaceSeguimiento: String? = null,
)

/** P6: una lectura de ubicación mientras la alerta sigue activa (`POST /alertas/{id}/ubicaciones`). */
@Serializable
data class LecturaUbicacionDto(
    val latitud: Double,
    val longitud: Double,
    val precisionM: Double? = null,
    val velocidadMs: Double? = null,
    val rumboGrados: Int? = null,
    val bateria: Int? = null,
    val fuente: String = "GNSS",
    val capturadaEn: String,
)

@Serializable
data class LoteUbicacionesDto(val lecturas: List<LecturaUbicacionDto>)

@Serializable
data class UbicacionesAgregadasDto(val insertadas: Int, val duplicadas: Int)

interface EmergenciasApi {
    @POST("alertas")
    suspend fun registrarAlerta(@Body body: AlertaNuevaDto): Response<AlertaRegistradaDto>

    @POST("alertas/{id}/ubicaciones")
    suspend fun agregarUbicaciones(@Path("id") alertaId: String, @Body body: LoteUbicacionesDto): Response<UbicacionesAgregadasDto>
}

/** Qué pasó al mandar la alerta al servidor. */
sealed interface ResultadoServidor {
    /** El servidor la guardó (o ya la tenía) y le dio [folio]. */
    data class Registrada(val folio: String, val estado: String) : ResultadoServidor

    /** No se guardó. Con [reintentar] conviene volver a mandarla (sin red, servidor caído o sesión vencida). */
    data class Fallo(val codigoHttp: Int?, val reintentar: Boolean) : ResultadoServidor
}

/** Por aquí [com.escom.sendero_seguro.emergencias.ModuloEmergencias] manda la alerta al servidor. */
fun interface CanalServidorAlertas {
    suspend fun enviar(alerta: AlertaEmergencia): ResultadoServidor
}

/** Qué pasó al mandar un grupo de ubicaciones. */
enum class ResultadoUbicaciones {
    /** El servidor las guardó (o ya las tenía): se quitan de pendientes. */
    ENTREGADAS,

    /** Sin red, servidor caído, sesión vencida o la alerta todavía no está registrada: se quedan para después. */
    REINTENTAR,

    /** El servidor no las quiere (datos inválidos): se descartan. */
    DESCARTAR,

    /** La alerta ya terminó: no tiene caso mandar más. */
    ALERTA_TERMINADA,
}

/** Por aquí [com.escom.sendero_seguro.emergencias.ModuloEmergencias] manda las ubicaciones de la alerta activa. */
fun interface CanalUbicaciones {
    suspend fun enviar(alertaId: String, lecturas: List<LecturaUbicacionDto>): ResultadoUbicaciones
}

/** Implementación con Retrofit. En [crear], `tokenActual` da el JWT de la sesión del Módulo 1 (null si no hay sesión). */
class ServidorAlertasRetrofit(private val api: EmergenciasApi) : CanalServidorAlertas, CanalUbicaciones {

    override suspend fun enviar(alertaId: String, lecturas: List<LecturaUbicacionDto>): ResultadoUbicaciones = try {
        val r = api.agregarUbicaciones(alertaId, LoteUbicacionesDto(lecturas))
        when {
            r.isSuccessful -> ResultadoUbicaciones.ENTREGADAS
            r.code() == 409 -> ResultadoUbicaciones.ALERTA_TERMINADA
            // 404: la alerta va en camino (malla o SMS) y todavía no llega; 401: sesión vencida; 5xx: el servidor falló.
            r.code() == 404 || r.code() == 401 || r.code() >= 500 -> ResultadoUbicaciones.REINTENTAR
            else -> ResultadoUbicaciones.DESCARTAR
        }
    } catch (_: IOException) {
        ResultadoUbicaciones.REINTENTAR
    }

    override suspend fun enviar(alerta: AlertaEmergencia): ResultadoServidor = try {
        val r = api.registrarAlerta(aDto(alerta))
        val cuerpo = r.body()
        when {
            r.isSuccessful && cuerpo != null -> ResultadoServidor.Registrada(cuerpo.folio, cuerpo.estado)
            // 401: la sesión venció; 5xx: el servidor falló. Las dos se arreglan reintentando más tarde.
            else -> ResultadoServidor.Fallo(r.code(), reintentar = r.code() == 401 || r.code() >= 500)
        }
    } catch (_: IOException) {
        ResultadoServidor.Fallo(null, reintentar = true)
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        fun crear(baseUrl: String, tokenActual: () -> String?): ServidorAlertasRetrofit {
            val auth = Interceptor { cadena ->
                val peticion = cadena.request().newBuilder().apply {
                    tokenActual()?.let { header("Authorization", "Bearer $it") }
                    header("Accept", "application/json")
                }.build()
                cadena.proceed(peticion)
            }
            val ok = OkHttpClient.Builder()
                .addInterceptor(auth)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(ok)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(EmergenciasApi::class.java)
            return ServidorAlertasRetrofit(api)
        }

        /** Los detonadores de la app con los nombres del servidor (`alerta_detonador`). */
        fun detonador(d: TipoDetonador) = when (d) {
            TipoDetonador.BOTON_SOS -> "BOTON"
            TipoDetonador.ATAJO_FISICO -> "ATAJO_FISICO"
            TipoDetonador.WIDGET -> "WIDGET"
            TipoDetonador.COMANDO_VOZ -> "VOZ"
            TipoDetonador.CAIDA_IMPACTO -> "CAIDA"
            TipoDetonador.VENCIMIENTO_CAMINATA_SEGURA -> "TEMPORIZADOR"
        }

        /** Una lectura del GPS como la espera el servidor. */
        fun aDto(d: DatosTelemetria) = LecturaUbicacionDto(
            latitud = d.latitud,
            longitud = d.longitud,
            precisionM = d.precision.toDouble().takeIf { it >= 0 },
            velocidadMs = d.velocidadMps.toDouble().takeIf { it >= 0 },
            rumboGrados = d.rumboGrados.takeIf { it >= 0 }?.let { Math.round(it) % 360 },
            bateria = d.nivelBateria.takeIf { it in 0..100 },
            fuente = d.fuente.name,
            capturadaEn = Instant.ofEpochMilli(d.timestamp).toString(),
        )

        fun aDto(a: AlertaEmergencia): AlertaNuevaDto {
            val activadaEn = Instant.ofEpochMilli(a.timestampInicio).toString()
            return AlertaNuevaDto(
                id = a.idAlerta,
                // 0 es la genérica en la app: sin tipo, el servidor la registra como genérica.
                tipoIncidenteId = a.tipoIncidenteId.takeIf { it > 0 },
                descripcion = a.descripcionRelato.ifBlank { null },
                canalRecepcion = if (a.canalRecepcion == CanalRecepcion.MALLA) "MALLA" else "INTERNET",
                detonador = detonador(a.detonador),
                nivelBateria = a.nivelBateria.takeIf { it in 0..100 },
                activadaEn = activadaEn,
                enviadaEn = Instant.now().toString(),
                ubicacion = UbicacionNuevaDto(
                    latitud = a.latitudInicial,
                    longitud = a.longitudInicial,
                    precisionM = a.precisionMetros.toDouble().takeIf { it >= 0 },
                    bateria = a.nivelBateria.takeIf { it in 0..100 },
                    capturadaEn = activadaEn,
                ),
            )
        }
    }
}
