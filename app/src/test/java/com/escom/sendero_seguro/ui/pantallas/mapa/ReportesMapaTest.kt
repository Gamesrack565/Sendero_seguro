package com.escom.sendero_seguro.ui.pantallas.mapa

import com.escom.sendero_seguro.mapas.remoto.ZonaMapaDto
import com.escom.sendero_seguro.reportes.ReporteComunitarioPublicoDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReportesMapaTest {
    @Test
    fun filtraPorCategoriaPeriodoYFranjaHoraria() {
        val recientes = listOf(
            reporte("fisico", "FISICO", Instant.now().minusSeconds(2 * 24 * 60 * 60L)),
            reporte("social", "SOCIAL", Instant.now().minusSeconds(2 * 24 * 60 * 60L)),
            reporte("antiguo", "FISICO", Instant.now().minusSeconds(8 * 24 * 60 * 60L)),
        )

        val resultado = filtrarReportes(recientes, "FISICO", 7, "TODO")

        assertEquals(listOf("fisico"), resultado.map { it.id })
    }

    @Test
    fun filtraPorFranjaHorariaLocal() {
        val zonaHoraria = ZoneId.systemDefault()
        val fecha = LocalDate.now(zonaHoraria).minusDays(1)
        val reportes = listOf(
            reporte("madrugada", "FISICO", fecha.atTime(2, 0).atZone(zonaHoraria).toInstant()),
            reporte("tarde", "FISICO", fecha.atTime(15, 0).atZone(zonaHoraria).toInstant()),
        )

        val resultado = filtrarReportes(reportes, null, 7, "MADRUGADA")

        assertEquals(listOf("madrugada"), resultado.map { it.id })
    }

    @Test
    fun detectaSiElPuntoPublicoEstaDentroDeLaZona() {
        val zona = ZonaMapaDto(
            id = 1,
            nivel = "MEDIO",
            tipoIncidenteId = null,
            periodoDias = 30,
            densidadPonderada = 4.5,
            numReportes = 4,
            centroLatitud = 19.5,
            centroLongitud = -99.1,
            geometria = Json.parseToJsonElement(
                """{"type":"Polygon","coordinates":[[[-99.2,19.4],[-99.0,19.4],[-99.0,19.6],[-99.2,19.6],[-99.2,19.4]]]}"""
            ).jsonObject,
        )

        assertTrue(puntoEnZona(19.5, -99.1, zona))
        assertFalse(puntoEnZona(19.7, -99.1, zona))
    }

    private fun reporte(id: String, categoria: String, publicadoEn: Instant) =
        ReporteComunitarioPublicoDto(
            id = id,
            tipoIncidenteId = 1,
            tipo = "Reporte",
            categoria = categoria,
            descripcion = "Descripción de prueba",
            celda = "u4pruyd",
            latitud = 19.5,
            longitud = -99.1,
            autor = "Anónimo",
            publicadoEn = publicadoEn.toString(),
        )
}
