package com.escom.sendero_seguro.identidad.dominio

/**
 * Reglas de validación de las pantallas A-02, A-03, A-08, A-09, A-10, A-11 y A-12 (E3, Diseño de E/S).
 * Son funciones puras: se prueban en JVM y el backend debe aplicar las mismas.
 */
object Validaciones {

    // Claves de campo que usa la interfaz para mostrar el error junto al control.
    const val NOMBRE = "nombre"
    const val TELEFONO = "telefono"
    const val IDENTIFICADOR = "identificador"
    const val INSTITUCION = "institucion"
    const val ESCUELA = "escuela"
    const val CARRERA = "carrera"
    const val CONTRASENA = "contrasena"
    const val PIN = "pin"
    const val PIN_CONFIRMACION = "pinConfirmacion"
    const val USUARIO = "usuario"
    const val PRIORIDAD = "prioridad"
    const val CODIGO = "codigo"
    const val NOMBRE_GRUPO = "nombreGrupo"
    const val CUENTA_REGRESIVA = "cuentaRegresiva"
    const val MOTIVO = "motivo"

    val CUENTAS_REGRESIVAS_PERMITIDAS = setOf(3, 5, 10)

    private val SOLO_LETRAS_Y_ESPACIOS = Regex("^[\\p{L} ]+$")
    private val IDENTIFICADOR_REGEX = Regex("^[A-Za-z0-9]{6,15}$")
    private val PIN_REGEX = Regex("^\\d{4}$")
    private val CODIGO_REGEX = Regex("^[A-Z0-9]{3}-[A-Z0-9]{3}$")

    /** Quita espacios, guiones, paréntesis y el prefijo +52 para dejar 10 dígitos. */
    fun normalizarTelefono(entrada: String): String {
        var t = entrada.filter { it.isDigit() || it == '+' }
        if (t.startsWith("+52")) t = t.removePrefix("+52")
        return t.filter { it.isDigit() }
    }

    fun esTelefonoValido(entrada: String): Boolean = normalizarTelefono(entrada).length == 10

    /** Pasa a mayúsculas y acepta «k7q42m» o «K7Q-42M»; devuelve null si no tiene el formato AAA-999. */
    fun normalizarCodigoInvitacion(entrada: String): String? {
        val limpio = entrada.trim().uppercase().filter { it.isLetterOrDigit() || it == '-' }
        val conGuion = if (limpio.length == 6 && '-' !in limpio) limpio.substring(0, 3) + "-" + limpio.substring(3) else limpio
        return conGuion.takeIf { CODIGO_REGEX.matches(it) }
    }

    // ---------- A-02 Registro ----------

    fun validarNombreCompleto(nombre: String): String? {
        val n = nombre.trim()
        return when {
            n.isEmpty() -> "Escribe tu nombre completo"
            n.length !in 3..120 -> "Debe tener entre 3 y 120 caracteres"
            !SOLO_LETRAS_Y_ESPACIOS.matches(n) -> "Solo letras y espacios"
            else -> null
        }
    }

    fun validarTelefono(telefono: String): String? =
        if (esTelefonoValido(telefono)) null else "Escribe los 10 dígitos de tu número"

    fun validarIdentificador(id: String): String? {
        val v = id.trim()
        return when {
            v.isEmpty() -> "Escribe tu matrícula o número de empleado"
            !IDENTIFICADOR_REGEX.matches(v) -> "Alfanumérico de 6 a 15 caracteres"
            else -> null
        }
    }

    fun validarCarrera(carrera: String): String? =
        if (carrera.trim().length in 3..120) null else "Debe tener entre 3 y 120 caracteres"

    fun validarContrasena(contrasena: String): String? = when {
        contrasena.length < 8 -> "Mínimo 8 caracteres"
        contrasena.none { it.isLetter() } || contrasena.none { it.isDigit() } -> "Debe tener al menos una letra y un número"
        else -> null
    }

    fun validarPin(pin: String, confirmacion: String): Map<String, String> {
        val e = mutableMapOf<String, String>()
        if (!PIN_REGEX.matches(pin)) e[PIN] = "El PIN debe tener 4 dígitos"
        else if (pin != confirmacion) e[PIN_CONFIRMACION] = "Los PIN no coinciden"
        return e
    }

    fun validarRegistro(d: DatosRegistro): Map<String, String> {
        val e = mutableMapOf<String, String>()
        validarNombreCompleto(d.nombre)?.let { e[NOMBRE] = it }
        validarTelefono(d.telefonoMovil)?.let { e[TELEFONO] = it }
        validarIdentificador(d.identificadorInstitucional)?.let { e[IDENTIFICADOR] = it }
        if (d.institucionId == null) e[INSTITUCION] = "Elige tu institución"
        if (d.escuelaId == null) e[ESCUELA] = "Elige tu escuela"
        validarCarrera(d.carreraAdscripcion)?.let { e[CARRERA] = it }
        validarContrasena(d.contrasena)?.let { e[CONTRASENA] = it }
        return e
    }

    // ---------- A-03 Inicio de sesión ----------

    fun validarInicioSesion(usuario: String, contrasena: String): Map<String, String> {
        val e = mutableMapOf<String, String>()
        val u = usuario.trim()
        val esTelefono = u.all { it.isDigit() || it in " +-()" } && esTelefonoValido(u)
        if (u.isEmpty()) e[USUARIO] = "Escribe tu teléfono o matrícula"
        else if (!esTelefono && !IDENTIFICADOR_REGEX.matches(u)) e[USUARIO] = "Escribe 10 dígitos o tu matrícula"
        if (contrasena.isEmpty()) e[CONTRASENA] = "Escribe tu contraseña"
        return e
    }

    /** Devuelve el teléfono normalizado si el usuario escribió un teléfono; si no, la matrícula tal cual. */
    fun normalizarUsuario(usuario: String): String {
        val u = usuario.trim()
        return if (u.all { it.isDigit() || it in " +-()" } && esTelefonoValido(u)) normalizarTelefono(u) else u
    }

    // ---------- A-09 Contactos ----------

    fun validarContacto(
        nombre: String,
        telefono: String,
        existentes: List<Contacto>,
        idEditado: String? = null
    ): Map<String, String> {
        val e = mutableMapOf<String, String>()
        val n = nombre.trim()
        if (n.length !in 2..80) e[NOMBRE] = "Debe tener entre 2 y 80 caracteres"
        if (!esTelefonoValido(telefono)) {
            e[TELEFONO] = "Escribe los 10 dígitos del número"
        } else {
            val tel = normalizarTelefono(telefono)
            if (existentes.any { it.id != idEditado && normalizarTelefono(it.telefono) == tel }) {
                e[TELEFONO] = "Ese número ya está en tu lista"
            }
        }
        return e
    }

    /** Prioridades 1..n sin repetir (A-09). */
    fun prioridadesValidas(contactos: List<Contacto>): Boolean =
        contactos.map { it.prioridad }.sorted() == (1..contactos.size).toList()

    // ---------- A-10 Círculo ----------

    fun validarNombreGrupo(nombre: String): String? =
        if (nombre.trim().length in 2..40) null else "Debe tener entre 2 y 40 caracteres"

    fun validarCodigo(codigo: String): String? =
        if (normalizarCodigoInvitacion(codigo) != null) null else "El código tiene el formato AAA-999"

    // ---------- A-12 Activación ----------

    fun validarCuentaRegresiva(segundos: Int): String? =
        if (segundos in CUENTAS_REGRESIVAS_PERMITIDAS) null else "Elige 3, 5 o 10 segundos"
}
