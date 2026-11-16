package com.escom.sendero_seguro.mapas

import com.escom.sendero_seguro.emergencias.GestorCaminataSegura
import com.escom.sendero_seguro.emergencias.PuntoRuta
import com.escom.sendero_seguro.mapas.remoto.AristaMapaDto
import com.escom.sendero_seguro.mapas.remoto.GrafoMapaDto
import com.escom.sendero_seguro.mapas.remoto.NodoMapaDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdaptadorGrafoP7Test {
    @Test
    fun convierteGrafoP12YRespetaDireccionEnP7() {
        val dto = GrafoMapaDto(
            capaVersion = 12,
            nodos = listOf(
                NodoMapaDto(1, 19.5045, -99.1469),
                NodoMapaDto(2, 19.5050, -99.1460),
            ),
            aristas = listOf(
                AristaMapaDto(
                    id = 10,
                    origen = 1,
                    destino = 2,
                    longitudM = 120.0,
                    bidireccional = false,
                    riesgo = 0.4,
                )
            ),
            versionGrafo = 7,
        )
        val grafoP7 = dto.aGrafoOfflineP7(versionGrafoLocal = 6, versionCapaLocal = 11)
        val gestor = GestorCaminataSegura(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onTiempoCheckInVencido = {},
            onDesviacionRutaExcedida = {},
        )

        assertEquals(7, grafoP7.versionGrafo)
        assertEquals(12, grafoP7.versionCapaRiesgo)
        assertEquals(false, grafoP7.aristas.single().bidireccional)
        assertEquals(
            listOf(1L, 2L),
            gestor.calcularRutasEnDispositivoOffline(
                origen = PuntoRuta(19.5045, -99.1469),
                destino = PuntoRuta(19.5050, -99.1460),
                aristasGrafoCache = grafoP7.aristas,
                versionCapaRiesgo = grafoP7.versionCapaRiesgo,
            ).rutaMasCorta.nodos.map { it.idNodoOsm },
        )
        assertThrows(IllegalStateException::class.java) {
            gestor.calcularRutasEnDispositivoOffline(
                origen = PuntoRuta(19.5050, -99.1460),
                destino = PuntoRuta(19.5045, -99.1469),
                aristasGrafoCache = grafoP7.aristas,
                versionCapaRiesgo = grafoP7.versionCapaRiesgo,
            )
        }
    }

    @Test
    fun rechazaAristasQueReferencianNodosAusentes() {
        val dto = GrafoMapaDto(
            nodos = listOf(NodoMapaDto(1, 19.5045, -99.1469)),
            aristas = listOf(
                AristaMapaDto(
                    id = 10,
                    origen = 1,
                    destino = 2,
                    longitudM = 120.0,
                    bidireccional = true,
                    riesgo = 0.4,
                )
            ),
        )

        assertThrows(java.io.IOException::class.java) {
            dto.aGrafoOfflineP7(versionGrafoLocal = 7, versionCapaLocal = 12)
        }
    }
}
