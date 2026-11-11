package com.escom.sendero_seguro.emergencias

import com.escom.sendero_seguro.emergencias.remoto.ResultadoServidor
import com.escom.sendero_seguro.emergencias.remoto.ServidorAlertasRetrofit
import com.escom.sendero_seguro.identidad.AlmacenEnMemoria
import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import com.escom.sendero_seguro.identidad.datos.remoto.ServicioRemotoRetrofit
import com.escom.sendero_seguro.identidad.dominio.InfoDispositivo
import com.escom.sendero_seguro.identidad.dominio.Resultado
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/**
 * P4: la alerta del Módulo 2 llega al servidor (Módulo 4) con la sesión del Módulo 1.
 *
 * Las pruebas de conexión necesitan el servidor local prendido (`.\gradlew.bat :server:run`); si no, se saltan.
 * Otro servidor: `-Dsendero.servidor=http://.../api/v1/` o la variable SENDERO_SERVIDOR.
 */
class ServidorAlertasTest {

    private val baseUrl = (System.getProperty("sendero.servidor") ?: System.getenv("SENDERO_SERVIDOR")
        ?: "http://localhost:8080/api/v1/").let { if (it.endsWith("/")) it else "$it/" }

    private fun alerta(detonador: TipoDetonador = TipoDetonador.COMANDO_VOZ) = AlertaEmergencia(
        detonador = detonador,
        estadoActual = EstadoAlertaE01.ENVIADA,
        latitudInicial = 19.504507,
        longitudInicial = -99.146911,
        precisionMetros = 8f,
        nivelBateria = 64,
    )

    private fun servidorPrendido() {
        val arriba = runCatching {
            (URL(baseUrl + "salud").openConnection() as HttpURLConnection).run {
                connectTimeout = 2000; readTimeout = 2000
                responseCode == 200
            }
        }.getOrDefault(false)
        assumeTrue("El servidor no responde en $baseUrl; prueba omitida", arriba)
    }

    /** Token de Ana (cuenta de prueba del servidor local, 5510000001 / sendero123) con el login del Módulo 1. */
    private suspend fun tokenDeAna(): String {
        val almacen = AlmacenEnMemoria()
        val remoto = ServicioRemotoRetrofit(ClienteHttp.crear(baseUrl, almacen, false), ClienteHttp.json)
        val disp = InfoDispositivo(UUID.randomUUID().toString(), "Pixel de prueba", 34, true)
        val r = remoto.iniciarSesion("5510000001", "sendero123", disp)
        assertTrue("No se pudo iniciar sesión: $r", r is Resultado.Exito)
        return (r as Resultado.Exito).valor.token
    }

    @Test
    fun laAlertaSeMandaConLosNombresDelServidor() {
        val a = alerta(TipoDetonador.CAIDA_IMPACTO)
        val dto = ServidorAlertasRetrofit.aDto(a)
        assertEquals(a.idAlerta, dto.id)
        assertEquals("CAIDA", dto.detonador)
        assertNull("La genérica (0) va sin tipo", dto.tipoIncidenteId)
        assertEquals("INTERNET", dto.canalRecepcion)
        assertEquals(Instant.ofEpochMilli(a.timestampInicio).toString(), dto.activadaEn)
        assertEquals(19.504507, dto.ubicacion.latitud, 0.0)
        assertEquals(64, dto.ubicacion.bateria)
        val json = ServidorAlertasRetrofit.json.encodeToString(com.escom.sendero_seguro.emergencias.remoto.AlertaNuevaDto.serializer(), dto)
        assertFalse("Los nulos no viajan", json.contains("null"))
        assertTrue(kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.containsKey("ubicacion"))
        assertEquals(
            listOf("BOTON", "ATAJO_FISICO", "WIDGET", "VOZ", "CAIDA", "TEMPORIZADOR"),
            TipoDetonador.entries.map { ServidorAlertasRetrofit.detonador(it) },
        )
    }

    @Test
    fun laAlertaQuedaRegistradaConFolioYUnReenvioNoLaDuplica() = runBlocking<Unit> {
        servidorPrendido()
        val token = tokenDeAna()
        val servidor = ServidorAlertasRetrofit.crear(baseUrl) { token }
        val a = alerta()
        val primero = servidor.enviar(a)
        assertTrue("Se esperaba Registrada y llegó $primero", primero is ResultadoServidor.Registrada)
        val folio = (primero as ResultadoServidor.Registrada).folio
        assertTrue(folio.startsWith("ALR-"))

        val reenvio = servidor.enviar(a)
        assertEquals(folio, (reenvio as ResultadoServidor.Registrada).folio)
    }

    @Test
    fun sinSesionNoSeRegistraYSePuedeReintentar() = runBlocking<Unit> {
        servidorPrendido()
        val r = ServidorAlertasRetrofit.crear(baseUrl) { null }.enviar(alerta())
        assertEquals(ResultadoServidor.Fallo(401, reintentar = true), r)
    }
}
