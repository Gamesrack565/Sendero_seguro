package com.escom.sendero_seguro.identidad.dominio

sealed class Resultado<out T> {
    data class Exito<out T>(val valor: T) : Resultado<T>()
    data class Error(val error: ErrorIdentidad) : Resultado<Nothing>()

    val esExito: Boolean get() = this is Exito

    fun valorONulo(): T? = (this as? Exito)?.valor

    inline fun <R> mapear(f: (T) -> R): Resultado<R> = when (this) {
        is Exito -> Exito(f(valor))
        is Error -> this
    }
}

fun <T> exito(valor: T): Resultado<T> = Resultado.Exito(valor)
fun falla(e: ErrorIdentidad): Resultado<Nothing> = Resultado.Error(e)

sealed class ErrorIdentidad(val mensaje: String) {
    /** Errores por campo, con la clave del campo del formulario (A-02, A-09, A-10…). */
    class Validacion(val errores: Map<String, String>) :
        ErrorIdentidad(errores.values.firstOrNull() ?: "Revisa los datos")

    object ConsentimientoRequerido :
        ErrorIdentidad("Debes aceptar el aviso de privacidad para continuar")

    class CredencialesInvalidas(val intentosRestantes: Int?) : ErrorIdentidad(
        if (intentosRestantes != null)
            "Usuario o contraseña incorrectos. Te quedan $intentosRestantes intentos antes de un bloqueo temporal."
        else "Usuario o contraseña incorrectos."
    )

    class CuentaBloqueada(val hastaMs: Long?) :
        ErrorIdentidad("Tu cuenta está bloqueada temporalmente por intentos fallidos.")

    object CuentaSuspendida : ErrorIdentidad("Tu cuenta está suspendida. Contacta al Administrador.")

    class PerfilNoPermitido(val rol: UsuarioRol) :
        ErrorIdentidad("Tu perfil es «$rol». Usa la consola web para acceder.")

    object SinPermiso : ErrorIdentidad("Tu perfil no puede realizar esta acción.")

    object SinSesion : ErrorIdentidad("Inicia sesión para continuar.")

    object SinConexion : ErrorIdentidad("Sin conexión a internet.")

    object PinNoConfigurado : ErrorIdentidad("No hay PIN de cancelación en este teléfono.")

    object CodigoInvalido : ErrorIdentidad("El código no existe o ya venció.")

    class Conflicto(mensaje: String) : ErrorIdentidad(mensaje)

    class NoEncontrado(mensaje: String = "No se encontró el registro.") : ErrorIdentidad(mensaje)

    class Servidor(mensaje: String = "Ocurrió un error en el servidor. Intenta de nuevo.") :
        ErrorIdentidad(mensaje)
}
