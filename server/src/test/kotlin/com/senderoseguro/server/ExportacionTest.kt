package com.senderoseguro.server

import com.senderoseguro.server.auditoria.EstadisticaSemana
import com.senderoseguro.server.auditoria.EstadisticaTipo
import com.senderoseguro.server.auditoria.Estadisticas
import com.senderoseguro.server.auditoria.EstadisticasAlertas
import com.senderoseguro.server.auditoria.Exportacion
import com.senderoseguro.server.auditoria.Periodo
import com.senderoseguro.server.auditoria.rutasEstadisticas
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.configurarErrores
import com.senderoseguro.server.plataforma.configurarSeguridad
import com.senderoseguro.server.plataforma.configurarSerializacion
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.text.PDFTextStripper
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Exportación de las estadísticas en CSV y PDF (P13). */
class ExportacionTest : PruebaConBaseDatos() {

    private fun ejemplo(tipos: List<EstadisticaTipo> = listOf(EstadisticaTipo(3, "Asalto, robo \"a mano\"", 20, 95))) = EstadisticasAlertas(
        periodo = Periodo("2026-10-19", "2026-10-25"),
        totalAlertas = 21, medianaAcuseSeg = 95, pctCumpleMetaAcuse = 66.7, pctEscaladas = 4.8, pctCanceladas = 0.0,
        porCanal = mapOf("INTERNET" to 20, "SMS" to 1), porEstado = mapOf("CERRADA" to 21),
        porTipo = tipos + EstadisticaTipo(9, "=HYPERLINK(\"x\")", 1, null),
        porSemana = listOf(EstadisticaSemana("2026-W43", 21)),
    )

    @Test
    fun `el CSV trae una fila por dato, con acentos para Excel y sin formulas`() {
        val bytes = Exportacion.csv(ejemplo(), listOf("Zona" to "ESCOM"))
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF }, "BOM UTF-8")
        val lineas = String(bytes, Charsets.UTF_8).removePrefix("﻿").trimEnd().lines()
        assertEquals("seccion,concepto,valor,mediana_acuse_seg", lineas[0])
        assertTrue("filtro,Zona,ESCOM," in lineas)
        assertTrue("resumen,totalAlertas,21," in lineas)
        assertTrue("resumen,pctCumpleMetaAcuse,66.7," in lineas)
        assertTrue("porCanal,SMS,1," in lineas)
        assertTrue("porTipo,\"Asalto, robo \"\"a mano\"\"\",20,95" in lineas, "Comas y comillas escapadas")
        assertTrue("porTipo,\"'=HYPERLINK(\"\"x\"\")\",1," in lineas, "Una fórmula se escribe como texto")
        assertTrue("porSemana,2026-W43,21," in lineas)
    }

    @Test
    fun `el PDF es tamano carta, se lee y abre otra hoja si no cabe`() {
        val corto = Exportacion.pdf(ejemplo(), listOf("Zona" to "ESCOM"), Instant.parse("2026-10-25T22:00:00Z"))
        Loader.loadPDF(corto).use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertEquals(PDRectangle.LETTER.width, doc.getPage(0).mediaBox.width)
            assertEquals(PDRectangle.LETTER.height, doc.getPage(0).mediaBox.height)
            val texto = PDFTextStripper().getText(doc)
            assertTrue("Estadísticas de alertas" in texto)
            assertTrue("Periodo: 2026-10-19 a 2026-10-25" in texto)
            assertTrue("Generado: 2026-10-25 16:00" in texto, "Hora de la Ciudad de México")
            assertTrue("Zona: ESCOM" in texto && "Total de alertas" in texto && "66.7 %" in texto && "2026-W43" in texto)
        }
        val muchos = List(70) { EstadisticaTipo(it + 100, "Tipo $it 🚨", 1, null) }
        Loader.loadPDF(Exportacion.pdf(ejemplo(muchos), emptyList(), Instant.now())).use { doc ->
            assertTrue(doc.numberOfPages >= 2)
            assertTrue("Tipo 69 ?" in PDFTextStripper().getText(doc), "Lo que la fuente no tiene sale como ?")
        }
    }

    @Test
    fun `por la ruta, solo el Administrador exporta y cada exportacion queda en la bitacora`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "jwt.secreto" to "secreto-exportar", "jwt.emisor" to "sendero-seguro", "jwt.audiencia" to "sendero-seguro-api",
            )
        }
        val estadisticas = Estadisticas(dataSource)
        application {
            configurarSerializacion()
            configurarErrores()
            configurarSeguridad()
            routing { route("/api/v1") { rutasEstadisticas(estadisticas, Exportacion(dataSource, estadisticas)) } }
        }
        val tokens = EmisorTokens("secreto-exportar", "sendero-seguro", "sendero-seguro-api")
        suspend fun pedir(consulta: String, cuenta: String = "admin", rol: Rol = Rol.ADMINISTRADOR): HttpResponse {
            val jwt = tokens.emitir(UsuarioToken(AlertasPrueba.CUENTAS.getValue(cuenta), rol, null, null), 1.hours).first
            return client.get("/api/v1/estadisticas/exportar$consulta") { header("Authorization", "Bearer $jwt") }
        }
        // La bitácora no deja borrar, y en una base que se reutiliza ya puede haber exportaciones de corridas
        // anteriores con el mismo periodo: se cuentan las nuevas contra las que había al empezar.
        val dia = LocalDate.of(2001, 1, 1).plusDays(Random.nextLong(3000))
        fun total(formato: String) = numero(
            "SELECT count(*) FROM bitacora WHERE accion = 'EXPORTAR_ESTADISTICAS' AND detalle->>'desde' = ? AND detalle->>'formato' = ?",
            dia.toString(), formato,
        )
        val antes = mapOf("csv" to total("csv"), "pdf" to total("pdf"))
        fun registros(formato: String) = total(formato) - antes.getValue(formato)

        val csv = pedir("?formato=csv&desde=$dia&hasta=$dia")
        assertEquals(HttpStatusCode.OK, csv.status)
        assertTrue(csv.headers[HttpHeaders.ContentType]!!.startsWith("text/csv"))
        assertEquals("attachment; filename=estadisticas-$dia-a-$dia.csv", csv.headers[HttpHeaders.ContentDisposition])
        assertTrue(String(csv.readRawBytes(), Charsets.UTF_8).contains("resumen,totalAlertas,0,"))
        assertEquals(1, registros("csv"))

        val pdf = pedir("?formato=PDF&desde=$dia&hasta=$dia")
        assertEquals(HttpStatusCode.OK, pdf.status)
        assertEquals("application/pdf", pdf.headers[HttpHeaders.ContentType])
        assertEquals("%PDF", String(pdf.readRawBytes().take(4).toByteArray()))
        assertEquals(1, registros("pdf"))

        assertEquals(HttpStatusCode.Forbidden, pedir("?formato=csv&desde=$dia&hasta=$dia", "caseta", Rol.OPERADOR).status)
        assertEquals(HttpStatusCode.BadRequest, pedir("?formato=xls&desde=$dia&hasta=$dia").status)
        assertEquals(HttpStatusCode.BadRequest, pedir("?desde=$dia&hasta=$dia").status)
        assertEquals(HttpStatusCode.BadRequest, pedir("?formato=csv&desde=$dia").status)
        assertEquals(1, registros("csv"), "Lo rechazado no se registra")
    }
}
