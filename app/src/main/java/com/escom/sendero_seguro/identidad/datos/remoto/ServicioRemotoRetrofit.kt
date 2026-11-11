package com.escom.sendero_seguro.identidad.datos.remoto

import com.escom.sendero_seguro.identidad.dominio.AvisoPrivacidad
import com.escom.sendero_seguro.identidad.dominio.AvisoVinculacion
import com.escom.sendero_seguro.identidad.dominio.Circulo
import com.escom.sendero_seguro.identidad.dominio.CodigoEstado
import com.escom.sendero_seguro.identidad.dominio.ConsentimientoPendiente
import com.escom.sendero_seguro.identidad.dominio.Contacto
import com.escom.sendero_seguro.identidad.dominio.DatosRegistro
import com.escom.sendero_seguro.identidad.dominio.ErrorIdentidad
import com.escom.sendero_seguro.identidad.dominio.Escuela
import com.escom.sendero_seguro.identidad.dominio.InfoDispositivo
import com.escom.sendero_seguro.identidad.dominio.Institucion
import com.escom.sendero_seguro.identidad.dominio.Miembro
import com.escom.sendero_seguro.identidad.dominio.MiembroEstado
import com.escom.sendero_seguro.identidad.dominio.NotificacionCanal
import com.escom.sendero_seguro.identidad.dominio.RespuestaSesion
import com.escom.sendero_seguro.identidad.dominio.Resultado
import com.escom.sendero_seguro.identidad.dominio.SolicitudCancelacion
import com.escom.sendero_seguro.identidad.dominio.Usuario
import com.escom.sendero_seguro.identidad.dominio.UsuarioEstado
import com.escom.sendero_seguro.identidad.dominio.UsuarioRol
import com.escom.sendero_seguro.identidad.dominio.exito
import com.escom.sendero_seguro.identidad.dominio.falla
import com.escom.sendero_seguro.identidad.puertos.ServicioRemotoIdentidad
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant

