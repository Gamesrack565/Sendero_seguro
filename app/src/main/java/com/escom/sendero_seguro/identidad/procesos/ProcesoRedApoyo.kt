package com.escom.sendero_seguro.identidad.procesos

import com.escom.sendero_seguro.identidad.dominio.AvisoVinculacion
import com.escom.sendero_seguro.identidad.dominio.Circulo
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.Miembro
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.UsuarioRol
import com.escom.sendero_seguro.identidad.dominio.Validaciones
import com.escom.sendero_seguro.identidad.dominio.exito
import com.escom.sendero_seguro.identidad.dominio.falla
import com.escom.sendero_seguro.identidad.puertos.ClavesConfig
import com.escom.sendero_seguro.identidad.puertos.RepositorioConfigLocal
import com.escom.sendero_seguro.identidad.puertos.RepositorioContactosLocal
import com.escom.sendero_seguro.identidad.puertos.ServicioRemotoIdentidad
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * P2. Gestión de Red de Apoyo (Contactos Priorizados y Círculo Escolar).
 * Interfaces: A-09, A-10 y la salida X-01 (aviso de alta).
 *
 * Contactos: offline-first. Se guardan primero en contacto_local con un UUID generado en el teléfono
 * (el mismo que usa el servidor, así un reenvío no duplica) y luego se sincronizan.
 *
 * Composición: gestionarContactosPorPrioridad() + gestionarCirculoEscolar() + notificarVinculacionRedApoyo()
 */
