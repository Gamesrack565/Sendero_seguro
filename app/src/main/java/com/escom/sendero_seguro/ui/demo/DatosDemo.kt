package com.escom.sendero_seguro.ui.demo

import com.escom.sendero_seguro.ui.mapa.Punto
import com.escom.sendero_seguro.ui.modelo.CanalEnvio

/**
 * Datos de ejemplo mientras los módulos de backend no estén unidos a master.
 * Se reemplazan por los datos reales en la fase de integración, sin tocar las pantallas.
 */
object DatosDemo {
    const val NOMBRE = "Mariana"
    const val CONTACTOS = 3
    const val CIRCULO = "6IV1"
    const val INTEGRANTES_CIRCULO = 24
    val CANAL = CanalEnvio.INTERNET
    val ESCOM = Punto(19.5045, -99.1469)
}
