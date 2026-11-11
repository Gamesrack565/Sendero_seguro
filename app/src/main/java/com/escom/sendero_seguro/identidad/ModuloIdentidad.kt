package com.escom.sendero_seguro.identidad

import android.content.Context
import com.escom.sendero_seguro.BuildConfig
import com.escom.sendero_seguro.core.db.SenderoDatabase
import com.escom.sendero_seguro.identidad.datos.local.ColaCancelacionesRoom
import com.escom.sendero_seguro.identidad.datos.local.RepositorioConfigRoom
import com.escom.sendero_seguro.identidad.datos.local.RepositorioContactosRoom
import com.escom.sendero_seguro.identidad.datos.remoto.ClienteHttp
import com.escom.sendero_seguro.identidad.datos.remoto.ServicioRemotoRetrofit
import com.escom.sendero_seguro.identidad.datos.seguro.AlmacenSeguroKeystore
import com.escom.sendero_seguro.identidad.plataforma.FuenteBateriaAndroid
import com.escom.sendero_seguro.identidad.plataforma.GestorEnergiaAndroid
import com.escom.sendero_seguro.identidad.plataforma.InfoHardwareAndroid
import com.escom.sendero_seguro.identidad.procesos.ProcesoActivacionEnergia
import com.escom.sendero_seguro.identidad.procesos.ProcesoAutenticacion
import com.escom.sendero_seguro.identidad.procesos.ProcesoCancelacion
import com.escom.sendero_seguro.identidad.procesos.ProcesoRedApoyo
import com.escom.sendero_seguro.identidad.puertos.AlmacenSeguro
import com.escom.sendero_seguro.identidad.puertos.RepositorioConfigLocal
import com.escom.sendero_seguro.identidad.puertos.ServicioRemotoIdentidad

/**
 * Módulo Backend 1: Identidad, Contactos, Configuración y Control de Coacción.
 *   P1 → [autenticacion]   P2 → [redApoyo]   P3 → [activacion]   P8 → [cancelacion]
 *
 * Punto de entrada para los demás módulos y para las pantallas:
 *   val m = (application as SenderoApp).identidad
 *   m.cancelacion.eventos.collect { … }          // Módulo 2: detener la alerta
 *   m.activacion.ajustarEscaneoMallaPorBateria() // Módulo 2: intervalo de escaneo
 *   m.autenticacion.sesionLocal()                // ¿quién emite la alerta? (sin internet)
 */
class ModuloIdentidad(context: Context) {

    private val app = context.applicationContext
    private val db = SenderoDatabase.obtener(app)

    val almacen: AlmacenSeguro = AlmacenSeguroKeystore(app)
    val config: RepositorioConfigLocal = RepositorioConfigRoom(db.configLocalDao())
    private val contactosLocal = RepositorioContactosRoom(db.contactoLocalDao(), db.configLocalDao())
    val gestorEnergia = GestorEnergiaAndroid(app)

    private val remoto: ServicioRemotoIdentidad = ServicioRemotoRetrofit(
        ClienteHttp.crear(BuildConfig.API_BASE_URL, almacen, BuildConfig.DEBUG),
        ClienteHttp.json
    )

    val autenticacion = ProcesoAutenticacion(remoto, config, almacen, contactosLocal, InfoHardwareAndroid(app))
    val redApoyo = ProcesoRedApoyo(remoto, config, contactosLocal)
    val activacion = ProcesoActivacionEnergia(config, FuenteBateriaAndroid(app), gestorEnergia)
    val cancelacion = ProcesoCancelacion(remoto, config, almacen, ColaCancelacionesRoom(db.colaAlertaDao()))

    /**
     * Sube lo que quedó pendiente sin señal (contactos, cancelaciones e intentos de cancelación fallidos).
     * SenderoApp lo llama al abrir la app y cada vez que vuelve internet.
     */
    suspend fun sincronizarPendientes() {
        if (autenticacion.sesionLocal()?.tokenVigente != true) return
        redApoyo.sincronizarContactos()
        cancelacion.sincronizarCancelacionesPendientes()
        cancelacion.sincronizarIntentosFallidosPendientes()
    }
}