class ProcesoRedApoyo(
    private val remoto: ServicioRemotoIdentidad,
    private val config: RepositorioConfigLocal,
    private val contactosLocal: RepositorioContactosLocal,
    private val generarId: () -> String = { UUID.randomUUID().toString() }
) {
    private val candado = Mutex()

    // ================================================================ Contactos (A-09)

    fun observarContactos(): Flow<List<Contacto>> = contactosLocal.observar()

    /** Contrato del E3: alta, edición, baja y reordenamiento de contactos por prioridad. */
    suspend fun gestionarContactosPorPrioridad(operacion: OperacionContacto): Resultado<List<Contacto>> =
        when (operacion) {
            is OperacionContacto.Agregar -> agregarContacto(operacion.nombre, operacion.telefono, operacion.prioridad)
                .mapear { contactosLocal.listar() }
            is OperacionContacto.Editar -> editarContacto(operacion.id, operacion.nombre, operacion.telefono)
            is OperacionContacto.Eliminar -> eliminarContacto(operacion.id)
            is OperacionContacto.Reordenar -> reordenarContactos(operacion.idsEnOrden)
        }

    /**
     * Agrega un contacto. Si [prioridad] es nula va al final; si no, se inserta en esa posición
     * y los demás se recorren (1..n sin repetir).
     */
    suspend fun agregarContacto(nombre: String, telefono: String, prioridad: Int? = null): Resultado<Contacto> =
        candado.withLock {
            if (config.leer(ClavesConfig.USUARIO_ID) == null) return@withLock falla(ErrorIdentidad.SinSesion)
            val actuales = contactosLocal.listar().sortedBy { it.prioridad }
            val e = Validaciones.validarContacto(nombre, telefono, actuales)
            if (e.isNotEmpty()) return@withLock falla(ErrorIdentidad.Validacion(e))

            val posicion = (prioridad ?: (actuales.size + 1)).coerceIn(1, actuales.size + 1)
            val nuevo = Contacto(
                id = generarId(),
                nombre = nombre.trim(),
                telefono = Validaciones.normalizarTelefono(telefono),
                prioridad = posicion,
                pendienteSincronizar = true
            )
            val lista = actuales.toMutableList().apply { add(posicion - 1, nuevo) }.renumerar()
            contactosLocal.guardarTodos(lista)
            marcarPendientes(lista.filter { it.pendienteSincronizar }.map { it.id })

            val guardado = subirContacto(lista.first { it.id == nuevo.id })
            if (!guardado.pendienteSincronizar) {
                if (posicion <= actuales.size) subirOrden() // hubo corrimiento de prioridades
                notificarVinculacionRedApoyo(AvisoVinculacion.ContactoAgregado(guardado.id))
            }
            exito(guardado)
        }

    suspend fun editarContacto(id: String, nombre: String, telefono: String): Resultado<List<Contacto>> =
        candado.withLock {
            val actuales = contactosLocal.listar()
            val c = actuales.firstOrNull { it.id == id }
                ?: return@withLock falla(ErrorIdentidad.NoEncontrado("El contacto ya no existe"))
            val e = Validaciones.validarContacto(nombre, telefono, actuales, idEditado = id)
            if (e.isNotEmpty()) return@withLock falla(ErrorIdentidad.Validacion(e))
            val editado = c.copy(
                nombre = nombre.trim(),
                telefono = Validaciones.normalizarTelefono(telefono),
                pendienteSincronizar = true
            )
            contactosLocal.guardar(editado)
            marcarPendientes(listOf(id))
            subirContacto(editado)
            exito(contactosLocal.listar())
        }

    suspend fun eliminarContacto(id: String): Resultado<List<Contacto>> = candado.withLock {
        val restantes = contactosLocal.listar().filter { it.id != id }.sortedBy { it.prioridad }.renumerar()
        contactosLocal.eliminar(id)
        contactosLocal.guardarTodos(restantes)
        quitarDeLista(ClavesConfig.CONTACTOS_PENDIENTES, id)
        when (remoto.eliminarContacto(id)) {
            is Resultado.Exito -> subirOrden()
            is Resultado.Error -> agregarALista(ClavesConfig.CONTACTOS_ELIMINADOS_PENDIENTES, id)
        }
        exito(contactosLocal.listar())
    }

    /** Arrastrar para cambiar la prioridad (A-09). [idsEnOrden] debe contener todos los contactos. */
    suspend fun reordenarContactos(idsEnOrden: List<String>): Resultado<List<Contacto>> = candado.withLock {
        val porId = contactosLocal.listar().associateBy { it.id }
        if (idsEnOrden.toSet() != porId.keys || idsEnOrden.size != porId.size) {
            return@withLock falla(
                ErrorIdentidad.Validacion(mapOf(Validaciones.PRIORIDAD to "La prioridad debe ser 1..n sin repetir"))
            )
        }
        val lista = idsEnOrden.map { porId.getValue(it) }.renumerar()
        contactosLocal.guardarTodos(lista)
        subirOrden()
        exito(contactosLocal.listar())
    }

    /** Descarga la lista del servidor y reemplaza la copia local (al iniciar sesión o al recuperar señal). */
    suspend fun sincronizarContactos(): Resultado<List<Contacto>> = candado.withLock {
        // 1. Subir lo que se hizo sin conexión.
        for (id in leerLista(ClavesConfig.CONTACTOS_ELIMINADOS_PENDIENTES)) {
            if (remoto.eliminarContacto(id) is Resultado.Exito) {
                quitarDeLista(ClavesConfig.CONTACTOS_ELIMINADOS_PENDIENTES, id)
            }
        }
        val locales = contactosLocal.listar().associateBy { it.id }
        for (id in leerLista(ClavesConfig.CONTACTOS_PENDIENTES)) {
            val c = locales[id]
            if (c == null) quitarDeLista(ClavesConfig.CONTACTOS_PENDIENTES, id) else subirContacto(c)
        }
        if (leerLista(ClavesConfig.CONTACTOS_PENDIENTES).isNotEmpty()) {
            return@withLock falla(ErrorIdentidad.SinConexion)
        }
        if (locales.isNotEmpty()) subirOrden()

        // 2. Bajar la versión del servidor (incluye tieneApp/canal calculados por el backend).
        when (val r = remoto.listarContactos()) {
            is Resultado.Error -> r
            is Resultado.Exito -> {
                val lista = r.valor.sortedBy { it.prioridad }.renumerar().map { it.copy(pendienteSincronizar = false) }
                contactosLocal.reemplazarTodos(lista)
                exito(lista)
            }
        }
    }

    // ================================================================ Círculo escolar (A-10)

    suspend fun cargarMiCirculo(): Resultado<Circulo?> {
        val r = remoto.miCirculo()
        if (r is Resultado.Exito) {
            val c = r.valor
            if (c == null) config.borrar(ClavesConfig.CIRCULO_ID) else config.escribir(ClavesConfig.CIRCULO_ID, c.id)
        }
        return r
    }

    /** Contrato del E3: crear o unirse a un círculo, regenerar código, dar de baja o retirarse. */
    suspend fun gestionarCirculoEscolar(operacion: OperacionCirculo): Resultado<Circulo?> = when (operacion) {
        is OperacionCirculo.Crear -> crearCirculo(operacion.nombreGrupo)
        is OperacionCirculo.Unirse -> unirseConCodigo(operacion.codigo)
        is OperacionCirculo.RegenerarCodigo -> regenerarCodigo()
        is OperacionCirculo.DarDeBaja -> darDeBajaIntegrante(operacion.usuarioId).mapear { cargarMiCirculo().valorONulo() }
        OperacionCirculo.Retirarme -> retirarme().mapear { null }
    }

    /** Solo el Coordinador crea el círculo de su grupo; el backend genera el código VIGENTE. */
    suspend fun crearCirculo(nombreGrupo: String): Resultado<Circulo> {
        if (!esCoordinador()) return falla(ErrorIdentidad.SinPermiso)
        Validaciones.validarNombreGrupo(nombreGrupo)?.let {
            return falla(ErrorIdentidad.Validacion(mapOf(Validaciones.NOMBRE_GRUPO to it)))
        }
        val r = remoto.crearCirculo(generarId(), nombreGrupo.trim())
        if (r is Resultado.Exito) config.escribir(ClavesConfig.CIRCULO_ID, r.valor.id)
        return r
    }

    suspend fun unirseConCodigo(codigo: String): Resultado<Circulo> {
        val normalizado = Validaciones.normalizarCodigoInvitacion(codigo)
            ?: return falla(ErrorIdentidad.Validacion(mapOf(Validaciones.CODIGO to "El código tiene el formato AAA-999")))
        val r = remoto.unirseACirculo(normalizado)
        if (r is Resultado.Exito) {
            config.escribir(ClavesConfig.CIRCULO_ID, r.valor.id)
            notificarVinculacionRedApoyo(AvisoVinculacion.NuevoIntegrante(r.valor.id))
        }
        return r
    }

    suspend fun regenerarCodigo(): Resultado<Circulo> {
        if (!esCoordinador()) return falla(ErrorIdentidad.SinPermiso)
        val id = config.leer(ClavesConfig.CIRCULO_ID) ?: return falla(ErrorIdentidad.NoEncontrado("Aún no tienes círculo"))
        return remoto.regenerarCodigo(id)
    }

    suspend fun cargarMiembros(): Resultado<List<Miembro>> {
        val id = config.leer(ClavesConfig.CIRCULO_ID) ?: return exito(emptyList())
        return remoto.miembros(id)
    }

    /** Dar de baja a un integrante (solo Coordinador): miembro_circulo.estado = DADA_DE_BAJA. */
    suspend fun darDeBajaIntegrante(usuarioId: String): Resultado<List<Miembro>> {
        if (!esCoordinador()) return falla(ErrorIdentidad.SinPermiso)
        val id = config.leer(ClavesConfig.CIRCULO_ID) ?: return falla(ErrorIdentidad.NoEncontrado("Aún no tienes círculo"))
        if (usuarioId == config.leer(ClavesConfig.USUARIO_ID)) {
            return falla(ErrorIdentidad.Conflicto("No puedes darte de baja de tu propio círculo"))
        }
        val r = remoto.darDeBaja(id, usuarioId)
        if (r is Resultado.Error) return r
        return remoto.miembros(id)
    }

    /** El integrante sale por su cuenta: miembro_circulo.estado = RETIRADA. */
    suspend fun retirarme(): Resultado<Unit> {
        val id = config.leer(ClavesConfig.CIRCULO_ID) ?: return exito(Unit)
        val r = remoto.retirarse(id)
        if (r is Resultado.Exito) config.borrar(ClavesConfig.CIRCULO_ID)
        return r
    }

    // ================================================================ Aviso (X-01)

    /**
     * Pide al backend que avise del alta: al contacto (SMS o push, tipo CONTACTO_AGREGADO) o
     * a los integrantes del círculo (NUEVO_INTEGRANTE). Si no hay señal no bloquea el alta.
     */
    suspend fun notificarVinculacionRedApoyo(aviso: AvisoVinculacion): Resultado<Unit> =
        remoto.notificarVinculacion(aviso)

    // ================================================================ internos

    private suspend fun esCoordinador(): Boolean =
        config.leer(ClavesConfig.PERFIL) == UsuarioRol.COORDINADOR.name

    /** Sube un contacto; si lo logra lo guarda con los datos del servidor y lo quita de pendientes. */
    private suspend fun subirContacto(c: Contacto): Contacto =
        when (val r = remoto.guardarContacto(c.copy(pendienteSincronizar = false))) {
            is Resultado.Exito -> {
                val confirmado = r.valor.copy(prioridad = c.prioridad, pendienteSincronizar = false)
                contactosLocal.guardar(confirmado)
                quitarDeLista(ClavesConfig.CONTACTOS_PENDIENTES, c.id)
                confirmado
            }
            is Resultado.Error -> c.copy(pendienteSincronizar = true).also { contactosLocal.guardar(it) }
        }

    private suspend fun subirOrden() {
        val ids = contactosLocal.listar().sortedBy { it.prioridad }.map { it.id }
        if (ids.isNotEmpty()) remoto.reordenarContactos(ids)
    }

    private suspend fun marcarPendientes(ids: List<String>) = ids.forEach { agregarALista(ClavesConfig.CONTACTOS_PENDIENTES, it) }

    private suspend fun leerLista(clave: String): List<String> =
        config.leer(clave)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    private suspend fun agregarALista(clave: String, id: String) {
        val l = leerLista(clave)
        if (id !in l) config.escribir(clave, (l + id).joinToString(","))
    }

    private suspend fun quitarDeLista(clave: String, id: String) {
        val l = leerLista(clave)
        if (id in l) {
            val resto = l - id
            if (resto.isEmpty()) config.borrar(clave) else config.escribir(clave, resto.joinToString(","))
        }
    }

    private fun List<Contacto>.renumerar(): List<Contacto> = mapIndexed { i, c ->
        if (c.prioridad == i + 1) c else c.copy(prioridad = i + 1)
    }
}

sealed class OperacionContacto {
    data class Agregar(val nombre: String, val telefono: String, val prioridad: Int? = null) : OperacionContacto()
    data class Editar(val id: String, val nombre: String, val telefono: String) : OperacionContacto()
    data class Eliminar(val id: String) : OperacionContacto()
    data class Reordenar(val idsEnOrden: List<String>) : OperacionContacto()
}

sealed class OperacionCirculo {
    data class Crear(val nombreGrupo: String) : OperacionCirculo()
    data class Unirse(val codigo: String) : OperacionCirculo()
    object RegenerarCodigo : OperacionCirculo()
    data class DarDeBaja(val usuarioId: String) : OperacionCirculo()
    object Retirarme : OperacionCirculo()
}
