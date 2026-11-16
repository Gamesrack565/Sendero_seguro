package com.escom.sendero_seguro.mapas.remoto

import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

@Serializable
data class VersionesMapasDto(
    val capaRiesgo: Int? = null,
    val catalogo: Int? = null,
    val paquetes: List<PaqueteVersionDto> = emptyList(),
    val grafo: Int? = null,
)

@Serializable
data class PaqueteVersionDto(val region: String, val version: String)

@Serializable
data class CapaRiesgoDto(
    val version: Int,
    val generadaEn: String,
    val periodoDias: Int,
    val zonas: List<ZonaMapaDto>,
    val leyenda: String,
)

@Serializable
data class ZonaMapaDto(
    val id: Long,
    val nivel: String,
    val tipoIncidenteId: Int? = null,
    val periodoDias: Int,
    val densidadPonderada: Double,
    val numReportes: Int,
    val centroLatitud: Double,
    val centroLongitud: Double,
    val geometria: JsonObject,
)

@Serializable
data class GrafoMapaDto(
    val capaVersion: Int? = null,
    val nodos: List<NodoMapaDto>,
    val aristas: List<AristaMapaDto>,
    val versionGrafo: Int? = null,
)

@Serializable
data class NodoMapaDto(val id: Long, val latitud: Double, val longitud: Double)

@Serializable
data class AristaMapaDto(
    val id: Long,
    val origen: Long,
    val destino: Long,
    val longitudM: Double,
    val bidireccional: Boolean,
    val riesgo: Double,
)

@Serializable
data class PaqueteMapaDto(
    val id: Int,
    val region: String,
    val version: String,
    val minLatitud: Double,
    val minLongitud: Double,
    val maxLatitud: Double,
    val maxLongitud: Double,
    val tamanoBytes: Long,
    val hashSha256: String,
    val url: String,
    val publicadoEn: String,
)

interface MapasApi {
    @GET("mapas/version")
    suspend fun versiones(): VersionesMapasDto

    @GET("mapa-calor")
    suspend fun mapaCalor(@Query("periodo") periodo: Int): Response<CapaRiesgoDto>

    @GET("grafo")
    suspend fun grafo(
        @Query("minLatitud") minLatitud: Double,
        @Query("minLongitud") minLongitud: Double,
        @Query("maxLatitud") maxLatitud: Double,
        @Query("maxLongitud") maxLongitud: Double,
    ): Response<GrafoMapaDto>

    @GET("mapas/paquetes")
    suspend fun paquetes(): List<PaqueteMapaDto>

    @Streaming
    @GET("archivos/mapa/{nombre}")
    suspend fun descargarPaquete(@Path("nombre") nombre: String): Response<ResponseBody>
}

object ServidorMapasRetrofit {
    fun crear(baseUrl: String, tokenActual: () -> String?): MapasApi {
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
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ClienteHttp.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MapasApi::class.java)
    }
}
