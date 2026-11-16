package com.escom.sendero_seguro.mapas

import com.escom.sendero_seguro.emergencias.AristaVialOffline
import com.escom.sendero_seguro.emergencias.PuntoRuta
import com.escom.sendero_seguro.mapas.remoto.GrafoMapaDto
import java.io.IOException

data class GrafoOfflineP7(
    val aristas: List<AristaVialOffline>,
    val versionGrafo: Int,
    val versionCapaRiesgo: Int,
)

internal fun GrafoMapaDto.aGrafoOfflineP7(
    versionGrafoLocal: Int,
    versionCapaLocal: Int,
): GrafoOfflineP7 {
    val nodosPorId = nodos.associate { nodo ->
        if (!nodo.latitud.isFinite() || nodo.latitud !in -90.0..90.0 ||
            !nodo.longitud.isFinite() || nodo.longitud !in -180.0..180.0
        ) {
            throw IOException("El grafo descargado contiene coordenadas inválidas.")
        }
        nodo.id to PuntoRuta(nodo.latitud, nodo.longitud, nodo.id)
    }
    if (nodosPorId.size != nodos.size) {
        throw IOException("El grafo descargado contiene identificadores de nodo duplicados.")
    }

    val aristasP7 = aristas.map { arista ->
        val origen = nodosPorId[arista.origen]
            ?: throw IOException("El tramo ${arista.id} referencia un nodo de origen inexistente.")
        val destino = nodosPorId[arista.destino]
            ?: throw IOException("El tramo ${arista.id} referencia un nodo de destino inexistente.")
        if (!arista.longitudM.isFinite() || arista.longitudM <= 0.0 ||
            !arista.riesgo.isFinite() || arista.riesgo !in 0.0..1.0
        ) {
            throw IOException("El tramo ${arista.id} contiene longitud o riesgo inválidos.")
        }
        AristaVialOffline(
            idArista = arista.id,
            nodoOrigen = origen,
            nodoDestino = destino,
            nombreVia = "Tramo ${arista.id}",
            longitudMetros = arista.longitudM,
            riesgoNormalizado = arista.riesgo,
            bienIluminada = true, // El contrato P12 no incluye iluminación.
            bidireccional = arista.bidireccional,
        )
    }

    return GrafoOfflineP7(
        aristas = aristasP7,
        versionGrafo = versionGrafo ?: versionGrafoLocal,
        versionCapaRiesgo = capaVersion ?: versionCapaLocal,
    )
}
