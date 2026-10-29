package com.escom.sendero_seguro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.escom.sendero_seguro.ui.navegacion.NavegacionApp
import com.escom.sendero_seguro.ui.theme.Sendero_seguroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Sendero_seguroTheme {
                NavegacionApp()
            }
        }
    }
}
