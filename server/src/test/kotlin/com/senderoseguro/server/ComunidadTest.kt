package com.senderoseguro.server

import com.senderoseguro.server.comunidad.AristaEntrada
import com.senderoseguro.server.comunidad.AutorProtegido
import com.senderoseguro.server.comunidad.Coordenada
import com.senderoseguro.server.comunidad.DecisionEntrada
import com.senderoseguro.server.comunidad.EvidenciaEntrada
import com.senderoseguro.server.comunidad.GrafoEntrada
import com.senderoseguro.server.comunidad.MapaCalorServicio
import com.senderoseguro.server.comunidad.MapasServicio
import com.senderoseguro.server.comunidad.NodoEntrada
import com.senderoseguro.server.comunidad.PaqueteEntrada
import com.senderoseguro.server.comunidad.ReporteEntrada
import com.senderoseguro.server.comunidad.ReportesServicio
import com.senderoseguro.server.comunidad.RutaSeguraServicio
import com.senderoseguro.server.comunidad.SolicitudIdentidad
import com.senderoseguro.server.comunidad.SolicitudRuta
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.sincronizacion.ContextoSync
import com.senderoseguro.server.sincronizacion.ElementoSync
import com.senderoseguro.server.sincronizacion.Sincronizacion
import com.senderoseguro.server.sincronizacion.TipoSync
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Módulo 3 en el servidor: reportes, moderación, identidad protegida, feed, mapa de calor, ruta segura y mapas offline. */
class ComunidadTest : PruebaConBaseDatos() {

    private val admin = UsuarioToken(AlertasPrueba.CUENTAS.getValue("admin"), Rol.ADMINISTRADOR, null, null)
    private val ana = AlertasPrueba.ANA
    private val autor = AutorProtegido("llave-de-prueba-de-reportes-anonimos")
    private val mapaCalor by lazy { MapaCalorServicio(dataSource) }
    private val reportes by lazy { ReportesServicio(dataSource, autor) }
    private val rutas by lazy { RutaSeguraServicio(dataSource, mapaCalor) }
    private fun codigoDe(bloque: () -> Unit): String = assertFailsWith<ApiException> { bloque() }.codigo
    private fun tipo(codigo: String) = numero("SELECT id FROM tipo_incidente WHERE codigo = ?", codigo)

    /** Un lugar al azar lejos de los reportes de otras corridas, para que el mapa de calor no los mezcle. */
    private var lat0 = 0.0
    private var lon0 = 0.0

    @BeforeTest
    fun lugarNuevo() {
        lat0 = 19.30 + Random.nextDouble(0.0, 0.15)
        lon0 = -99.25 + Random.nextDouble(0.0, 0.15)
    }
    private fun punto(esteM: Double, norteM: Double) = lat0 + norteM / 111_320.0 to lon0 + esteM / 104_950.0

    private fun reporte(
        tipo: String = "ACOSO", anonimo: Boolean = false, este: Double = 0.0, norte: Double = 0.0,
        descripcion: String = "Un hombre molesta a quien pasa por la entrada",
    ): ReporteEntrada {
        val (lat, lon) = punto(este, norte)
        return ReporteEntrada(UUID.randomUUID().toString(), tipo(tipo), null, descripcion, lat, lon, 8.0, anonimo, Instant.now().toString())
    }

    private fun publicar(e: ReporteEntrada, oculta: Boolean = false) =
        reportes.decidir(admin, UUID.fromString(reportes.crear(ana, e, null).first.id), DecisionEntrada("PUBLICAR", ocultaDatosPersonales = oculta), null)

    @Test
    fun `un reporte queda PENDIENTE, no se duplica y valida tipo y evidencias`() {
        val e = reporte()
        val (r, nuevo) = reportes.crear(ana, e, null)
        assertTrue(nuevo)
        assertEquals("PENDIENTE", r.estado)
        assertTrue(r.folio.startsWith("REP-"))
        assertEquals(7, r.celda.length)
        val (otra, nuevo2) = reportes.crear(ana, e, null)
        assertEquals(r.folio, otra.folio)
        assertEquals(false, nuevo2)
        assertTrue(reportes.mios(ana).any { it.folio == r.folio })

        // AGRESION_FISICA es solo de alerta.
        assertEquals("VALIDACION", codigoDe { reportes.crear(ana, reporte(tipo = "AGRESION_FISICA"), null) })
        assertEquals("VALIDACION", codigoDe { reportes.crear(ana, reporte(descripcion = "corto"), null) })
        val mala = reporte().copy(evidencias = listOf(EvidenciaEntrada("../secreto.jpg", "image/jpeg", 10, "0".repeat(64))))
        assertEquals("VALIDACION", codigoDe { reportes.crear(ana, mala, null) })
        val buena = reporte().copy(evidencias = listOf(EvidenciaEntrada("evidencia/${UUID.randomUUID()}.jpg", "image/jpeg", 2048, "a".repeat(64))))
        assertEquals(1, reportes.paraModerar(UUID.fromString(reportes.crear(ana, buena, null).first.id)).evidencias.size)
    }

