package com.escom.sendero_seguro.reportes

import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReporteEntradaDtoTest {
    @Test
    fun serializaLaMetadataDeLasEvidenciasAdjuntas() {
        val reporte = ReporteEntradaDto(
            id = "39d870c1-8c11-4b9b-a2f4-223d359347f5",
            tipoIncidenteId = 7,
            versionCatalogoId = 3,
            descripcion = "Incidente con evidencia adjunta",
            latitud = 19.432608,
            longitud = -99.133209,
            esAnonimo = false,
            creadoEn = "2026-02-28T15:00:00Z",
            evidencias = listOf(
                EvidenciaReporteDto(
                    clave = "evidencia/39d870c1-8c11-4b9b-a2f4-223d359347f5.jpg",
                    mime = "image/jpeg",
                    tamanoBytes = 2048,
                    hashSha256 = "a".repeat(64),
                )
            ),
        )

        val evidencia = ClienteHttp.json.parseToJsonElement(
            ClienteHttp.json.encodeToString(ReporteEntradaDto.serializer(), reporte)
        ).jsonObject["evidencias"]!!.jsonArray.single().jsonObject

        assertEquals("image/jpeg", evidencia["mime"]?.jsonPrimitive?.content)
        assertEquals(2048L, evidencia["tamanoBytes"]?.jsonPrimitive?.content?.toLong())
        assertEquals("evidencia/39d870c1-8c11-4b9b-a2f4-223d359347f5.jpg", evidencia["clave"]?.jsonPrimitive?.content)
    }

    @Test
    fun serializaLosCamposDelContratoSinCamposDeSugerenciaIa() {
        val reporte = ReporteEntradaDto(
            id = "39d870c1-8c11-4b9b-a2f4-223d359347f5",
            tipoIncidenteId = 7,
            versionCatalogoId = 3,
            descripcion = "Incidente de prueba comunitaria",
            latitud = 19.432608,
            longitud = -99.133209,
            precisionM = 12.5,
            esAnonimo = true,
            creadoEn = "2026-02-28T15:00:00Z",
        )

        val json = ClienteHttp.json.parseToJsonElement(
            ClienteHttp.json.encodeToString(ReporteEntradaDto.serializer(), reporte)
        ).jsonObject

        assertEquals(7, json["tipoIncidenteId"]?.jsonPrimitive?.content?.toInt())
        assertEquals(3, json["versionCatalogoId"]?.jsonPrimitive?.content?.toInt())
        assertEquals(true, json["esAnonimo"]?.jsonPrimitive?.content?.toBoolean())
        assertFalse(json.containsKey("tipoSugeridoId"))
        assertFalse(json.containsKey("confianzaSugerencia"))
    }

    @Test
    fun serializaElSobreOfflineConElContratoDeSyncLote() {
        val reporte = ReporteEntradaDto(
            id = "39d870c1-8c11-4b9b-a2f4-223d359347f5",
            tipoIncidenteId = 7,
            versionCatalogoId = 3,
            descripcion = "Incidente de prueba comunitaria",
            latitud = 19.432608,
            longitud = -99.133209,
            esAnonimo = false,
            creadoEn = "2026-02-28T15:00:00Z",
        )
        val datos = ClienteHttp.json.encodeToJsonElement(ReporteEntradaDto.serializer(), reporte).jsonObject
        val lote = LoteReporteDto(
            dispositivoId = "00000000-0000-4000-8000-0000000000d1",
            elementos = listOf(
                ElementoLoteReporteDto(
                    id = reporte.id,
                    creadoEn = reporte.creadoEn,
                    datos = datos,
                )
            ),
        )

        val json = ClienteHttp.json.parseToJsonElement(
            ClienteHttp.json.encodeToString(LoteReporteDto.serializer(), lote)
        ).jsonObject
        val elemento = json["elementos"]!!.jsonArray.single().jsonObject

        assertEquals("00000000-0000-4000-8000-0000000000d1", json["dispositivoId"]?.jsonPrimitive?.content)
        assertEquals("REPORTE", elemento["tipo"]?.jsonPrimitive?.content)
        assertEquals(reporte.id, elemento["id"]?.jsonPrimitive?.content)
        assertEquals(reporte.id, elemento["datos"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }
}
