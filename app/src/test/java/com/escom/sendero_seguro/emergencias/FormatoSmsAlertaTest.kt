package com.escom.sendero_seguro.emergencias

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class FormatoSmsAlertaTest {
    @Test
    fun codificaElFormatoSs1DeLaPasarelaConIdTipoUbicacionYChecksum() {
        val alerta = AlertaEmergencia(
            idAlerta = "12345678-1234-1234-1234-123456789abc",
            detonador = TipoDetonador.BOTON_SOS,
            tipoIncidenteId = 1,
            latitudInicial = 19.504507,
            longitudInicial = -99.146911,
            precisionMetros = 8f,
            nivelBateria = 64,
            timestampInicio = 1_798_000_000_000L
        )

        val sms = FormatoSmsAlerta.codificar(alerta)
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(sms.substring(0, 40).toByteArray(Charsets.US_ASCII))
            .take(3)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        assertEquals(FormatoSmsAlerta.LONGITUD, sms.length)
        assertTrue(sms.startsWith("SS1|1234567801+1950451-09914691008"))
        assertEquals(checksum, sms.substring(40))
    }

    @Test
    fun alertaGenericaUsaElTipoGenericoDelCatalogoLocal() {
        val alerta = AlertaEmergencia(
            detonador = TipoDetonador.BOTON_SOS,
            tipoIncidenteId = 0,
            latitudInicial = 19.0,
            longitudInicial = -99.0,
            precisionMetros = 10f,
            nivelBateria = 50
        )

        assertEquals("SS1|${alerta.idAlerta.replace("-", "").take(8)}01", FormatoSmsAlerta.codificar(alerta).take(14))
    }
}
