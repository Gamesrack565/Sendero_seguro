package com.senderoseguro.server

import com.senderoseguro.server.catalogo.CatalogoServicio
import com.senderoseguro.server.catalogo.PasoEntrada
import com.senderoseguro.server.catalogo.ReglaEntrada
import com.senderoseguro.server.catalogo.TipoEntrada
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Catálogo de incidentes y reglas (CU-08, CU-09, A-08, E-09). */
class CatalogoTest : PruebaConBaseDatos() {

    private val admin = UsuarioToken(AlertasPrueba.CUENTAS.getValue("admin"), Rol.ADMINISTRADOR, null, null)
    private val catalogo by lazy { CatalogoServicio(dataSource) }
    private fun codigoDe(bloque: () -> Unit): String = assertFailsWith<ApiException> { bloque() }.codigo
    private fun corporacion(canal: String) = numero("SELECT id FROM corporacion WHERE canal_codigo = ?", canal)

    /** Si una prueba deja una versión en edición, se descarta para la siguiente. */
    @AfterTest
    fun descartarEdicion() {
        val abierta = catalogo.versiones().firstOrNull { it.estado in setOf("BORRADOR", "EN_VALIDACION") } ?: return
        catalogo.descartar(admin, abierta.numero, null)
    }

    @Test
    fun `el borrador copia la version vigente y solo puede haber uno`() {
        val vigente = catalogo.vigente(null)
        val borrador = catalogo.crearBorrador(admin, null)
        assertEquals("BORRADOR", borrador.version.estado)
        assertEquals(vigente.numero + 1, borrador.version.numero)
        assertEquals(numero("SELECT count(*) FROM tipo_incidente_version WHERE version_catalogo_id = ?", vigente.versionCatalogoId), borrador.tipos.size)
        assertTrue(borrador.tipos.first { it.tipo.codigo == "GENERICA" }.regla!!.pasos.isNotEmpty())
        assertTrue(borrador.errores.isEmpty(), borrador.errores.toString())
        assertEquals("EDICION_ABIERTA", codigoDe { catalogo.crearBorrador(admin, null) })

        catalogo.descartar(admin, borrador.version.numero, null)
        assertEquals(vigente.numero, catalogo.vigente(null).numero)
    }

    @Test
    fun `un tipo nuevo de alerta sin regla no se publica y con regla si`() {
        val numero = catalogo.crearBorrador(admin, null).version.numero
        val codigo = "PRUEBA_${Random.nextInt(100_000, 999_999)}"
        val conTipo = catalogo.guardarTipo(admin, numero, codigo,
            TipoEntrada("Prueba ${codigo.takeLast(6)}", "SOCIAL", 2, "ALERTA", listOf("ubicacion")), null)
        assertTrue(conTipo.errores.any { it.codigo == "SIN_REGLA" && it.tipo == codigo })

        assertEquals("BORRADOR", catalogo.validar(admin, numero, null).version.estado)
        assertEquals("CATALOGO_INVALIDO", codigoDe { catalogo.publicar(admin, numero, null) })

        val seg = corporacion("SEG_ESCOLAR")
        val c5 = corporacion("SSC_C5")
        assertEquals("VALIDACION", codigoDe {
            catalogo.guardarRegla(admin, numero, codigo, ReglaEntrada(seg, 10, listOf(PasoEntrada("COORDINADOR_CIRCULO"))), null)
        })
        assertEquals("VALIDACION", codigoDe {
            catalogo.guardarRegla(admin, numero, codigo, ReglaEntrada(seg, 120, listOf(PasoEntrada("CORPORACION", seg))), null)
        })
        assertEquals("VALIDACION", codigoDe {
            catalogo.guardarRegla(admin, numero, codigo, ReglaEntrada(seg, 120, listOf(PasoEntrada("OPERADOR", operadorId = AlertasPrueba.ANA.toString()))), null)
        })
        val conRegla = catalogo.guardarRegla(admin, numero, codigo,
            ReglaEntrada(seg, 120, listOf(PasoEntrada("CORPORACION", c5), PasoEntrada("COORDINADOR_CIRCULO"))), null)
        assertTrue(conRegla.errores.isEmpty(), conRegla.errores.toString())
        assertEquals(listOf("CORPORACION", "COORDINADOR_CIRCULO"), conRegla.tipos.first { it.tipo.codigo == codigo }.regla!!.pasos.map { it.destinoTipo })

        assertEquals("EN_VALIDACION", catalogo.validar(admin, numero, null).version.estado)
        val publicada = catalogo.publicar(admin, numero, null)
        assertEquals("PUBLICADA", publicada.version.estado)
        assertEquals(1, numero("SELECT count(*) FROM version_catalogo WHERE estado = 'PUBLICADA'"))
        assertTrue(catalogo.vigente("ALERTA").tipos.any { it.codigo == codigo })
        assertEquals("VERSION_CERRADA", codigoDe { catalogo.guardarTipo(admin, numero, codigo, TipoEntrada("Otro nombre", "SOCIAL", 2, "ALERTA"), null) })
    }

    @Test
    fun `la generica no se desactiva y un tipo desactivado deja de verse en la app`() {
        val numero = catalogo.crearBorrador(admin, null).version.numero
        assertEquals("VALIDACION", codigoDe { catalogo.guardarTipo(admin, numero, "GENERICA", TipoEntrada("Genérica", null, 2, "ALERTA", activo = false), null) })
        assertEquals("VALIDACION", codigoDe { catalogo.guardarTipo(admin, numero, "ACOSO", TipoEntrada("Acoso", null, 2, "AMBOS"), null) })
        assertEquals("VALIDACION", codigoDe { catalogo.guardarTipo(admin, numero, "ACOSO", TipoEntrada("Acoso", "SOCIAL", 9, "AMBOS"), null) })
        val nombreAsalto = catalogo.detalle(numero).tipos.first { it.tipo.codigo == "ASALTO_ROBO" }.tipo.nombre
        assertEquals("DUPLICADO", codigoDe { catalogo.guardarTipo(admin, numero, "ACOSO", TipoEntrada(nombreAsalto, "SOCIAL", 2, "AMBOS"), null) })

        val d = catalogo.guardarTipo(admin, numero, "ACOSO", TipoEntrada("Acoso", "SOCIAL", 2, "AMBOS", listOf("descripcion"), activo = false), null)
        assertEquals(false, d.tipos.first { it.tipo.codigo == "ACOSO" }.tipo.activo)
        assertTrue(d.errores.none { it.tipo == "ACOSO" })
    }
}
