package com.escom.sendero_seguro.identidad.datos.remoto

import com.escom.sendero_seguro.identidad.puertos.AlmacenSeguro
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object ClienteHttp {

    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Cliente con el token JWT en cada petición. Los tiempos de espera son cortos a propósito:
     * si no hay red, la app debe pasar rápido al siguiente canal o a la cola.
     */
    fun crear(baseUrl: String, almacen: AlmacenSeguro, depurar: Boolean): IdentidadApi {
        val auth = Interceptor { cadena ->
            val token = almacen.leerToken()?.token
            val peticion = cadena.request().newBuilder().apply {
                if (token != null) header("Authorization", "Bearer $token")
                header("Accept", "application/json")
            }.build()
            cadena.proceed(peticion)
        }
        val ok = OkHttpClient.Builder()
            .addInterceptor(auth)
            .apply {
                if (depurar) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC // nunca BODY: viajan contraseñas
                    })
                }
            }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(IdentidadApi::class.java)
    }
}
