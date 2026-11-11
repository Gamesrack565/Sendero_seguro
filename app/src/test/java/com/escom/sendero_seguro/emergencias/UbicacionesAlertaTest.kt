package com.escom.sendero_seguro.emergencias

import com.escom.sendero_seguro.emergencias.remoto.CanalUbicaciones
import com.escom.sendero_seguro.emergencias.remoto.EnvioUbicaciones
import com.escom.sendero_seguro.emergencias.remoto.LecturaUbicacionDto
import com.escom.sendero_seguro.emergencias.remoto.ResultadoServidor
import com.escom.sendero_seguro.emergencias.remoto.ResultadoUbicaciones
import com.escom.sendero_seguro.emergencias.remoto.ServidorAlertasRetrofit
import com.escom.sendero_seguro.identidad.AlmacenEnMemoria
import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import com.escom.sendero_seguro.identidad.datos.remoto.ServicioRemotoRetrofit
import com.escom.sendero_seguro.identidad.dominio.InfoDispositivo
import com.escom.sendero_seguro.identidad.dominio.Resultado
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/**
 * P6: las ubicaciones de la alerta activa llegan al servidor, también las que se juntaron sin señal.
 *
 * La prueba de conexión necesita el servidor local prendido (`.\gradlew.bat :server:run`); si no, se salta.
 */
class UbicacionesAlertaTest {

    private val baseUrl = (System.getProperty("sendero.servidor") ?: System.getenv("SENDERO_SERVIDOR")
        ?: "http://localhost:8080/api/v1/").let { if (it.endsWith("/")) it else "$it/" }

    private val inicio = Instant.now().minusSeconds(60).toEpochMilli()

    private fun lectura(segundo: Long, latitud: Double = 19.5051) = DatosTelemetria(
        latitud = latitud, longitud = -99.1470, precision = 6f, velocidadMps = 1.2f, rumboGrados = 359.7f,
        aceleracionG = 1f, nivelBateria = 63, timestamp = inicio + segundo * 1000,
    )

    /** Un servidor de mentira que contesta lo que le digan y anota lo que recibe. */
    private class CanalFalso(var respuesta: ResultadoUbicaciones = ResultadoUbicaciones.ENTREGADAS) : CanalUbicaciones {
        val recibidos = mutableListOf<List<LecturaUbicacionDto>>()
        override suspend fun enviar(alertaId: String, lecturas: List<LecturaUbicacionDto>): ResultadoUbicaciones {
            recibidos += lecturas
            return respuesta
        }
    }

    @Test
    fun laLecturaSeMandaConLosNombresDelServidor() {
        val dto = ServidorAlertasRetrofit.aDto(lectura(0))
        assertEquals("GNSS", dto.fuente)
        assertEquals("359.7 grados se redondea a 0", 0, dto.rumboGrados)
        assertEquals(63, dto.bateria)
        assertEquals(1.2, dto.velocidadMs!!, 0.001)
    }

    @Test
    fun sinSenalSeJuntanYSalenEnGruposCuandoVuelve() = runBlocking<Unit> {
        val canal = CanalFalso(ResultadoUbicaciones.REINTENTAR)
        val envio = EnvioUbicaciones(canal, maximo = 250, porEnvio = 100)
        repeat(260) { envio.agregar(lectura(it.toLong())) }
        assertEquals("Se guardan como máximo 250", 250, envio.pendientes())

        assertEquals(0, envio.enviarPendientes("a"))
        assertEquals("Sin red no se pierde nada", 250, envio.pendientes())

        canal.respuesta = ResultadoUbicaciones.ENTREGADAS
        canal.recibidos.clear()
        assertEquals(250, envio.enviarPendientes("a"))
        assertEquals(listOf(100, 100, 50), canal.recibidos.map { it.size })
        assertEquals("Las más viejas se descartaron primero", Instant.ofEpochMilli(lectura(10).timestamp).toString(), canal.recibidos[0][0].capturadaEn)
        assertEquals(0, envio.pendientes())
    }

    @Test
    fun siLaAlertaYaTerminoNoSeMandaMas() = runBlocking<Unit> {
        val canal = CanalFalso(ResultadoUbicaciones.ALERTA_TERMINADA)
        val envio = EnvioUbicaciones(canal)
        envio.agregar(lectura(0))
        envio.enviarPendientes("a")
        envio.agregar(lectura(1))
        assertEquals(0, envio.pendientes())
        envio.reiniciar()
        envio.agregar(lectura(2))
        assertEquals("Una alerta nueva vuelve a empezar", 1, envio.pendientes())
    }

    @Test
    fun lasUbicacionesLleganAlServidorSinRepetirse() = runBlocking<Unit> {
        val arriba = runCatching {
            (URL(baseUrl + "salud").openConnection() as HttpURLConnection).run {
                connectTimeout = 2000; readTimeout = 2000
                responseCode == 200
            }
        }.getOrDefault(false)
        assumeTrue("El servidor no responde en $baseUrl; prueba omitida", arriba)

        // Ana (5510000001 / sendero123) inicia sesión con el Módulo 1 y activa una alerta.
        val almacen = AlmacenEnMemoria()
        val remoto = ServicioRemotoRetrofit(ClienteHttp.crear(baseUrl, almacen, false), ClienteHttp.json)
        val r = remoto.iniciarSesion("5510000001", "sendero123", InfoDispositivo(UUID.randomUUID().toString(), "Pixel de prueba", 34, true))
        assertTrue("No se pudo iniciar sesión: $r", r is Resultado.Exito)
        val token = (r as Resultado.Exito).valor.token
        val servidor = ServidorAlertasRetrofit.crear(baseUrl) { token }
        val alerta = AlertaEmergencia(
            detonador = TipoDetonador.BOTON_SOS, estadoActual = EstadoAlertaE01.ENVIADA,
            latitudInicial = 19.504507, longitudInicial = -99.146911, precisionMetros = 8f, nivelBateria = 64,
            timestampInicio = Instant.now().minusSeconds(90).toEpochMilli(),
        )
        assertTrue(servidor.enviar(alerta) is ResultadoServidor.Registrada)

        val envio = EnvioUbicaciones(servidor)
        envio.agregar(lectura(0))
        envio.agregar(lectura(10, latitud = 19.5055))
        assertEquals(2, envio.enviarPendientes(alerta.idAlerta))
        // Reenviar lo mismo no duplica: el servidor contesta que ya las tenía.
        assertEquals(ResultadoUbicaciones.ENTREGADAS, servidor.enviar(alerta.idAlerta, listOf(ServidorAlertasRetrofit.aDto(lectura(0)))))
        assertEquals("Una alerta que no existe: se reintenta (puede venir en camino)",
            ResultadoUbicaciones.REINTENTAR, servidor.enviar(UUID.randomUUID().toString(), listOf(ServidorAlertasRetrofit.aDto(lectura(0)))))
    }
}
