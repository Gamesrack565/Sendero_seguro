package com.escom.sendero_seguro.ui.navegacion

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.escom.sendero_seguro.ui.demo.DatosDemo
import com.escom.sendero_seguro.ui.pantallas.PantallaEnConstruccion
import com.escom.sendero_seguro.ui.pantallas.PantallaGaleria
import com.escom.sendero_seguro.ui.pantallas.inicio.EstadoInicio
import com.escom.sendero_seguro.ui.pantallas.inicio.PantallaInicio
import com.escom.sendero_seguro.ui.theme.Fondo
import com.escom.sendero_seguro.ui.theme.RojoTexto
import com.escom.sendero_seguro.ui.theme.Superficie
import com.escom.sendero_seguro.ui.theme.TextoSecundario

private data class Pestana(val pantalla: Pantalla, val etiqueta: String, val icono: ImageVector)

// Barra inferior de A-04: Alerta · Mapa · Reportes · Perfil
private val Pestanas = listOf(
    Pestana(Pantalla.A04, "Alerta", Icons.Outlined.Shield),
    Pestana(Pantalla.A17, "Mapa", Icons.Outlined.Map),
    Pestana(Pantalla.A16, "Reportes", Icons.AutoMirrored.Outlined.Notes),
    Pestana(Pantalla.A11, "Perfil", Icons.Outlined.Person)
)

/** Navegación de la app según el mapa de navegación del E3 (G-02). */
@Composable
fun NavegacionApp(nav: NavHostController = rememberNavController()) {
    val entrada by nav.currentBackStackEntryAsState()
    val actual = Pantalla.deRuta(entrada?.destination?.route)
    val esDesarrollo = LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    Scaffold(
        containerColor = Fondo,
        // Las pantallas de alerta no tienen barra inferior, para evitar toques accidentales.
        bottomBar = { if (actual?.conBarra == true) BarraInferior(actual) { nav.irAPestana(it) } }
    ) { relleno ->
        NavHost(nav, startDestination = Pantalla.A03.ruta, modifier = Modifier.fillMaxSize().padding(relleno)) {
            composable(Pantalla.A04.ruta) {
                PantallaInicio(
                    estado = EstadoInicio(
                        DatosDemo.NOMBRE, DatosDemo.CANAL, DatosDemo.CONTACTOS,
                        DatosDemo.CIRCULO, DatosDemo.INTEGRANTES_CIRCULO
                    ),
                    onPedirAyuda = { nav.navigate(Pantalla.A05.ruta) },
                    onConectividad = { nav.navigate(Pantalla.A19.ruta) },
                    onRutaSegura = { nav.navigate(Pantalla.A18.ruta) },
                    onReportar = { nav.navigate(Pantalla.A15.ruta) },
                    onRedApoyo = { nav.navigate(Pantalla.A09.ruta) }
                )
            }
            composable(Pantalla.GALERIA.ruta) { PantallaGaleria(onVolver = { nav.popBackStack() }) }

            // Pantallas aún por construir: se pueden recorrer siguiendo el mapa de navegación.
            Pantalla.entries
                .filter { it != Pantalla.A04 && it != Pantalla.GALERIA }
                .forEach { p ->
                    composable(p.ruta) {
                        val extra = buildList {
                            if ((p == Pantalla.A03 || p == Pantalla.A11) && esDesarrollo) {
                                add("Galería de componentes (desarrollo)" to { nav.navigate(Pantalla.GALERIA.ruta) })
                                add("A-13 · Aviso a la red de apoyo" to { nav.navigate(Pantalla.A13.ruta) })
                                add("A-14 · Modo operador" to { nav.navigate(Pantalla.A14.ruta) })
                            }
                        }
                        PantallaEnConstruccion(
                            pantalla = p,
                            siguientes = SiguientesPantallas[p].orEmpty(),
                            onIr = { destino -> nav.irA(destino) },
                            onVolver = if (p.conBarra || p == Pantalla.A03) null else ({ nav.popBackStack() }),
                            extra = extra
                        )
                    }
                }
        }
    }
}

/** A-04 es la raíz: llegar a ella (por sesión o al cerrar una alerta) limpia lo anterior. */
private fun NavHostController.irA(destino: Pantalla) {
    if (destino == Pantalla.A04) {
        navigate(Pantalla.A04.ruta) { popUpTo(0) { inclusive = true } }
    } else {
        navigate(destino.ruta)
    }
}

private fun NavHostController.irAPestana(destino: Pantalla) {
    navigate(destino.ruta) {
        popUpTo(Pantalla.A04.ruta) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BarraInferior(actual: Pantalla, onElegir: (Pantalla) -> Unit) {
    NavigationBar(containerColor = Superficie) {
        Pestanas.forEach { p ->
            NavigationBarItem(
                selected = p.pantalla == actual,
                onClick = { onElegir(p.pantalla) },
                icon = { Icon(p.icono, contentDescription = null) },
                label = { Text(p.etiqueta, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = RojoTexto,
                    selectedTextColor = RojoTexto,
                    indicatorColor = Superficie,
                    unselectedIconColor = TextoSecundario,
                    unselectedTextColor = TextoSecundario
                )
            )
        }
    }
}
