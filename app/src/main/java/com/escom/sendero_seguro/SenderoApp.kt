package com.escom.sendero_seguro

import android.app.Application
import com.escom.sendero_seguro.identidad.ModuloIdentidad

class SenderoApp : Application() {
    /** Módulo 1 (P1, P2, P3, P8). Se crea perezosamente para no retrasar el arranque. */
    val identidad: ModuloIdentidad by lazy { ModuloIdentidad(this) }
}
