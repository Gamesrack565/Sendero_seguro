package com.escom.sendero_seguro.identidad.datos.local

import com.escom.sendero_seguro.core.db.ColaAlertaDao
import com.escom.sendero_seguro.core.db.ColaAlertaEntity
import com.escom.sendero_seguro.core.db.ConfigLocalDao
import com.escom.sendero_seguro.core.db.ConfigLocalEntity
import com.escom.sendero_seguro.core.db.ContactoLocalDao
import com.escom.sendero_seguro.core.db.ContactoLocalEntity
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.NotificacionCanal
import com.escom.sendero_seguro.identidad.dominio.SolicitudCancelacion
import com.escom.sendero_seguro.identidad.puertos.ClavesConfig
import com.escom.sendero_seguro.identidad.puertos.ColaCancelaciones
import com.escom.sendero_seguro.identidad.puertos.RepositorioConfigLocal
import com.escom.sendero_seguro.identidad.puertos.RepositorioContactosLocal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.Instant

class RepositorioConfigRoom(
    private val dao: ConfigLocalDao,
    private val reloj: () -> Long = System::currentTimeMillis
) : RepositorioConfigLocal {
    override suspend fun leer(clave: String): String? = dao.leer(clave)
    override suspend fun escribir(clave: String, valor: String) = dao.escribir(ConfigLocalEntity(clave, valor, reloj()))
    override suspend fun borrar(clave: String) = dao.borrar(clave)
    override fun observarTodo(): Flow<Map<String, String>> =
        dao.observarTodo().map { l -> l.associate { it.clave to it.valor } }
}

/**
 * contacto_local solo guarda lo necesario para el SMS sin conexión (ER-06). Lo que falta
 * por subir se marca con la lista CONTACTOS_PENDIENTES de config_local.
 */
class RepositorioContactosRoom(
    private val dao: ContactoLocalDao,
    private val configDao: ConfigLocalDao
) : RepositorioContactosLocal {

    override fun observar(): Flow<List<Contacto>> =
        combine(dao.observar(), configDao.observarTodo()) { contactos, config ->
            val pendientes = pendientesDe(config.firstOrNull { it.clave == ClavesConfig.CONTACTOS_PENDIENTES }?.valor)
            contactos.map { it.aDominio(it.id in pendientes) }
        }

    override suspend fun listar(): List<Contacto> {
        val pendientes = pendientesDe(configDao.leer(ClavesConfig.CONTACTOS_PENDIENTES))
        return dao.listar().map { it.aDominio(it.id in pendientes) }
    }

    override suspend fun guardar(contacto: Contacto) = dao.guardar(contacto.aEntidad())
    override suspend fun guardarTodos(contactos: List<Contacto>) = dao.guardarTodos(contactos.map { it.aEntidad() })
    override suspend fun reemplazarTodos(contactos: List<Contacto>) = dao.reemplazarTodos(contactos.map { it.aEntidad() })
    override suspend fun eliminar(id: String) = dao.eliminar(id)

    private fun pendientesDe(valor: String?): Set<String> =
        valor?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun ContactoLocalEntity.aDominio(pendiente: Boolean) = Contacto(
        id = id, nombre = nombre, telefono = telefono, prioridad = prioridad,
        canalPreferido = if (tieneApp == 1) NotificacionCanal.PUSH else NotificacionCanal.SMS,
        tieneApp = tieneApp == 1, pendienteSincronizar = pendiente
    )

    private fun Contacto.aEntidad() = ContactoLocalEntity(id, nombre, telefono, prioridad, if (tieneApp) 1 else 0)
}

/**
 * Deja la cancelación en cola_alerta para el despachador multicanal (Módulo 2/3).
 *
 * cola_alerta usa alerta_id como llave, así que hay una fila por alerta:
 *  - Si la alerta sigue en la cola y nunca salió por ningún canal (ultimo_canal nulo),
 *    basta con borrarla: el servidor nunca supo de ella.
 *  - En cualquier otro caso la fila se reemplaza por la CANCELACION.
 *
 * El paquete va en JSON. El despachador debe cifrarlo y firmarlo antes de mandarlo por malla o SMS (RNF9).
 */
class ColaCancelacionesRoom(
    private val dao: ColaAlertaDao,
    private val reloj: () -> Long = System::currentTimeMillis
) : ColaCancelaciones {

    override suspend fun encolar(solicitud: SolicitudCancelacion) {
        val existente = dao.buscar(solicitud.alertaId)
        if (existente != null && existente.tipoElemento == "ALERTA" && existente.ultimoCanal == null) {
            dao.eliminar(solicitud.alertaId)
            return
        }
        val ahora = reloj()
        dao.reemplazar(
            ColaAlertaEntity(
                alertaId = solicitud.alertaId,
                paquete = aJson(solicitud).toByteArray(Charsets.UTF_8),
                tipoElemento = "CANCELACION",
                ultimoCanal = null,
                reintentos = 0,
                creadoEn = ahora,
                expiraEn = ahora + VIGENCIA_MS
            )
        )
    }

    private fun aJson(s: SolicitudCancelacion): String = JSONObject()
        .put("alerta_id", s.alertaId)
        .put("usuario_id", s.usuarioId)
        .put("dispositivo_id", s.dispositivoId ?: JSONObject.NULL)
        .put("motivo", s.motivo?.name ?: JSONObject.NULL)
        .put("metodo", s.metodo.name)
        .put("exitoso", s.exitoso)
        .put("intentado_en", Instant.ofEpochMilli(s.intentadoEnMs).toString())
        .toString()

    companion object {
        /** Igual que una alerta en cola: 72 h (diccionario de cola_alerta). */
        const val VIGENCIA_MS = 72L * 60 * 60 * 1000
    }
}
