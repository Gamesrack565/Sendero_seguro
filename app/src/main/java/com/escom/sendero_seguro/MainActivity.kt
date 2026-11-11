package com.escom.sendero_seguro

import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.escom.sendero_seguro.ui.navegacion.NavegacionApp
import com.escom.sendero_seguro.ui.theme.Sendero_seguroTheme
import org.osmdroid.config.Configuration

// FragmentActivity (hereda de ComponentActivity) para poder mostrar BiometricPrompt en A-05 y A-08.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar osmdroid para el mapa
        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()

        setContent {
            Sendero_seguroTheme {
                NavegacionApp()
            }
        }
    }
}
