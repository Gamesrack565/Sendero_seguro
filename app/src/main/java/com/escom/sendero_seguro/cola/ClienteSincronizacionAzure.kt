package com.escom.sendero_seguro.cola

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resultado de la transmisión de un elemento pendiente hacia el backend de Azure.
 */
sealed class ResultadoTransmision {
    object Exitoso : ResultadoTransmision()
    data class ErrorRed(val mensaje: String, val excepcion: Throwable? = null) : ResultadoTransmision()
    data class ErrorServidor(val codigoHttp: Int, val cuerpo: String) : ResultadoTransmision()
    data class ErrorFatal(val mensaje: String) : ResultadoTransmision()
}

/**
 * Cliente de red HTTP hacia el servidor de Azure.
 * Respeta la idempotencia por UUID (ER-04 y A-05):
 * Si el servidor responde 200/201 (creado) o 409 (ya existía), se considera exitoso para la cola local.
 */
class ClienteSincronizacionAzure(
    private val context: Context,
    private var urlBaseAzure: String = "https://sendero-seguro-api.azurewebsites.net"
) {

    companion object {
        private const val TAG = "AZURE_SYNC_CLIENT"
        private const val TIMEOUT_CONEXION_MS = 10000
        private const val TIMEOUT_LECTURA_MS = 15000
    }

    /**
     * Permite ajustar la URL base del servidor (ej. entornos de prueba o producción).
     */
    fun configurarUrlServidor(nuevaUrl: String) {
        urlBaseAzure = nuevaUrl.trimEnd('/')
    }

    /**
     * Envía un elemento de la cola (Alerta o Reporte) al endpoint correspondiente.
     */
    suspend fun transmitirElemento(elemento: ElementoColaPendiente): ResultadoTransmision =
        withContext(Dispatchers.IO) {
            when (elemento) {
                is ElementoColaPendiente.Alerta -> transmitirAlerta(elemento)
                is ElementoColaPendiente.Reporte -> transmitirReporte(elemento)
            }
        }

    /**
     * Envío de Alertas SOS, Acuses, Telemetría o Paquetes de Malla al backend.
     */
    private fun transmitirAlerta(alerta: ElementoColaPendiente.Alerta): ResultadoTransmision {
        val endpoint = when (alerta.tipoElemento) {
            TipoElementoAlerta.TELEMETRIA.valor -> "$urlBaseAzure/api/telemetria"
            TipoElementoAlerta.PAQUETE_MALLA.valor -> "$urlBaseAzure/api/malla/paquete"
            TipoElementoAlerta.ACUSE_RECIBO.valor -> "$urlBaseAzure/api/alerta/acuse"
            else -> "$urlBaseAzure/api/alerta"
        }

        val jsonString = if (alerta.paqueteBytes != null && alerta.paqueteBytes.isNotEmpty()) {
            String(alerta.paqueteBytes, Charsets.UTF_8)
        } else {
            JSONObject().apply {
                put("alerta_id", alerta.id)
                put("tipo_elemento", alerta.tipoElemento)
                put("ultimo_canal", alerta.ultimoCanal)
                put("creado_en", alerta.creadoEn)
            }.toString()
        }

        return realizarPeticionPost(endpoint, jsonString, alerta.id)
    }

    /**
     * Envío de Reporte Comunitario de Incidentes al backend (ER-04).
     */
    private fun transmitirReporte(reporte: ElementoColaPendiente.Reporte): ResultadoTransmision {
        val endpoint = "$urlBaseAzure/api/reportes"

        // Envolvemos o verificamos que el payload incluya el reporte_id para idempotencia
        val jsonPayload = try {
            val json = JSONObject(reporte.datosJson)
            if (!json.has("id")) {
                json.put("id", reporte.id)
            }
            if (reporte.rutasEvidencia.isNotEmpty() && !json.has("evidencias_locales")) {
                json.put("evidencias_locales", reporte.rutasEvidencia.joinToString(","))
            }
            json.toString()
        } catch (e: Exception) {
            reporte.datosJson
        }

        return realizarPeticionPost(endpoint, jsonPayload, reporte.id)
    }

    /**
     * Ejecución de petición HTTP POST con manejo de códigos de respuesta e idempotencia.
     */
    private fun realizarPeticionPost(
        urlString: String,
        cuerpoJson: String,
        uuidElemento: String
    ): ResultadoTransmision {
        var conexion: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conexion = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_CONEXION_MS
                readTimeout = TIMEOUT_LECTURA_MS
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Idempotency-Key", uuidElemento)
                setRequestProperty("User-Agent", "SenderoSeguro-Android/P11")
            }

            OutputStreamWriter(conexion.outputStream, Charsets.UTF_8).use { escritor ->
                escritor.write(cuerpoJson)
                escritor.flush()
            }

            val codigoHttp = conexion.responseCode
            val cuerpoRespuesta = leerCuerpoRespuesta(conexion)

            when (codigoHttp) {
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_CREATED,
                HttpURLConnection.HTTP_ACCEPTED -> {
                    Log.i(TAG, "✅ [200/201] Transmisión exitosa a Azure [ID: $uuidElemento]")
                    ResultadoTransmision.Exitoso
                }
                HttpURLConnection.HTTP_CONFLICT -> {
                    // Idempotencia: el backend ya tenía registrado este UUID exacto (Diagrama A-05: 'si ya existe, ignorar')
                    Log.i(TAG, "ℹ️ [409 Conflict] Elemento ya existía en backend por UUID [ID: $uuidElemento]. Idempotencia satisfecha.")
                    ResultadoTransmision.Exitoso
                }
                in 500..599 -> {
                    Log.w(TAG, "⚠️ Error en servidor Azure ($codigoHttp): $cuerpoRespuesta")
                    ResultadoTransmision.ErrorRed("Fallo en servidor backend: HTTP $codigoHttp")
                }
                else -> {
                    Log.e(TAG, "❌ Rechazo por el servidor ($codigoHttp): $cuerpoRespuesta")
                    ResultadoTransmision.ErrorServidor(codigoHttp, cuerpoRespuesta)
                }
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "🔌 Error de conexión de red al contactar Azure: ${e.message}")
            ResultadoTransmision.ErrorRed("Sin conexión con el host de Azure", e)
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error inesperado durante transmisión: ${e.message}", e)
            ResultadoTransmision.ErrorFatal(e.message ?: "Error desconocido")
        } finally {
            conexion?.disconnect()
        }
    }

    private fun leerCuerpoRespuesta(conexion: HttpURLConnection): String {
        val stream = if (conexion.responseCode in 200..299) {
            conexion.inputStream
        } else {
            conexion.errorStream ?: conexion.inputStream
        } ?: return ""

        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}
