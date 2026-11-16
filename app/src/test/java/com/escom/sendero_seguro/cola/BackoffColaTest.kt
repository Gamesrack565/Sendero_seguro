package com.escom.sendero_seguro.cola

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffColaTest {

    @Test
    fun laEsperaSeDuplicaHastaElLimiteDeDiezMinutos() {
        assertEquals(15_000L, GestorColaSincronizacion.calcularEsperaBackoffMs(0))
        assertEquals(30_000L, GestorColaSincronizacion.calcularEsperaBackoffMs(1))
        assertEquals(60_000L, GestorColaSincronizacion.calcularEsperaBackoffMs(2))
        assertEquals(600_000L, GestorColaSincronizacion.calcularEsperaBackoffMs(6))
        assertEquals(600_000L, GestorColaSincronizacion.calcularEsperaBackoffMs(Int.MAX_VALUE))
    }

    @Test
    fun unContadorNegativoUsaElIntervaloInicial() {
        assertEquals(15_000L, GestorColaSincronizacion.calcularEsperaBackoffMs(-1))
    }
}
