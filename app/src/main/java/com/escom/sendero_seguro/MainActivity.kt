package com.escom.sendero_seguro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.util.Log
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
import com.escom.sendero_seguro.ui.pantallas.mapa.PantallaMapaP12
import com.escom.sendero_seguro.ui.pantallas.reporte.PantallaReporteComunitario
import com.escom.sendero_seguro.ui.mapa.ConfiguracionMapaOffline
import com.escom.sendero_seguro.emergencias.AvisoMallaUsuario
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

// FragmentActivity (hereda de ComponentActivity) para poder mostrar BiometricPrompt en A-05 y A-08.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConfiguracionMapaOffline.inicializar(applicationContext)

        enableEdgeToEdge()
        val senderoApp = application as SenderoApp
        val modulo2 = senderoApp.emergencias

        setContent {
            Sendero_seguroTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val estadoEnvio by modulo2.estadoEnvioAlerta.collectAsState()
                    val scope = rememberCoroutineScope()
                    var mostrarDiagnostico by remember { mutableStateOf(false) }
                    var mostrarMapaP12 by remember { mutableStateOf(false) }
                    var mostrarReporte by remember { mutableStateOf(false) }
                    val permisosLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {}

                    LaunchedEffect(Unit) {
                        modulo2.cargarEstadoColaLocal()
                    }

                    LaunchedEffect(senderoApp.reportes) {
                        try {
                            senderoApp.reportes.cargarCatalogo()
                        } catch (e: IOException) {
                            Log.i(
                                "MainActivity",
                                "No se pudo precargar el catálogo; se volverá a intentar al abrir Reportar.",
                                e,
                            )
                        } catch (e: HttpException) {
                            Log.w("MainActivity", "El servidor no permitió precargar el catálogo de reportes.", e)
                        }
                    }

                    LaunchedEffect(Unit) {
                        val permisos = mutableListOf<String>()
                        if (BuildConfig.DEBUG && BuildConfig.SMS_GATEWAY_NUMBER.isNotBlank() &&
                            checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permisos.add(Manifest.permission.SEND_SMS)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                            !AvisoMallaUsuario.yaSeSolicitoPermisoNotificaciones(this@MainActivity)
                        ) {
                            AvisoMallaUsuario.marcarSolicitudPermisoNotificaciones(this@MainActivity)
                            permisos.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        if (permisos.isNotEmpty()) permisosLauncher.launch(permisos.toTypedArray())
                    }

                    Column(Modifier.fillMaxSize().padding(innerPadding)) {
                        if (mostrarMapaP12) {
                            Box(Modifier.weight(1f)) {
                                PantallaMapaP12(
                                    moduloMapas = senderoApp.mapas,
                                    moduloReportes = senderoApp.reportes,
                                    onVolver = { mostrarMapaP12 = false },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else if (mostrarReporte) {
                            Box(Modifier.weight(1f)) {
                                PantallaReporteComunitario(
                                    moduloReportes = senderoApp.reportes,
                                    prepararSesion = { senderoApp.prepararSesionPruebaLocal() },
                                    onVolver = { mostrarReporte = false },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else if (mostrarDiagnostico) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                TextButton(onClick = { mostrarDiagnostico = false }) {
                                    Text("Volver al inicio")
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                PantallaDiagnosticoModulo2(
                                    modulo = modulo2,
                                    moduloMapas = senderoApp.mapas,
                                    modifier = Modifier.fillMaxSize(),
                                )
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
                                    onReportar = { mostrarReporte = true },
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
                                if (senderoApp.p12LocalHabilitado()) {
                                    TextButton(onClick = { mostrarMapaP12 = true }) {
                                        Text("Mapa P12")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}