class ServicioRemotoRetrofit(
    private val api: IdentidadApi,
    private val json: Json
) : ServicioRemotoIdentidad {

    // ------------------------------------------------------------------ P1

    override suspend fun avisoVigente() = llamar { api.avisoVigente().let { AvisoPrivacidad(it.id, it.version, it.contenidoHash) } }

    override suspend fun instituciones() = llamar { api.instituciones().map { Institucion(it.id, it.nombre, it.siglas) } }

    override suspend fun escuelas(institucionId: Int) =
        llamar { api.escuelas(institucionId).map { Escuela(it.id, it.institucionId, it.nombre) } }

    override suspend fun registrar(datos: DatosRegistro, consentimiento: ConsentimientoPendiente, dispositivo: InfoDispositivo) =
        llamar {
            api.registrar(
                RegistroDto(
                    nombre = datos.nombre,
                    telefonoMovil = datos.telefonoMovil,
                    identificadorInstitucional = datos.identificadorInstitucional,
                    tipoComunidad = datos.tipoComunidad.name,
                    escuelaId = requireNotNull(datos.escuelaId),
                    carreraAdscripcion = datos.carreraAdscripcion,
                    contrasena = datos.contrasena,
                    aceptaEntrenamientoIa = consentimiento.aceptaEntrenamientoIa,
                    consentimiento = ConsentimientoDto(consentimiento.avisoId, iso(consentimiento.aceptadoEnMs)),
                    dispositivo = dispositivo.aDto()
                )
            ).aDominio()
        }

    override suspend fun iniciarSesion(usuario: String, contrasena: String, dispositivo: InfoDispositivo) =
        llamar { api.iniciarSesion(LoginDto(usuario, contrasena, dispositivo.aDto())).aDominio() }

    override suspend fun cerrarSesion() = llamar { api.cerrarSesion() }

    override suspend fun perfil() = llamar { api.perfil().aDominio() }

    override suspend fun actualizarPerfil(
        telefonoMovil: String?,
        identificadorInstitucional: String?,
        carreraAdscripcion: String?,
        aceptaEntrenamientoIa: Boolean?
    ) = llamar {
        api.actualizarPerfil(PerfilCambioDto(telefonoMovil, identificadorInstitucional, carreraAdscripcion, aceptaEntrenamientoIa))
            .aDominio()
    }

    override suspend fun solicitarBaja(contrasena: String) = llamar { api.solicitarBaja(BajaDto(contrasena)) }

    // ------------------------------------------------------------------ P2

    override suspend fun listarContactos() = llamar { api.listarContactos().map { it.aDominio() } }

    override suspend fun guardarContacto(contacto: Contacto) = llamar {
        api.guardarContacto(contacto.id, ContactoDto(contacto.id, contacto.nombre, contacto.telefono, contacto.prioridad))
            .aDominio()
    }

    override suspend fun eliminarContacto(id: String) = llamar { api.eliminarContacto(id) }

    override suspend fun reordenarContactos(idsEnOrden: List<String>) =
        llamar { api.reordenarContactos(OrdenDto(idsEnOrden)).map { it.aDominio() } }

    override suspend fun miCirculo() = llamar { api.miCirculo().circulo?.aDominio() }

    override suspend fun crearCirculo(id: String, nombreGrupo: String) =
        llamar { api.crearCirculo(CrearCirculoDto(id, nombreGrupo)).aDominio() }

    override suspend fun unirseACirculo(codigo: String) = llamar { api.unirse(UnirseDto(codigo)).aDominio() }

    override suspend fun regenerarCodigo(circuloId: String) = llamar { api.regenerarCodigo(circuloId).aDominio() }

    override suspend fun miembros(circuloId: String) = llamar {
        api.miembros(circuloId).map {
            Miembro(it.usuarioId, it.nombre, enumO(it.estado, MiembroEstado.ACTIVA))
        }
    }

    override suspend fun darDeBaja(circuloId: String, usuarioId: String) = llamar { api.darDeBaja(circuloId, usuarioId) }

    override suspend fun retirarse(circuloId: String) = llamar { api.retirarse(circuloId) }

    override suspend fun notificarVinculacion(aviso: AvisoVinculacion) = llamar {
        api.notificarVinculacion(
            when (aviso) {
                is AvisoVinculacion.ContactoAgregado -> AvisoVinculacionDto("CONTACTO_AGREGADO", contactoId = aviso.contactoId)
                is AvisoVinculacion.NuevoIntegrante -> AvisoVinculacionDto("NUEVO_INTEGRANTE", circuloId = aviso.circuloId)
            }
        )
    }

    // ------------------------------------------------------------------ P8

    override suspend fun registrarCancelacion(solicitud: SolicitudCancelacion) = llamar {
        api.registrarCancelacion(
            solicitud.alertaId,
            CancelacionDto(
                dispositivoId = solicitud.dispositivoId,
                motivo = solicitud.motivo?.name,
                metodo = solicitud.metodo.name,
                exitoso = solicitud.exitoso,
                intentadoEn = iso(solicitud.intentadoEnMs)
            )
        )
    }

    // ------------------------------------------------------------------ errores HTTP → dominio

    private suspend fun <T> llamar(bloque: suspend () -> T): Resultado<T> = try {
        exito(bloque())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        falla(mapearHttp(e))
    } catch (e: IOException) {
        falla(ErrorIdentidad.SinConexion)
    } catch (e: Exception) {
        falla(ErrorIdentidad.Servidor())
    }

    private fun mapearHttp(e: HttpException): ErrorIdentidad {
        val cuerpo = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
        val dto = cuerpo?.let { runCatching { json.decodeFromString(ErrorDto.serializer(), it) }.getOrNull() } ?: ErrorDto()
        val detalle = dto.detalle ?: DetalleErrorDto()
        return when {
            dto.codigo == "VALIDACION" || e.code() == 400 || e.code() == 422 ->
                ErrorIdentidad.Validacion(detalle.errores.ifEmpty { mapOf("general" to dto.mensaje.ifEmpty { "Revisa los datos" }) })
            dto.codigo == "TOKEN_INVALIDO" || dto.codigo == "NO_AUTENTICADO" -> ErrorIdentidad.SinSesion
            dto.codigo == "CREDENCIALES_INVALIDAS" || e.code() == 401 -> ErrorIdentidad.CredencialesInvalidas(detalle.intentosRestantes)
            dto.codigo == "CUENTA_BLOQUEADA" || e.code() == 423 ->
                ErrorIdentidad.CuentaBloqueada(detalle.bloqueadaHasta?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() })
            dto.codigo == "CUENTA_SUSPENDIDA" -> ErrorIdentidad.CuentaSuspendida
            dto.codigo == "PERFIL_NO_PERMITIDO" -> ErrorIdentidad.PerfilNoPermitido(enumO(detalle.rol, UsuarioRol.ADMINISTRADOR))
            dto.codigo == "CODIGO_INVALIDO" || dto.codigo == "CODIGO_VENCIDO" || e.code() == 410 -> ErrorIdentidad.CodigoInvalido
            e.code() == 403 -> ErrorIdentidad.SinPermiso
            e.code() == 404 -> ErrorIdentidad.NoEncontrado(dto.mensaje.ifEmpty { "No se encontró el registro." })
            e.code() == 409 -> ErrorIdentidad.Conflicto(dto.mensaje.ifEmpty { "El dato ya está registrado." })
            else -> ErrorIdentidad.Servidor()
        }
    }

    // ------------------------------------------------------------------ conversiones

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun ms(iso: String?) = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private inline fun <reified E : Enum<E>> enumO(valor: String?, defecto: E): E =
        valor?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: defecto

    private fun InfoDispositivo.aDto() = DispositivoDto(id, modelo, androidApi, soportaWifiAware)

    private fun UsuarioDto.aDominio() = Usuario(
        id = id, nombre = nombre, telefonoMovil = telefonoMovil, identificadorInstitucional = identificadorInstitucional,
        escuelaId = escuelaId, carreraAdscripcion = carreraAdscripcion,
        rol = enumO(rol, UsuarioRol.INTEGRANTE), estado = enumO(estado, UsuarioEstado.ACTIVA),
        aceptaEntrenamientoIa = aceptaEntrenamientoIa
    )

    private fun SesionDto.aDominio() = RespuestaSesion(usuario.aDominio(), token, ms(expiraEn) ?: 0L)

    private fun ContactoDto.aDominio() = Contacto(
        id = id, nombre = nombre, telefono = telefono, prioridad = prioridad,
        canalPreferido = enumO(canalPreferido, NotificacionCanal.SMS),
        tieneApp = usuarioVinculadoId != null
    )

    private fun CirculoDto.aDominio() = Circulo(
        id = id, nombreGrupo = nombreGrupo, escuelaId = escuelaId, coordinadorId = coordinadorId,
        codigoInvitacion = codigoInvitacion, estadoCodigo = enumO(estadoCodigo, CodigoEstado.VIGENTE),
        codigoVenceEnMs = ms(codigoVenceEn)
    )
}
