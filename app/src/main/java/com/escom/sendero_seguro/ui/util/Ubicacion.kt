package com.escom.sendero_seguro.ui.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.escom.sendero_seguro.ui.mapa.Punto
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Ubicación leída del teléfono, con su precisión. */
data class UbicacionLeida(val punto: Punto, val precisionM: Float?)

fun tienePermisoUbicacion(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Ubicación actual (A-15, A-16, A-18). Pide una lectura nueva (máx. 8 s) y si no llega usa la última conocida.
 * Solo se lee mientras la pantalla la necesita (RNF10: no se comparte fuera de una alerta).
 */
suspend fun leerUbicacionActual(context: Context): UbicacionLeida? {
    if (!tienePermisoUbicacion(context)) return null
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val proveedores = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val nueva = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && proveedores.isNotEmpty()) {
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                try {
                    lm.getCurrentLocation(proveedores.first(), null, context.mainExecutor) { cont.resume(it) }
                } catch (e: SecurityException) {
                    cont.resume(null)
                }
            }
        }
    } else null
    val ubicacion = nueva ?: proveedores.plus(LocationManager.PASSIVE_PROVIDER).mapNotNull {
        try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null }
    }.maxByOrNull { it.time }
    return ubicacion?.let { UbicacionLeida(Punto(it.latitude, it.longitude), it.accuracy.takeIf { a -> a > 0 }) }
}

/** Distancia en metros entre dos puntos (haversine). */
fun distanciaMetros(a: Punto, b: Punto): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.latitud - a.latitud)
    val dLon = Math.toRadians(b.longitud - a.longitud)
    val h = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(a.latitud)) * Math.cos(Math.toRadians(b.latitud)) * Math.sin(dLon / 2).let { it * it }
    return 2 * r * Math.asin(Math.sqrt(h))
}