    @Test
    fun `el autor anonimo se guarda cifrado y solo se consulta con justificacion`() {
        val (r, _) = reportes.crear(ana, reporte(anonimo = true), null)
        val id = UUID.fromString(r.id)
        assertNull(texto("SELECT autor_id::text FROM reporte WHERE id = ?", id))
        assertEquals("Anónimo", reportes.paraModerar(id).autor)
        assertTrue(reportes.mios(ana).none { it.id == r.id })
        assertEquals(0, numero("SELECT count(*) FROM bitacora WHERE accion = 'LEVANTAR_REPORTE' AND entidad_id = ? AND usuario_id IS NOT NULL", r.id))

        assertEquals("VALIDACION", codigoDe { reportes.identidad(admin, id, SolicitudIdentidad("Es abusivo"), null) })
        val quien = reportes.identidad(admin, id, SolicitudIdentidad("El reporte acusa a una persona con su nombre completo"), null)
        assertEquals(ana.toString(), quien.usuarioId)
        assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'CONSULTAR_IDENTIDAD_PROTEGIDA' AND entidad_id = ?", r.id))

        val conNombre = UUID.fromString(reportes.crear(ana, reporte(), null).first.id)
        assertEquals("NO_ES_ANONIMO", codigoDe { reportes.identidad(admin, conNombre, SolicitudIdentidad("x".repeat(30)), null) })
        // Otro con la misma llave no lo descifra si la llave cambia.
        assertNotEquals(ana, runCatching { AutorProtegido("otra-llave-distinta-de-la-de-prueba").descifrar(autor.cifrar(ana)) }.getOrNull())
    }

    @Test
    fun `publicar crea la version publica aproximada, rechazar avisa al autor y retirar la quita del feed`() {
        val publicado = publicar(reporte(descripcion = "Me escribieron del 55 1234 5678 y de acoso@ejemplo.mx"), oculta = true)
        assertEquals("PUBLICADO", publicado.estado)
        val id = UUID.fromString(publicado.id)
        val textoPublico = texto("SELECT descripcion FROM reporte_publico WHERE reporte_id = ?", id)!!
        assertTrue("1234" !in textoPublico && "@" !in textoPublico, textoPublico)
        assertEquals(7, texto("SELECT celda FROM reporte_publico WHERE reporte_id = ?", id)!!.length)

        val (lat, lon) = punto(0.0, 0.0)
        val feed = reportes.feed(tipo("ACOSO"), "SOCIAL", 7, lat, lon, 1_000.0, null, null)
        val tarjeta = feed.reportes.first { it.id == publicado.id }
        assertEquals("Ana Prueba", tarjeta.autor)
        assertNotEquals(lat, tarjeta.latitud)

        val anonimo = publicar(reporte(anonimo = true))
        assertEquals("Anónimo", reportes.feed(null, null, null, lat, lon, 1_000.0, null, null).reportes.first { it.id == anonimo.id }.autor)

        val rechazado = UUID.fromString(reportes.crear(ana, reporte(anonimo = true), null).first.id)
        assertEquals("VALIDACION", codigoDe { reportes.decidir(admin, rechazado, DecisionEntrada("RECHAZAR"), null) })
        reportes.decidir(admin, rechazado, DecisionEntrada("RECHAZAR", "No cumple las reglas de la comunidad"), null)
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE reporte_id = ? AND usuario_id = ? AND tipo = 'REPORTE_RECHAZADO'", rechazado, ana))
        assertEquals("TRANSICION_INVALIDA", codigoDe { reportes.decidir(admin, rechazado, DecisionEntrada("PUBLICAR"), null) })

        reportes.decidir(admin, id, DecisionEntrada("RETIRAR", "Ya se resolvió el problema"), null)
        assertTrue(reportes.feed(null, null, null, lat, lon, 1_000.0, null, null).reportes.none { it.id == publicado.id })
    }

    @Test
    fun `el feed se pagina con el cursor`() {
        repeat(3) { publicar(reporte(este = 500.0 + it)) }
        val (lat, lon) = punto(500.0, 0.0)
        val primera = reportes.feed(null, null, null, lat, lon, 100.0, null, null, limite = 2)
        assertEquals(2, primera.reportes.size)
        val segunda = reportes.feed(null, null, null, lat, lon, 100.0, null, primera.siguiente, limite = 2)
        assertTrue(segunda.reportes.isNotEmpty())
        assertTrue(segunda.reportes.none { s -> primera.reportes.any { it.id == s.id } })
    }

