package com.escom.sendero_seguro.identidad.dominio

/**
 * Regla de A-12 / CU-27: con «Escaneo según batería» activo se busca vecinos de la malla
 * cada 2 min si la batería está en 20 % o más y cada 10 min si baja de 20 %.
 * Cargando o con el ahorro desactivado siempre se usan 2 min.
 */
object PoliticaEnergia {
    const val UMBRAL_BATERIA_BAJA = 20
    const val INTERVALO_NORMAL_MS = 2 * 60 * 1000L
    const val INTERVALO_AHORRO_MS = 10 * 60 * 1000L

    fun calcular(config: ConfigActivacion, bateria: EstadoBateria): PoliticaEscaneo {
        val ahorrar = config.ahorroBateria && !bateria.cargando &&
            bateria.nivelPorcentaje < UMBRAL_BATERIA_BAJA
        val intervalo = if (ahorrar) INTERVALO_AHORRO_MS else INTERVALO_NORMAL_MS
        val descripcion = buildString {
            append("Batería ${bateria.nivelPorcentaje} %")
            if (bateria.cargando) append(" (cargando)")
            append(": escaneo cada ${intervalo / 60_000} min")
            if (!config.retransmitirMalla) append(" · sin retransmitir alertas de otros")
        }
        return PoliticaEscaneo(intervalo, config.retransmitirMalla, descripcion)
    }
}
