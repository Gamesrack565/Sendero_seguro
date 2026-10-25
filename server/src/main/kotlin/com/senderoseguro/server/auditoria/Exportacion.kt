package com.senderoseguro.server.auditoria

import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.transaccion
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

enum class FormatoExportacion(val extension: String, val tipoContenido: String) {
    CSV("csv", "text/csv; charset=utf-8"),
    PDF("pdf", "application/pdf"),
}

class Archivo(val nombre: String, val formato: FormatoExportacion, val bytes: ByteArray)

/**
 * Exportación de las estadísticas (P13): CSV (X-02) y PDF tamaño carta (X-03), con los mismos números que
 * `GET /estadisticas/alertas`. Cada exportación queda en la bitácora como EXPORTAR_ESTADISTICAS; si no se puede
 * registrar, no se entrega el archivo.
 */
class Exportacion(private val dataSource: DataSource, private val estadisticas: Estadisticas) {

    fun exportar(admin: UsuarioToken, f: FiltrosEstadisticas, formato: FormatoExportacion, ip: String?): Archivo {
        val e = estadisticas.alertas(f)
        val filtros = describirFiltros(f)
        val bytes = when (formato) {
            FormatoExportacion.CSV -> csv(e, filtros)
            FormatoExportacion.PDF -> pdf(e, filtros, Instant.now())
        }
        dataSource.transaccion { conn ->
            Bitacora.registrar(
                conn, "EXPORTAR_ESTADISTICAS", admin.usuarioId, entidad = "estadisticas",
                detalle = buildJsonObject {
                    put("formato", formato.extension)
                    put("desde", f.desde.toString())
                    put("hasta", f.hasta.toString())
                    f.tipoIncidenteId?.let { put("tipoIncidenteId", it) }
                    f.zonaId?.let { put("zonaId", it) }
                    put("totalAlertas", e.totalAlertas)
                },
                ip = ip,
            )
        }
        return Archivo("estadisticas-${f.desde}-a-${f.hasta}.${formato.extension}", formato, bytes)
    }

    /** Filtros con nombre, para el encabezado de los archivos: ("Tipo", "Asalto o robo"), ("Zona", "ESCOM"). */
    private fun describirFiltros(f: FiltrosEstadisticas): List<Pair<String, String>> = dataSource.connection.use { conn ->
        listOfNotNull(
            f.tipoIncidenteId?.let { id ->
                "Tipo" to (conn.consultarUno(
                    "SELECT nombre FROM tipo_incidente_version WHERE tipo_incidente_id = ? ORDER BY version_catalogo_id DESC LIMIT 1", id,
                ) { it.getString(1) } ?: "Tipo $id")
            },
            f.zonaId?.let { id -> "Zona" to (conn.consultarUno("SELECT nombre FROM zona_geografica WHERE id = ?", id) { it.getString(1) } ?: "Zona $id") },
        )
    }

