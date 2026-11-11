package com.senderoseguro.server.comunidad

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.exigirRol
import com.senderoseguro.server.plataforma.ipCliente
import com.senderoseguro.server.plataforma.usuarioActual
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Los servicios del Módulo 3 en el servidor. */
class Comunidad(
    val reportes: ReportesServicio,
    val mapaCalor: MapaCalorServicio,
    val rutas: RutaSeguraServicio,
    val mapas: MapasServicio,
    val versionCatalogo: () -> Int?,
)

/**
 * Módulo 3 en el servidor (P9 a P12), todo con token:
 * - Reportes: `POST reportes`, `GET reportes/mios`.
 * - Moderación (Administrador): `GET moderacion/reportes`, `GET moderacion/reportes/{id}`, `POST reportes/{id}/decision`,
 *   `POST reportes/{id}/identidad`.
 * - Feed: `GET feed`. Mapa de calor: `GET mapa-calor`, `GET mapa-calor/zonas/{id}`, `POST mapa-calor/generar` (Administrador).
 * - Ruta segura: `POST rutas/segura`, `GET grafo`, `POST grafo` (Administrador).
 * - Mapas offline: `GET mapas/version`, `GET mapas/paquetes`, `POST mapas/paquetes` (Administrador).
 * - `GET|POST puntos-interes`, `GET|POST zonas` (el POST es del Administrador).
 */
fun Route.rutasComunidad(c: Comunidad) {
    authenticate(AUTH_JWT) {
        post("/reportes") {
            val u = call.usuarioActual()
            val e = call.cuerpo<ReporteEntrada>()
            val ip = call.ipCliente()
            val (r, nuevo) = io { c.reportes.crear(u.usuarioId, e, ip) }
            call.respond(if (nuevo) HttpStatusCode.Created else HttpStatusCode.OK, r)
        }
        get("/reportes/mios") {
            val u = call.usuarioActual()
            call.respond(io { c.reportes.mios(u.usuarioId) })
        }
        get("/moderacion/reportes") {
            call.exigirRol(Rol.ADMINISTRADOR)
            val q = call.request.queryParameters
            call.respond(io { c.reportes.colaModeracion(q["estado"], q["limite"]?.toIntOrNull() ?: 50) })
        }
        get("/moderacion/reportes/{id}") {
            call.exigirRol(Rol.ADMINISTRADOR)
            call.respond(io { c.reportes.paraModerar(call.id()) })
        }
        post("/reportes/{id}/decision") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val d = call.cuerpo<DecisionEntrada>()
            val ip = call.ipCliente()
            call.respond(io { c.reportes.decidir(u, call.id(), d, ip) })
        }
        post("/reportes/{id}/identidad") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val s = call.cuerpo<SolicitudIdentidad>()
            val ip = call.ipCliente()
            call.respond(io { c.reportes.identidad(u, call.id(), s, ip) })
        }

        get("/feed") {
            call.usuarioActual()
            val q = call.request.queryParameters
            call.respond(io {
                c.reportes.feed(
                    tipoId = q.entero("tipoIncidenteId"), categoria = q["categoria"], dias = q.entero("dias"),
                    lat = q.decimal("latitud"), lon = q.decimal("longitud"), radioM = q.decimal("radioM"), zonaId = q.entero("zonaId"),
                    siguiente = q["siguiente"], limite = q.entero("limite") ?: 30,
                )
            })
        }

        get("/mapa-calor") {
            call.usuarioActual()
            val q = call.request.queryParameters
            val tiene = q.entero("version")
            if (tiene != null && tiene == io { c.mapaCalor.versionVigente() }) {
                call.respond(HttpStatusCode.NotModified)
                return@get
            }
            call.respond(io { c.mapaCalor.capa(q.entero("periodo") ?: 30, q.entero("tipoIncidenteId")) })
        }
        get("/mapa-calor/zonas/{id}") {
            call.usuarioActual()
            val id = call.parameters["id"]?.toLongOrNull() ?: throw errorValidacion(mapOf("id" to "No es un id de zona"))
            call.respond(io { c.mapaCalor.zona(id) })
        }
        post("/mapa-calor/generar") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            call.respond(io { c.mapaCalor.generar(u.usuarioId) })
        }

        post("/rutas/segura") {
            call.usuarioActual()
            val s = call.cuerpo<SolicitudRuta>()
            call.respond(io { c.rutas.calcular(s) })
        }
        get("/grafo") {
            call.usuarioActual()
            call.respond(io { c.rutas.grafo(call.caja() ?: throw errorValidacion(mapOf("caja" to "Manda minLatitud, minLongitud, maxLatitud y maxLongitud"))) })
        }
        post("/grafo") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val g = call.cuerpo<GrafoEntrada>()
            val ip = call.ipCliente()
            call.respond(io { c.rutas.cargarGrafo(u, g, ip) })
        }

        get("/mapas/version") {
            call.usuarioActual()
            call.respond(io { c.mapas.versiones(c.mapaCalor.versionVigente(), c.versionCatalogo()) })
        }
        get("/mapas/paquetes") {
            call.usuarioActual()
            call.respond(io { c.mapas.paquetes() })
        }
        post("/mapas/paquetes") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val e = call.cuerpo<PaqueteEntrada>()
            val ip = call.ipCliente()
            call.respond(HttpStatusCode.Created, io { c.mapas.publicarPaquete(u, e, ip) })
        }

        get("/puntos-interes") {
            call.usuarioActual()
            call.respond(io { c.mapas.puntosInteres(call.caja()) })
        }
        post("/puntos-interes") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val e = call.cuerpo<PuntoInteresEntrada>()
            val ip = call.ipCliente()
            call.respond(HttpStatusCode.Created, io { c.mapas.crearPuntoInteres(u, e, ip) })
        }
        get("/zonas") {
            call.usuarioActual()
            call.respond(io { c.mapas.zonas() })
        }
        post("/zonas") {
            val u = call.exigirRol(Rol.ADMINISTRADOR)
            val e = call.cuerpo<ZonaGeograficaEntrada>()
            val ip = call.ipCliente()
            call.respond(HttpStatusCode.Created, io { c.mapas.crearZona(u, e, ip) })
        }
    }
}

private fun ApplicationCall.id(): UUID =
    parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "El reporte no existe")

private fun io.ktor.http.Parameters.entero(nombre: String): Int? =
    this[nombre]?.let { it.toIntOrNull() ?: throw errorValidacion(mapOf(nombre to "Debe ser un número entero")) }

private fun io.ktor.http.Parameters.decimal(nombre: String): Double? =
    this[nombre]?.let { it.toDoubleOrNull() ?: throw errorValidacion(mapOf(nombre to "Debe ser un número")) }

/** `?minLatitud=&minLongitud=&maxLatitud=&maxLongitud=`; null si no viene ninguno. */
private fun ApplicationCall.caja(): Geo.Caja? {
    val q = request.queryParameters
    val valores = listOf("minLatitud", "minLongitud", "maxLatitud", "maxLongitud").map { q.decimal(it) }
    if (valores.all { it == null }) return null
    if (valores.any { it == null }) throw errorValidacion(mapOf("caja" to "Manda las cuatro: minLatitud, minLongitud, maxLatitud y maxLongitud"))
    return Geo.Caja(valores[0]!!, valores[1]!!, valores[2]!!, valores[3]!!)
}

private suspend inline fun <reified T : Any> ApplicationCall.cuerpo(): T = runCatching { receive<T>() }
    .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Cuerpo inválido: revisa los campos obligatorios") }

private suspend fun <T> io(bloque: () -> T): T = withContext(Dispatchers.IO) { bloque() }
