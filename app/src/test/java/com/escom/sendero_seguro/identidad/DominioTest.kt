package com.escom.sendero_seguro.identidad

import com.escom.sendero_seguro.identidad.dominio.ConfigActivacion
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
import com.escom.sendero_seguro.identidad.dominio.EstadoBateria
import com.escom.sendero_seguro.identidad.dominio.HashPin
import com.escom.sendero_seguro.identidad.dominio.PoliticaEnergia
import com.escom.sendero_seguro.identidad.dominio.TipoComunidad
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DominioTest {

    private fun datos() = DatosRegistro(
        nombre = "Mariana López Hernández",
        telefonoMovil = "55 1234 5678",
        identificadorInstitucional = "2023630417",
        tipoComunidad = TipoComunidad.ALUMNO,
        institucionId = 1,
        escuelaId = 1,
        carreraAdscripcion = "Ing. en Inteligencia Artificial",
        contrasena = "segura2026"
    )

    @Test
    fun registroValidoNoTieneErrores() {
        assertTrue(Validaciones.validarRegistro(datos()).isEmpty())
    }

    @Test
    fun registroDetectaCadaCampo() {
        val e = Validaciones.validarRegistro(
            datos().copy(
                nombre = "M4riana", telefonoMovil = "55 1234 567", identificadorInstitucional = "abc",
                escuelaId = null, carreraAdscripcion = "IA", contrasena = "solotexto"
            )
        )
        assertEquals(
            setOf(Validaciones.NOMBRE, Validaciones.TELEFONO, Validaciones.IDENTIFICADOR,
                Validaciones.ESCUELA, Validaciones.CARRERA, Validaciones.CONTRASENA),
            e.keys
        )
    }

    @Test
    fun telefonoSeNormaliza() {
        assertEquals("5512345678", Validaciones.normalizarTelefono("+52 (55) 1234-5678"))
        assertTrue(Validaciones.esTelefonoValido("55 1234 5678"))
        assertFalse(Validaciones.esTelefonoValido("551234567"))
    }

    @Test
    fun pinDebeSerDeCuatroDigitosYCoincidir() {
        assertTrue(Validaciones.validarPin("1234", "1234").isEmpty())
        assertEquals(setOf(Validaciones.PIN), Validaciones.validarPin("12a4", "12a4").keys)
        assertEquals(setOf(Validaciones.PIN_CONFIRMACION), Validaciones.validarPin("1234", "4321").keys)
    }

    @Test
    fun inicioSesionAceptaTelefonoOMatricula() {
        assertTrue(Validaciones.validarInicioSesion("2023630417", "x").isEmpty())
        assertTrue(Validaciones.validarInicioSesion("E48211X", "x").isEmpty())
        assertEquals(setOf(Validaciones.USUARIO, Validaciones.CONTRASENA), Validaciones.validarInicioSesion("", "").keys)
    }

    @Test
    fun contactoNoRepiteTelefono() {
        val existentes = listOf(Contacto("a", "Laura", "5588124410", 1))
        assertEquals(setOf(Validaciones.TELEFONO), Validaciones.validarContacto("Diego", "55 8812 4410", existentes).keys)
        assertTrue(Validaciones.validarContacto("Laura H", "5588124410", existentes, idEditado = "a").isEmpty())
        assertEquals(setOf(Validaciones.NOMBRE), Validaciones.validarContacto("D", "5530319987", existentes).keys)
    }

    @Test
    fun codigoDeInvitacionSeNormaliza() {
        assertEquals("K7Q-42M", Validaciones.normalizarCodigoInvitacion("k7q42m"))
        assertEquals("K7Q-42M", Validaciones.normalizarCodigoInvitacion(" K7Q-42M "))
        assertNull(Validaciones.normalizarCodigoInvitacion("K7Q-42"))
    }

    @Test
    fun cuentaRegresivaSolo3_5_10() {
        assertNull(Validaciones.validarCuentaRegresiva(5))
        assertTrue(Validaciones.validarCuentaRegresiva(7) != null)
    }

    @Test
    fun hashPinVerificaYUsaSal() {
        val h1 = HashPin.generar("1234")
        val h2 = HashPin.generar("1234")
        assertNotEquals(h1, h2)
        assertTrue(HashPin.verificar("1234", h1))
        assertFalse(HashPin.verificar("1235", h1))
        assertFalse(HashPin.verificar("1234", "basura"))
    }

    @Test
    fun escaneoSegunBateria() {
        val c = ConfigActivacion()
        assertEquals(PoliticaEnergia.INTERVALO_NORMAL_MS, PoliticaEnergia.calcular(c, EstadoBateria(64, false)).intervaloMs)
        assertEquals(PoliticaEnergia.INTERVALO_NORMAL_MS, PoliticaEnergia.calcular(c, EstadoBateria(20, false)).intervaloMs)
        assertEquals(PoliticaEnergia.INTERVALO_AHORRO_MS, PoliticaEnergia.calcular(c, EstadoBateria(19, false)).intervaloMs)
        assertEquals(PoliticaEnergia.INTERVALO_NORMAL_MS, PoliticaEnergia.calcular(c, EstadoBateria(10, true)).intervaloMs)
        assertEquals(
            PoliticaEnergia.INTERVALO_NORMAL_MS,
            PoliticaEnergia.calcular(c.copy(ahorroBateria = false), EstadoBateria(5, false)).intervaloMs
        )
    }
}
