package com.senderoseguro.server.comunidad

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Cálculos geográficos que comparten el feed, el mapa de calor y la ruta segura. */
object Geo {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private const val RADIO_TIERRA_M = 6_371_000.0

    data class Punto(val lat: Double, val lon: Double)
    data class Caja(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
        fun contiene(p: Punto, margenM: Double = 0.0): Boolean {
            val dLat = margenM / 111_320.0
            val dLon = margenM / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.01))
            return p.lat in (minLat - dLat)..(maxLat + dLat) && p.lon in (minLon - dLon)..(maxLon + dLon)
        }
    }

    /** Distancia en metros (la misma fórmula que `fn_distancia_m` de la base). */
    fun distanciaM(a: Punto, b: Punto): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * RADIO_TIERRA_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Geohash de [precision] caracteres. 7 caracteres ≈ celda de 150 m (la ubicación pública de un reporte). */
    fun geohash(lat: Double, lon: Double, precision: Int = 7): String {
        var latR = -90.0 to 90.0
        var lonR = -180.0 to 180.0
        val sb = StringBuilder()
        var bit = 0
        var ch = 0
        var esLon = true
        while (sb.length < precision) {
            if (esLon) {
                val mid = (lonR.first + lonR.second) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); lonR = mid to lonR.second } else lonR = lonR.first to mid
            } else {
                val mid = (latR.first + latR.second) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latR = mid to latR.second } else latR = latR.first to mid
            }
            esLon = !esLon
            if (bit < 4) bit++ else { sb.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }

    /** Centro de la celda de un geohash. */
    fun centro(hash: String): Punto {
        var latR = -90.0 to 90.0
        var lonR = -180.0 to 180.0
        var esLon = true
        for (c in hash) {
            val v = BASE32.indexOf(c)
            for (b in 4 downTo 0) {
                val uno = (v shr b) and 1 == 1
                if (esLon) {
                    val mid = (lonR.first + lonR.second) / 2
                    lonR = if (uno) mid to lonR.second else lonR.first to mid
                } else {
                    val mid = (latR.first + latR.second) / 2
                    latR = if (uno) mid to latR.second else latR.first to mid
                }
                esLon = !esLon
            }
        }
        return Punto((latR.first + latR.second) / 2, (lonR.first + lonR.second) / 2)
    }

    /** Envolvente convexa (cadena monótona). Regresa los vértices en orden antihorario, sin repetir el primero. */
    fun envolvente(puntos: List<Punto>): List<Punto> {
        val p = puntos.distinct().sortedWith(compareBy({ it.lon }, { it.lat }))
        if (p.size < 3) return p
        fun cruz(o: Punto, a: Punto, b: Punto) = (a.lon - o.lon) * (b.lat - o.lat) - (a.lat - o.lat) * (b.lon - o.lon)
        val abajo = mutableListOf<Punto>()
        for (x in p) { while (abajo.size >= 2 && cruz(abajo[abajo.size - 2], abajo.last(), x) <= 0) abajo.removeAt(abajo.size - 1); abajo += x }
        val arriba = mutableListOf<Punto>()
        for (x in p.reversed()) { while (arriba.size >= 2 && cruz(arriba[arriba.size - 2], arriba.last(), x) <= 0) arriba.removeAt(arriba.size - 1); arriba += x }
        return abajo.dropLast(1) + arriba.dropLast(1)
    }

    /** Cuadro de [radioM] alrededor de [c], para zonas con menos de 3 puntos distintos o en línea. */
    fun cuadro(c: Punto, radioM: Double): List<Punto> {
        val dLat = radioM / 111_320.0
        val dLon = radioM / (111_320.0 * cos(Math.toRadians(c.lat)).coerceAtLeast(0.01))
        return listOf(Punto(c.lat - dLat, c.lon - dLon), Punto(c.lat - dLat, c.lon + dLon), Punto(c.lat + dLat, c.lon + dLon), Punto(c.lat + dLat, c.lon - dLon))
    }
}
