package com.escom.sendero_seguro.mapas

import java.io.File
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test

class PaquetesMapaOfflineTest {
    @Test
    fun aceptaMetadatosConNombresDeCalles() {
        PaquetesMapaOffline.validarMetadataVectorial(
            """{"vector_layers":[{"id":"transportation","fields":{}},{"id":"transportation_name","fields":{"name":"String"}},{"id":"water","fields":{}}]}""",
        )
    }

    @Test(expected = IOException::class)
    fun rechazaMetadatosSinCapaDeNombresDeCalles() {
        PaquetesMapaOffline.validarMetadataVectorial(
            """{"vector_layers":[{"id":"transportation","fields":{}},{"id":"water","fields":{}}]}""",
        )
    }

    @Test(expected = IOException::class)
    fun rechazaCapaDeNombresSinCampoDeNombre() {
        PaquetesMapaOffline.validarMetadataVectorial(
            """{"vector_layers":[{"id":"transportation","fields":{}},{"id":"transportation_name","fields":{"ref":"String"}},{"id":"water","fields":{}}]}""",
        )
    }

    @Test
    fun aceptaFirmaDeBaseMbtiles() {
        val archivo = File.createTempFile("paquete-mapa-", ".mbtiles")
        archivo.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))

        PaquetesMapaOffline.validarFirmaMbtiles(archivo)

        assertTrue(archivo.delete())
    }

    @Test(expected = IOException::class)
    fun rechazaPaqueteRasterZip() {
        val archivo = File.createTempFile("paquete-mapa-", ".zip")
        archivo.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))

        try {
            PaquetesMapaOffline.validarFirmaMbtiles(archivo)
        } finally {
            archivo.delete()
        }
    }
}