    companion object {
        /**
         * Una fila por dato: `seccion,concepto,valor,mediana_acuse_seg`. Lleva BOM para que Excel lea bien los acentos,
         * y punto decimal.
         */
        fun csv(e: EstadisticasAlertas, filtros: List<Pair<String, String>> = emptyList()): ByteArray {
            val filas = buildList {
                add(listOf("seccion", "concepto", "valor", "mediana_acuse_seg"))
                add(listOf("periodo", "desde", e.periodo.desde, ""))
                add(listOf("periodo", "hasta", e.periodo.hasta, ""))
                filtros.forEach { (k, v) -> add(listOf("filtro", k, v, "")) }
                add(listOf("resumen", "totalAlertas", e.totalAlertas.toString(), ""))
                add(listOf("resumen", "medianaAcuseSeg", e.medianaAcuseSeg?.toString().orEmpty(), ""))
                add(listOf("resumen", "pctCumpleMetaAcuse", e.pctCumpleMetaAcuse?.toString().orEmpty(), ""))
                add(listOf("resumen", "pctEscaladas", e.pctEscaladas.toString(), ""))
                add(listOf("resumen", "pctCanceladas", e.pctCanceladas.toString(), ""))
                e.porCanal.forEach { (k, n) -> add(listOf("porCanal", k, n.toString(), "")) }
                e.porEstado.forEach { (k, n) -> add(listOf("porEstado", k, n.toString(), "")) }
                e.porTipo.forEach { add(listOf("porTipo", it.nombre, it.total.toString(), it.medianaAcuseSeg?.toString().orEmpty())) }
                e.porSemana.forEach { add(listOf("porSemana", it.semana, it.total.toString(), "")) }
            }
            val texto = filas.joinToString("\r\n", postfix = "\r\n") { fila -> fila.joinToString(",", transform = ::celda) }
            return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + texto.toByteArray(Charsets.UTF_8)
        }

        /** Comillas cuando hace falta, y un apóstrofo delante de =, +, - o @ para que Excel no lo tome como fórmula. */
        private fun celda(valor: String): String {
            val seguro = if (valor.firstOrNull() in setOf('=', '+', '-', '@') && valor.toDoubleOrNull() == null) "'$valor" else valor
            return if (seguro.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + seguro.replace("\"", "\"\"") + "\"" else seguro
        }

        fun pdf(e: EstadisticasAlertas, filtros: List<Pair<String, String>>, generadoEn: Instant): ByteArray =
            PDDocument().use { doc ->
                Hoja(doc).apply {
                    titulo("Sendero Seguro · Estadísticas de alertas")
                    texto("Periodo: ${e.periodo.desde} a ${e.periodo.hasta} (hora de la Ciudad de México)")
                    filtros.forEach { (k, v) -> texto("$k: $v") }
                    texto("Generado: " + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(Estadisticas.ZONA).format(generadoEn), gris = true)

                    seccion("Resumen")
                    fila("Total de alertas", e.totalAlertas.toString())
                    fila("Mediana de activación a acuse", e.medianaAcuseSeg?.let { "$it s" } ?: "sin datos")
                    fila("Acusadas dentro de la meta", e.pctCumpleMetaAcuse?.let { "$it %" } ?: "sin datos")
                    fila("Escaladas", "${e.pctEscaladas} %")
                    fila("Canceladas", "${e.pctCanceladas} %")

                    seccion("Por canal de recepción")
                    if (e.porCanal.isEmpty()) texto("Sin alertas", gris = true)
                    e.porCanal.forEach { (k, n) -> fila(k, n.toString()) }

                    seccion("Por estado")
                    if (e.porEstado.isEmpty()) texto("Sin alertas", gris = true)
                    e.porEstado.forEach { (k, n) -> fila(k, n.toString()) }

                    seccion("Por tipo de incidente")
                    if (e.porTipo.isEmpty()) texto("Sin alertas", gris = true)
                    e.porTipo.forEach { fila(it.nombre, it.total.toString(), it.medianaAcuseSeg?.let { m -> "mediana $m s" }.orEmpty()) }

                    seccion("Por semana")
                    val maximo = e.porSemana.maxOfOrNull { it.total } ?: 0
                    e.porSemana.forEach { barra(it.semana, it.total, maximo) }
                    cerrar()
                }
                ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            }
    }
}

/** Escribe hacia abajo en hojas carta y abre otra cuando se acaba el espacio. */
private class Hoja(private val doc: PDDocument) {
    private val normal = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val negrita = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private val margen = 54f
    private val ancho = PDRectangle.LETTER.width - 2 * margen
    private lateinit var lienzo: PDPageContentStream
    private var y = 0f

    init { nuevaPagina() }

    private fun nuevaPagina() {
        if (::lienzo.isInitialized) lienzo.close()
        val pagina = PDPage(PDRectangle.LETTER)
        doc.addPage(pagina)
        lienzo = PDPageContentStream(doc, pagina)
        y = PDRectangle.LETTER.height - margen
    }

    private fun espacio(alto: Float) { if (y - alto < margen) nuevaPagina() }

    /** Las fuentes estándar del PDF solo traen Latin-1; lo demás (emojis, otros alfabetos) sale como "?". */
    private fun limpio(t: String) = t.map { c -> if (runCatching { normal.encode(c.toString()) }.isSuccess) c else '?' }.joinToString("")

    private fun escribir(t: String, x: Float, fuente: PDType1Font, tam: Float, gris: Boolean = false) {
        lienzo.beginText()
        lienzo.setFont(fuente, tam)
        lienzo.setNonStrokingColor(if (gris) 0.45f else 0f)
        lienzo.newLineAtOffset(x, y)
        lienzo.showText(limpio(t))
        lienzo.endText()
    }

    private fun derecha(t: String, xFin: Float, fuente: PDType1Font, tam: Float) =
        escribir(t, xFin - fuente.getStringWidth(limpio(t)) / 1000 * tam, fuente, tam)

    fun titulo(t: String) { espacio(24f); escribir(t, margen, negrita, 16f); y -= 24f }
    fun texto(t: String, gris: Boolean = false) { espacio(15f); escribir(t, margen, normal, 10f, gris); y -= 15f }
    fun seccion(t: String) { y -= 10f; espacio(36f); escribir(t, margen, negrita, 12f); y -= 18f }

    fun fila(concepto: String, valor: String, nota: String = "") {
        espacio(15f)
        escribir(concepto, margen + 8f, normal, 10f)
        derecha(valor, margen + ancho * 0.7f, negrita, 10f)
        if (nota.isNotEmpty()) escribir(nota, margen + ancho * 0.74f, normal, 9f, gris = true)
        y -= 15f
    }

    fun barra(etiqueta: String, total: Int, maximo: Int) {
        espacio(15f)
        escribir(etiqueta, margen + 8f, normal, 10f)
        val inicio = margen + 80f
        val largo = if (maximo == 0) 0f else (ancho - 130f) * total / maximo
        if (largo > 0f) {
            lienzo.setNonStrokingColor(0.25f, 0.45f, 0.75f)
            lienzo.addRect(inicio, y - 1f, largo, 9f)
            lienzo.fill()
        }
        escribir(total.toString(), inicio + largo + 6f, normal, 10f)
        y -= 15f
    }

    fun cerrar() = lienzo.close()
}
