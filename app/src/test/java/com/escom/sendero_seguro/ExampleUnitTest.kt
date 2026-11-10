package com.escom.sendero_seguro

import com.escom.sendero_seguro.emergencias.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    // PRUEBA P5.1: Retransmisión de malla, decremento de TTL y bloqueo de duplicados
    @Test
    fun probarRetransmisionMallaP51() {
        val gestorMalla = GestorMalla()
        val paqueteEntrante = PaqueteMalla(
            packetId = "SOS-TEST-001",
            emisorId = "ALUMNO_ESCOM",
            latitud = 19.5045,
            longitud = -99.1469,
            nivelBateria = 85,
            ttl = 3
        )

        // 1. Primera recepción: debe retransmitir y bajar el TTL de 3 a 2
        val retransmitido = gestorMalla.interceptarYRetransmitir(paqueteEntrante)
        assertNotNull(retransmitido)
        assertEquals(2, retransmitido?.ttl)

        // 2. Segunda recepción del mismo paquete: debe ignorarlo por ID duplicado
        val duplicado = gestorMalla.interceptarYRetransmitir(paqueteEntrante)
        assertNull(duplicado)
    }

    // PRUEBA P7: Desviación geográfica y vencimiento del temporizador de Caminata Segura
    @Test
    fun probarCaminataSeguraDesviacionYCheckInP7() = runBlocking {
        var alertaDisparadaPorTiempo = false
        var desviacionDetectadaMetros = 0.0

        val gestorCaminata = GestorCaminataSegura(
            scope = CoroutineScope(Dispatchers.Default),
            onTiempoCheckInVencido = { alertaDisparadaPorTiempo = true },
            onDesviacionRutaExcedida = { distancia -> desviacionDetectadaMetros = distancia }
        )

        // Ruta objetivo en ESCOM con check-in rápido de 1 segundo para la prueba
        val ruta = listOf(PuntoRuta(19.5045, -99.1469))
        gestorCaminata.iniciarCaminataSegura(
            rutaObjetivo = ruta,
            tiempoEstimadoLlegadaMs = 60_000L,
            intervaloCheckInSeg = 1L,
            toleranciaMetros = 50.0
        )

        // 1. Evaluar coordenada lejana (~500 metros de desviación)
        gestorCaminata.evaluarUbicacionEnRuta(19.5090, -99.1469)
        assertTrue(gestorCaminata.estadoCaminata.value.fueraDeRuta)
        assertTrue(desviacionDetectadaMetros > 50.0)

        // 2. Esperar 1.5 segundos sin hacer check-in para verificar que dispare la alerta P4
        delay(1500L)
        assertTrue(alertaDisparadaPorTiempo)
    }
}