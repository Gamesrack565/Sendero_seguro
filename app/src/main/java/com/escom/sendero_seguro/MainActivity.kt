package com.escom.sendero_seguro

import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.escom.sendero_seguro.emergencias.ModuloEmergencias
import com.escom.sendero_seguro.ui.theme.Sendero_seguroTheme
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar osmdroid para el mapa
        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName

        enableEdgeToEdge()
        val modulo2 = ModuloEmergencias(this)

        setContent {
            Sendero_seguroTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PantallaDiagnosticoModulo2(
                        modulo = modulo2,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}