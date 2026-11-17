package com.escom.sendero_seguro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.escom.sendero_seguro.emergencias.AvisoMallaUsuario
import com.escom.sendero_seguro.ui.mapa.ConfiguracionMapaOffline
import com.escom.sendero_seguro.ui.navegacion.NavegacionApp
import com.escom.sendero_seguro.ui.pantallas.red.AvisoRedApoyo
import com.escom.sendero_seguro.ui.theme.Sendero_seguroTheme
import retrofit2.HttpException
import java.io.IOException

// FragmentActivity (hereda de ComponentActivity) para poder mostrar BiometricPrompt en A-05 y A-08.
class MainActivity : FragmentActivity() {
    /** A-13: aviso de la red de apoyo con el que se abrió la app (push o enlace de ubicación). */
    private var avisoEntrante by mutableStateOf<AvisoRedApoyo?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        avisoDe(intent)?.let { avisoEntrante = it }
    }

    private fun avisoDe(intent: Intent?): AvisoRedApoyo? {
        intent ?: return null
        val enlace = intent.getStringExtra("enlace") ?: intent.data?.toString()?.takeIf { "/enlace/" in it } ?: return null
        return AvisoRedApoyo(
            enlace = enlace,
            nombre = intent.getStringExtra("nombre"),
            tipo = intent.getStringExtra("tipo"),
            folio = intent.getStringExtra("folio"),
            telefono = intent.getStringExtra("telefono")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        avisoEntrante = avisoDe(intent)

        ConfiguracionMapaOffline.inicializar(applicationContext)

        enableEdgeToEdge()
        val senderoApp = application as SenderoApp

        setContent {
            Sendero_seguroTheme {
                val permisosLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {}

                LaunchedEffect(Unit) {
                    senderoApp.emergencias.cargarEstadoColaLocal()
                }

                LaunchedEffect(senderoApp.reportes) {
                    try {
                        senderoApp.reportes.cargarCatalogo()
                    } catch (e: IOException) {
                        Log.i("MainActivity", "No se pudo precargar el catálogo; se volverá a intentar al abrir Reportar.", e)
                    } catch (e: HttpException) {
                        Log.w("MainActivity", "El servidor no permitió precargar el catálogo de reportes.", e)
                    }
                }

                LaunchedEffect(Unit) {
                    val permisos = mutableListOf<String>()
                    // Ubicación: la alerta, la ruta segura y los reportes la necesitan (RNF10: solo durante su uso).
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        permisos.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        permisos.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
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

                NavegacionApp(avisoEntrante = avisoEntrante)
            }
        }
    }
}
