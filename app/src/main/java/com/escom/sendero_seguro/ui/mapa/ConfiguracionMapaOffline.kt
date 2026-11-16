package com.escom.sendero_seguro.ui.mapa

import android.content.Context
import android.preference.PreferenceManager
import com.escom.sendero_seguro.mapas.PaquetesMapaOffline
import org.osmdroid.config.Configuration
import java.io.IOException

object ConfiguracionMapaOffline {
    fun inicializar(context: Context) {
        val appContext = context.applicationContext
        val configuration = Configuration.getInstance()
        configuration.load(appContext, PreferenceManager.getDefaultSharedPreferences(appContext))

        val directorioPaquetes = PaquetesMapaOffline.directorio(appContext)
        if (!directorioPaquetes.isDirectory && !directorioPaquetes.mkdirs()) {
            throw IOException("No se pudo preparar el directorio de mapas offline.")
        }
        configuration.osmdroidBasePath = directorioPaquetes
        configuration.userAgentValue = appContext.packageName
    }
}
