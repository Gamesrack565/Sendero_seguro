package com.escom.sendero_seguro.emergencias

import com.escom.sendero_seguro.emergencias.remoto.ResultadoServidor
import com.escom.sendero_seguro.emergencias.remoto.ServidorAlertasRetrofit
import com.escom.sendero_seguro.identidad.AlmacenEnMemoria
import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import com.escom.sendero_seguro.identidad.datos.remoto.ServicioRemotoRetrofit
import com.escom.sendero_seguro.identidad.dominio.InfoDispositivo
import com.escom.sendero_seguro.identidad.dominio.Resultado
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * P5.1 (RF-14, A-01): el paquete BLE de 24 bytes y su entrega al servidor desde un teléfono con internet.
 *
 * Las de conexión necesitan el servidor local prendido (`.\gradlew.bat :server:run`); si no, se saltan.
 */
class RelevoMallaTest {

    private val baseUrl = (System.getProperty("sendero.servidor") ?: System.getenv("SENDERO_SERVIDOR")
        ?: "http://localhost:8080/api/v1/").let { if (it.endsWith("/")) it else "$it/" }

    /** Ana, de las cuentas de prueba del servidor local. */
    private val ana = "00000000-0000-4000-8000-000000000001"

    private fun paqueteDeAna(alertaId: String = UUID.randomUUID().toString()) = PaqueteMalla(
        packetId = "SOS-" + alertaId.take(8),
        alertaId = alertaId,
        emisorId = ana,
        latitud = 19.504507,
        longitud = -99.146911,
        nivelBateria = 40,
        ttl = 5,
        timestamp = System.currentTimeMillis() - 20_000,
    )

    @Test
    fun elPaqueteCabeEnElAnuncioYSeLeeIgual() {
        val original = paqueteDeAna()
        val bytes = FormatoPaqueteMalla.codificar(original.copy(ttl = 3))
        assertEquals("Un anuncio BLE legacy deja 24 bytes de datos", 24, bytes.size)

        val leido = FormatoPaqueteMalla.decodificar(bytes)!!
        assertEquals(original.alertaId.take(8), leido.alertaId)
        assertEquals(original.packetId, leido.packetId)
        assertEquals("00000001", leido.emisorId)
        assertEquals(3, leido.ttl)
        assertEquals(2, leido.saltosRecorridos)
        assertEquals(40, leido.nivelBateria)
        assertEquals(original.timestamp / 1000 * 1000, leido.timestamp)
        assertEquals(19.504507, leido.latitud, 0.00001)
        assertEquals(false, leido.esAcuseOperador)

        val acuse = FormatoPaqueteMalla.decodificar(FormatoPaqueteMalla.codificar(original.copy(esAcuseOperador = true)))!!
        assertTrue(acuse.esAcuseOperador)
        assertEquals("ACK-" + original.alertaId.take(8), acuse.packetId)
        assertEquals(5, acuse.ttl)
        assertNull("Un acuse no se entrega como alerta", FormatoPaqueteMalla.relevoJson(acuse))
    }

    @Test
    fun losPaquetesDelFormatoAnteriorSeSiguenLeyendo() {
        val viejo = byteArrayOf(0x53, 0x53, 0, 0, 0x04, 0xD2.toByte()) + ByteArray(8) + byteArrayOf(55, 4, 1)
        val p = FormatoPaqueteMalla.decodificar(viejo)!!
        assertEquals("SOS-1234", p.packetId)
        assertEquals(4, p.ttl)
        assertNull("Sin persona no se puede entregar", FormatoPaqueteMalla.relevoJson(p))
        assertNull(FormatoPaqueteMalla.decodificar(byteArrayOf(1, 2, 3)))
    }

    private fun servidorPrendido() {
        val arriba = runCatching {
            (URL(baseUrl + "salud").openConnection() as HttpURLConnection).run {
                connectTimeout = 2000; readTimeout = 2000
                responseCode == 200
            }
        }.getOrDefault(false)
        assumeTrue("El servidor no responde en $baseUrl; prueba omitida", arriba)
    }

    private suspend fun token(telefono: String): String {
        val remoto = ServicioRemotoRetrofit(ClienteHttp.crear(baseUrl, AlmacenEnMemoria(), false), ClienteHttp.json)
        val r = remoto.iniciarSesion(telefono, "sendero123", InfoDispositivo(UUID.randomUUID().toString(), "Pixel de prueba", 34, true))
        assertTrue("No se pudo iniciar sesión: $r", r is Resultado.Exito)
        return (r as Resultado.Exito).valor.token
    }

    /** Lo mismo que hace GestorMalla.enviarAServidorLinux. */
    private fun relevar(cuerpo: String, token: String): Pair<Int, String> =
        (URL(baseUrl + "malla/relevos").openConnection() as HttpURLConnection).run {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $token")
            doOutput = true
            outputStream.use { it.write(cuerpo.toByteArray()) }
            responseCode to (if (responseCode < 400) inputStream else errorStream).bufferedReader().readText()
        }

    @Test
    fun beto_entregaLaAlertaDeAnaQueLeLlegoPorLaMalla_yLaDeAnaPorInternetEsLaMisma() = runBlocking<Unit> {
        servidorPrendido()
        val alertaId = UUID.randomUUID().toString()
        val enElAire = FormatoPaqueteMalla.codificar(paqueteDeAna(alertaId).copy(ttl = 4))
        // El teléfono de Beto la recibe por Bluetooth y tiene internet.
        val recibido = FormatoPaqueteMalla.decodificar(enElAire)!!
        val cuerpo = FormatoPaqueteMalla.relevoJson(recibido)!!

        val (codigo, respuesta) = relevar(cuerpo, token("5510000002"))
        assertEquals(201, codigo)
        val folio = Json.parseToJsonElement(respuesta).jsonObject.getValue("folio").jsonPrimitive.content

        // Ana recupera internet y su app manda la misma alerta: el servidor la reconoce.
        val alertaDeAna = AlertaEmergencia(
            idAlerta = alertaId, detonador = TipoDetonador.BOTON_SOS, estadoActual = EstadoAlertaE01.ENVIADA,
            latitudInicial = 19.504507, longitudInicial = -99.146911, precisionMetros = 8f, nivelBateria = 40,
            timestampInicio = recibido.timestamp + 300,
        )
        val tokenAna = token("5510000001")
        val r = ServidorAlertasRetrofit.crear(baseUrl) { tokenAna }.enviar(alertaDeAna)
        assertEquals(folio, (r as ResultadoServidor.Registrada).folio)
    }
}
