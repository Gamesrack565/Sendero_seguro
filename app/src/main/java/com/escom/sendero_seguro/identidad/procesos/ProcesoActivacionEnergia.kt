package com.escom.sendero_seguro.identidad.procesos

import com.escom.sendero_seguro.identidad.dominio.ConfigActivacion
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.PoliticaEnergia
import com.escom.sendero_seguro.identidad.dominio.PoliticaEscaneo
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import com.escom.sendero_seguro.identidad.dominio.exito
import com.escom.sendero_seguro.identidad.dominio.falla
import com.escom.sendero_seguro.identidad.puertos.ClavesConfig
import com.escom.sendero_seguro.identidad.puertos.FuenteBateria
import com.escom.sendero_seguro.identidad.puertos.GestorEnergia
import com.escom.sendero_seguro.identidad.puertos.RepositorioConfigLocal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * P3. Configuración de Activación Rápida, Energía y Red Malla (A-12).
 * Todo vive en config_local: funciona sin internet y lo leen el Módulo 2 (atajo, widget,
 * cuenta regresiva, escaneo de la malla) y el Módulo 3 (mapas solo con Wi-Fi).
 *
 * Composición: configurarAtajosSinDesbloquear() + definirCuentaRegresivaPreEnvio()
 *            + ajustarEscaneoMallaPorBateria()
 */
class ProcesoActivacionEnergia(
    private val config: RepositorioConfigLocal,
    private val bateria: FuenteBateria,
    private val energia: GestorEnergia
) {

    /** Configuración vigente con los valores por defecto del E3 cuando una clave no existe. */
    fun observarConfig(): Flow<ConfigActivacion> =
        config.observarTodo().map { m -> desdeMapa(m) }.distinctUntilChanged()

    suspend fun configActual(): ConfigActivacion = observarConfig().first()

    /** Botón de encendido ×5 y widget rojo de un toque. El Módulo 2 reacciona a estos valores. */
    suspend fun configurarAtajosSinDesbloquear(atajoActivo: Boolean? = null, widgetActivo: Boolean? = null) {
        atajoActivo?.let { config.escribir(ClavesConfig.ATAJO_ACTIVO, it.toString()) }
        widgetActivo?.let { config.escribir(ClavesConfig.WIDGET_ACTIVO, it.toString()) }
    }

    /** Temporizador previo al envío: 3, 5 o 10 s. */
    suspend fun definirCuentaRegresivaPreEnvio(segundos: Int): Resultado<Unit> {
        Validaciones.validarCuentaRegresiva(segundos)?.let {
            return falla(ErrorIdentidad.Validacion(mapOf(Validaciones.CUENTA_REGRESIVA to it)))
        }
        config.escribir(ClavesConfig.CUENTA_REGRESIVA_SEG, segundos.toString())
        return exito(Unit)
    }

    suspend fun configurarRedMalla(retransmitir: Boolean? = null, ahorroBateria: Boolean? = null) {
        retransmitir?.let { config.escribir(ClavesConfig.RETRANSMITIR_MALLA, it.toString()) }
        ahorroBateria?.let { config.escribir(ClavesConfig.AHORRO_BATERIA, it.toString()) }
    }

    suspend fun configurarMapasSoloWifi(soloWifi: Boolean) {
        config.escribir(ClavesConfig.MAPAS_SOLO_WIFI, soloWifi.toString())
    }

    /**
     * Política de escaneo de la malla que cambia con la batería y con la configuración.
     * El Módulo 2 la recolecta para programar el escaneo BLE / Wi-Fi Direct.
     */
    fun ajustarEscaneoMallaPorBateria(): Flow<PoliticaEscaneo> =
        combine(observarConfig(), bateria.observar()) { c, b -> PoliticaEnergia.calcular(c, b) }
            .distinctUntilChanged()

    /** true si Android puede pausar la app en Doze; la UI debe pedir la exclusión (A-12). */
    fun requiereExclusionAhorroEnergia(): Boolean = !energia.estaExentoDeOptimizacion()

    companion object {
        fun desdeMapa(m: Map<String, String>): ConfigActivacion {
            val d = ConfigActivacion()
            fun bool(k: String, def: Boolean) = m[k]?.toBooleanStrictOrNull() ?: def
            val seg = m[ClavesConfig.CUENTA_REGRESIVA_SEG]?.toIntOrNull()
                ?.takeIf { it in Validaciones.CUENTAS_REGRESIVAS_PERMITIDAS } ?: d.cuentaRegresivaSeg
            return ConfigActivacion(
                atajoActivo = bool(ClavesConfig.ATAJO_ACTIVO, d.atajoActivo),
                widgetActivo = bool(ClavesConfig.WIDGET_ACTIVO, d.widgetActivo),
                cuentaRegresivaSeg = seg,
                retransmitirMalla = bool(ClavesConfig.RETRANSMITIR_MALLA, d.retransmitirMalla),
                ahorroBateria = bool(ClavesConfig.AHORRO_BATERIA, d.ahorroBateria),
                mapasSoloWifi = bool(ClavesConfig.MAPAS_SOLO_WIFI, d.mapasSoloWifi)
            )
        }
    }
}
