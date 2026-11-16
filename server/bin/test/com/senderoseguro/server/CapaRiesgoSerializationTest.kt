package com.senderoseguro.server

import com.senderoseguro.server.comunidad.CapaRiesgo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CapaRiesgoSerializationTest {
    @Test
    fun serializaLeyendaQueLaAppNecesita() {
        val leyenda = "Información orientativa: zonas con reportes comunitarios publicados, no una predicción."
        val capa = CapaRiesgo(
            version = 3,
            generadaEn = "2026-11-16T00:00:00Z",
            periodoDias = 30,
            zonas = emptyList(),
            leyenda = leyenda,
        )

        val json = Json.parseToJsonElement(Json.encodeToString(capa))

        assertEquals(leyenda, json.jsonObject["leyenda"]?.jsonPrimitive?.content)
    }
}