    @Test
    fun `el mapa de calor agrupa los reportes y la ruta segura evita la zona`() {
        // Grafo: O → M → D en línea recta (200 m) y un rodeo por el norte O → N1 → N2 → D (260 m).
        val base = Random.nextLong(1_000_000_000L, 9_000_000_000L)
        fun nodo(n: Long, este: Double, norte: Double) = punto(este, norte).let { NodoEntrada(base + n, it.first, it.second) }
        val nodos = listOf(nodo(1, 0.0, 0.0), nodo(2, 100.0, 0.0), nodo(3, 200.0, 0.0), nodo(4, 0.0, 30.0), nodo(5, 200.0, 30.0))
        val aristas = listOf(1L to 2L, 2L to 3L, 1L to 4L, 4L to 5L, 5L to 3L).map { (a, b) -> AristaEntrada(base + a, base + b) }
        assertEquals(5, rutas.cargarGrafo(admin, GrafoEntrada(nodos, aristas), null).aristas)

        // Seis asaltos (peso 3) sobre la línea recta: una zona ALTO.
        listOf(40.0, 50.0, 60.0, 140.0, 150.0, 160.0).forEachIndexed { i, x -> publicar(reporte(tipo = "ASALTO_ROBO", este = x, norte = if (i % 2 == 0) -3.0 else 3.0)) }
        val generada = mapaCalor.generar(admin.usuarioId)
        assertTrue(generada.tramos >= 2)

        val (lat, lon) = punto(100.0, 0.0)
        val zona = mapaCalor.capa(30, null).zonas.first { kotlin.math.abs(it.centroLatitud - lat) < 0.001 && kotlin.math.abs(it.centroLongitud - lon) < 0.001 }
        assertEquals("ALTO", zona.nivel)
        assertEquals(6, zona.numReportes)
        assertEquals("Asalto o robo", mapaCalor.zona(zona.id).motivos.single().tipo)
        assertTrue(mapaCalor.capa(30, tipo("ASALTO_ROBO")).zonas.any { it.numReportes == 6 })

        val (oLat, oLon) = punto(-5.0, 0.0)
        val (dLat, dLon) = punto(205.0, 0.0)
        val r = rutas.calcular(SolicitudRuta(Coordenada(oLat, oLon), Coordenada(dLat, dLon)))
        assertTrue(r.segura.distanciaM > r.masCorta.distanciaM, r.toString())
        assertTrue(r.segura.riesgoMedio < r.masCorta.riesgoMedio)
        assertTrue(r.advertencias.any { it.nivel == "ALTO" })
        assertEquals(generada.version, r.capaVersion)

        val (fLat, fLon) = punto(5_000.0, 5_000.0)
        assertEquals("FUERA_DE_AREA", codigoDe { rutas.calcular(SolicitudRuta(Coordenada(fLat, fLon), Coordenada(dLat, dLon))) })

        val caja = com.senderoseguro.server.comunidad.Geo.Caja(lat0 - 0.001, lon0 - 0.001, lat0 + 0.001, lon0 + 0.003)
        val grafo = rutas.grafo(caja)
        assertEquals(5, grafo.nodos.count { it.id in nodos.map { n -> n.id } })
        assertTrue(grafo.aristas.any { it.riesgo == 1.0 })
    }

    @Test
    fun `la cola sin senal acepta reportes y los paquetes de mapa reemplazan su version`() {
        val sync = Sincronizacion(dataSource, dispersion, mapOf(TipoSync.REPORTE to reportes.manejadorSync))
        val e = reporte()
        val elemento = ElementoSync(TipoSync.REPORTE, UUID.fromString(e.id), Instant.now().minusSeconds(60), buildJsonObject {
            put("id", e.id); put("tipoIncidenteId", e.tipoIncidenteId); put("descripcion", e.descripcion)
            put("latitud", e.latitud); put("longitud", e.longitud); put("creadoEn", e.creadoEn)
        })
        val contexto = ContextoSync(ana, UUID.fromString("00000000-0000-4000-8000-0000000000d1"))
        assertEquals("ACEPTADO", sync.procesar(contexto, listOf(elemento)).single().resultado)
        assertEquals("DUPLICADO", sync.procesar(contexto, listOf(elemento)).single().resultado)

        val mapas = MapasServicio(dataSource)
        val region = "Region prueba ${Random.nextInt(100_000, 999_999)}"
        fun paquete(v: String) = PaqueteEntrada(region, v, 19.49, -99.16, 19.51, -99.13, "mapa/${UUID.randomUUID()}.zip", 1024, "b".repeat(64))
        mapas.publicarPaquete(admin, paquete("1"), null)
        assertEquals("2", mapas.publicarPaquete(admin, paquete("2"), null).version)
        assertEquals(listOf("2"), mapas.paquetes().filter { it.region == region }.map { it.version })
        assertEquals("DUPLICADO", codigoDe { mapas.publicarPaquete(admin, paquete("2"), null) })
    }
}
