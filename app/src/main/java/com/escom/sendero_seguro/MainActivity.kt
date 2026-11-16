package com.escom.sendero_seguro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.escom.sendero_seguro.ui.theme.Sendero_seguroTheme
import com.escom.sendero_seguro.ui.demo.DatosDemo
import com.escom.sendero_seguro.ui.modelo.CanalEnvio
import com.escom.sendero_seguro.ui.pantallas.inicio.EstadoInicio
import com.escom.sendero_seguro.ui.pantallas.inicio.PantallaInicio
import kotlinx.coroutines.launch
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
        val senderoApp = application as SenderoApp
        val modulo2 = senderoApp.emergencias

        setContent {
            Sendero_seguroTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val estadoEnvio by modulo2.estadoEnvioAlerta.collectAsState()
                    val scope = rememberCoroutineScope()
                    var mostrarDiagnostico by remember { mutableStateOf(false) }
                    val smsPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) {}

                    LaunchedEffect(Unit) {
                        modulo2.cargarEstadoColaLocal()
                    }

                    LaunchedEffect(Unit) {
                        if (BuildConfig.DEBUG && BuildConfig.SMS_GATEWAY_NUMBER.isNotBlank() &&
                            checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                        }
                    }

                    Column(Modifier.fillMaxSize().padding(innerPadding)) {
                        if (mostrarDiagnostico) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                TextButton(onClick = { mostrarDiagnostico = false }) {
                                    Text("Volver al inicio")
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                PantallaDiagnosticoModulo2(modulo2, Modifier.fillMaxSize())
                            }
                        } else {
                            Box(Modifier.weight(1f)) {
                                PantallaInicio(
                                    estado = EstadoInicio(
                                        DatosDemo.NOMBRE,
                                        if (modulo2.gestorMalla.hayConexionAInternet()) CanalEnvio.INTERNET else CanalEnvio.EN_COLA,
                                        DatosDemo.CONTACTOS,
                                        DatosDemo.CIRCULO,
                                        DatosDemo.INTEGRANTES_CIRCULO
                                    ),
                                    onPedirAyuda = {},
                                    onConectividad = { mostrarDiagnostico = true },
                                    onRutaSegura = {},
                                    onReportar = {},
                                    onRedApoyo = {},
                                    modifier = Modifier.fillMaxSize(),
                                    onEnviarAlertaPrueba = { datos ->
                                        scope.launch {
                                            val error = senderoApp.prepararSesionPruebaLocal()
                                            if (error != null) {
                                                modulo2.mostrarErrorPreparandoPrueba(error)
                                            } else {
                                                modulo2.enviarAlertaPrueba(datos)
                                            }
                                        }
                                    },
                                    estadoEnvioAlerta = estadoEnvio
                                )
                            }
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                TextButton(onClick = { mostrarDiagnostico = true }) {
                                    Text("Diagnóstico P5.1 / P6 / P7")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}