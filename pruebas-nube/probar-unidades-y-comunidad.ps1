# Prueba en la nube de lo nuevo del servidor: administracion de usuarios y unidades (CU-07, CU-10), turno del
# operador, acuse y atencion de punta a punta, acuse y cancelacion por la cola sin senal (A-14), catalogo (CU-08,
# CU-09), trayectos y check-ins (P11), reportes, moderacion e identidad protegida (P9), feed, mapa de calor (P10),
# ruta segura (P12) y mapas offline.
#
# Crea tres cuentas de prueba (quien activa las alertas, un operador y un Administrador temporal), una unidad de
# prueba en Seguridad IPN, y al final cierra o cancela las alertas, retira los reportes, da de baja la unidad,
# regresa las cuentas a Integrante y las da de baja. El catalogo solo se prueba con un borrador que se descarta.
#
# Pide la contrasena de la base de Azure para volver Administrador la cuenta temporal. La contrasena solo se usa
# en esta terminal. Necesita Docker prendido con el contenedor sendero-postgres.
#
# Correr:   powershell -ExecutionPolicy Bypass -File pruebas-nube\probar-unidades-y-comunidad.ps1

param(
    [string]$Base = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1",
    [string]$Contenedor = "sendero-postgres",
    [string]$ConexionBase = "host=sendero-seguro-db.postgres.database.azure.com port=5432 dbname=sendero_seguro user=sendero sslmode=require"
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$base = $Base.TrimEnd("/")
$fallas = 0
$inv = [Globalization.CultureInfo]::InvariantCulture

# ---------------------------------------------------------------- ayudas

# Hace la peticion y regresa el codigo HTTP, el JSON (si lo hay) y el texto, tambien cuando el servidor responde un error.
function Pedir($metodo, $ruta, $token = $null, $cuerpo = $null, $encabezadosExtra = $null) {
    $encabezados = @{}
    if ($token) { $encabezados["Authorization"] = "Bearer $token" }
    if ($encabezadosExtra) { foreach ($k in $encabezadosExtra.Keys) { $encabezados[$k] = $encabezadosExtra[$k] } }
    $p = @{ Method = $metodo; Uri = "$base$ruta"; Headers = $encabezados; UseBasicParsing = $true }
    if ($null -ne $cuerpo) {
        if ($cuerpo -is [byte[]]) {
            $p["Body"] = $cuerpo
        } else {
            $p["ContentType"] = "application/json; charset=utf-8"
            $p["Body"] = [Text.Encoding]::UTF8.GetBytes(($cuerpo | ConvertTo-Json -Depth 8 -Compress))
        }
    }
    try {
        $r = Invoke-WebRequest @p
        $codigo = [int]$r.StatusCode
        $texto = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }
        $codigo = [int]$resp.StatusCode
        $texto = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $texto = $_.ErrorDetails.Message }
        elseif ($resp -is [System.Net.HttpWebResponse]) {
            $lector = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
            $texto = $lector.ReadToEnd()
        }
    }
    $json = $null
    if ($texto) { try { $json = $texto | ConvertFrom-Json } catch { $json = $null } }
    return @{ codigo = $codigo; json = $json; texto = $texto }
}

function PedirArchivo($ruta, $token, $bytes, $tipo) {
    try {
        $r = Invoke-WebRequest -Method POST -Uri "$base$ruta" -Headers @{ Authorization = "Bearer $token" } `
            -ContentType $tipo -Body $bytes -UseBasicParsing
        return @{ codigo = [int]$r.StatusCode; json = ([Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json) }
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { throw }
        return @{ codigo = [int]$resp.StatusCode; json = $null }
    }
}

function Revisar($nombre, $esperados, $r) {
    if (@($esperados) -contains $r.codigo) {
        Write-Host ("  OK    " + $nombre + " (" + $r.codigo + ")")
        return $true
    }
    $script:fallas++
    $extra = ""
    if ($r.json -and $r.json.codigo) { $extra = " " + $r.json.codigo + ": " + $r.json.mensaje }
    Write-Host ("  FALLA " + $nombre + ": se esperaba " + (@($esperados) -join " o ") + " y llego " + $r.codigo + $extra) -ForegroundColor Red
    return $false
}

function Comprobar($nombre, $condicion, $detalle = "") {
    if ($condicion) { Write-Host ("  OK    " + $nombre) }
    else {
        $script:fallas++
        Write-Host ("  FALLA " + $nombre + $(if ($detalle) { " (" + $detalle + ")" } else { "" })) -ForegroundColor Red
    }
}

function Ahora([int]$segundos = 0) {
    return [DateTime]::UtcNow.AddSeconds($segundos).ToString("yyyy-MM-ddTHH:mm:ss.fff'Z'", $inv)
}

function NuevaCuenta($nombre) {
    $n = [string]$script:aleatorio.Next(10000000, 99999999)
    $c = @{
        telefono = "55" + $n; matricula = "NUBE" + $n; contrasena = "Nube" + [string]$script:aleatorio.Next(100000, 999999)
        dispositivo = @{ id = [string][guid]::NewGuid(); modelo = "Prueba nube"; androidApi = 34; soportaWifiAware = $false }
    }
    $registro = @{
        nombre = $nombre; telefonoMovil = $c.telefono; identificadorInstitucional = $c.matricula; tipoComunidad = "ALUMNO"
        escuelaId = $script:escuela.id; carreraAdscripcion = "Prueba en la nube"; contrasena = $c.contrasena
        aceptaEntrenamientoIa = $false; consentimiento = @{ avisoId = $script:aviso.json.id }; dispositivo = $c.dispositivo
    }
    $r = Pedir POST "/auth/registro" $null $registro
    $c.registro = $registro
    $c.r = $r
    if ($r.codigo -eq 201) { $c.token = $r.json.token; $c.id = $r.json.usuario.id }
    return $c
}

function Alerta($id, $activadaEn, $lat = 19.504507, $lon = -99.146911) {
    return @{
        id = $id; activadaEn = $activadaEn; detonador = "BOTON"; nivelBateria = 80
        ubicacion = @{ latitud = $lat; longitud = $lon; precisionM = 8.0; bateria = 80; capturadaEn = $activadaEn }
    }
}

# Lecturas cada 10 s hacia atras desde $segundosInicio (el servidor toma una hora futura como ahora).
function Lecturas($n, $segundosInicio) {
    $lista = @()
    for ($i = 0; $i -lt $n; $i++) {
        $lista += @{ latitud = 19.504507 + 0.0001 * ($i + 1); longitud = -99.146911; precisionM = 6.0; bateria = 79; capturadaEn = (Ahora ($segundosInicio - $i * 10)) }
    }
    return ,$lista
}

function Cancelar($token, $alertaId, $exitoso, $dispositivoId) {
    $c = @{ dispositivoId = $dispositivoId; metodo = "PIN"; exitoso = $exitoso; intentadoEn = (Ahora) }
    if ($exitoso) { $c.motivo = "FALSA_ALARMA" }
    return Pedir POST "/alertas/$alertaId/cancelaciones" $token $c
}

# Consulta en la base de Azure con el psql del contenedor. Reintenta por si el nombre del servidor no resuelve a la primera.
function Sql($consulta) {
    $ErrorActionPreference = "Continue"
    for ($i = 0; $i -lt 3; $i++) {
        $salida = docker exec -e "PGPASSWORD=$script:clavePg" $Contenedor psql $ConexionBase -v ON_ERROR_STOP=1 -tAc $consulta 2>&1
        if ($LASTEXITCODE -eq 0) { return ($salida | Out-String).Trim() }
        Start-Sleep -Seconds 2
    }
    throw ("psql no pudo conectarse: " + ($salida | Out-String))
}


# Espera a que la alerta quede asignada a la unidad de prueba (la dispersion corre en segundo plano).
function EsperarAsignacion($alertaId, $unidadId, $segundos = 40) {
    $limite = [DateTime]::UtcNow.AddSeconds($segundos)
    while ([DateTime]::UtcNow -lt $limite) {
        $r = Pedir GET "/alertas/$alertaId" $script:admin.token
        if ($r.codigo -eq 200 -and @($r.json.asignaciones | Where-Object { $_.unidadId -eq $unidadId -and -not $_.liberadaEn }).Count -gt 0) { return $r }
        Start-Sleep -Seconds 2
    }
    return $null
}

function Lote($cuenta, $elementos) {
    return Pedir POST "/sync/lote" $cuenta.token @{ dispositivoId = $cuenta.dispositivo.id; elementos = @($elementos) }
}

function Resultados($r) {
    return (@($r.json.resultados | ForEach-Object { $_.resultado + $(if ($_.error) { " " + $_.error.codigo } else { "" }) }) -join ",")
}

function Entrar($cuenta, $conTelefono = $true) {
    $datos = @{ usuario = $cuenta.telefono; contrasena = $cuenta.contrasena }
    if ($conTelefono) { $datos.dispositivo = $cuenta.dispositivo }
    $r = Pedir POST "/auth/login" $null $datos
    if ($r.codigo -eq 200) { $cuenta.token = $r.json.token }
    return $r
}

# ---------------------------------------------------------------- pregunta

Write-Host "Prueba de unidades y comunidad contra $base"
Write-Host ""
$seguro = Read-Host "Contrasena de la base de Azure (para el Administrador temporal)" -AsSecureString
if ($seguro.Length -eq 0) { Write-Host "Sin la contrasena no se puede probar la consola." -ForegroundColor Red; exit 1 }
$clavePg = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro))
Write-Host ""

$aleatorio = New-Object Random
$a = $null; $op = $null; $admin = $null
$unidad = $null
$id1 = $null; $id2 = $null
$rep1 = $null
$lat = 19.504507; $lon = -99.146911

try {

# ---------------------------------------------------------------- 1. cuentas

Write-Host "1. Cuentas de prueba"
$salud = Pedir GET "/salud"; Revisar "salud" 200 $salud | Out-Null
$aviso = Pedir GET "/avisos-privacidad/vigente"
$inst = Pedir GET "/instituciones"
$ipn = @($inst.json | Where-Object { $_.siglas -eq "IPN" })[0]
if (-not $ipn) { $ipn = @($inst.json)[0] }
$escuela = @((Pedir GET ("/instituciones/" + $ipn.id + "/escuelas")).json)[0]

$a = NuevaCuenta "Prueba Comunidad"
if (-not (Revisar "registro de quien activa las alertas" 201 $a.r)) { throw "Sin la cuenta de prueba no se puede seguir" }
$op = NuevaCuenta "Operador Nube"
if (-not (Revisar "registro del futuro operador" 201 $op.r)) { throw "Sin la cuenta del operador no se puede seguir" }
$admin = NuevaCuenta "Consola Nube"
if (-not (Revisar "registro de la cuenta de consola" 201 $admin.r)) { throw "Sin la cuenta de consola no se puede seguir" }
Sql ("UPDATE usuario SET rol = 'ADMINISTRADOR' WHERE id = '" + $admin.id + "'") | Out-Null
$admin.promovida = $true
Revisar "login de consola" 200 (Entrar $admin $false) | Out-Null

# ---------------------------------------------------------------- 2. administracion

Write-Host "2. Usuarios, corporaciones y unidades (CU-07, CU-10)"
Revisar "una cuenta de la app no busca usuarios" 403 (Pedir GET "/usuarios?buscar=prueba" $a.token) | Out-Null
$r = Pedir GET ("/usuarios?buscar=" + $op.matricula) $admin.token
Revisar "buscar al futuro operador" 200 $r | Out-Null
Comprobar "la busqueda lo encuentra" (@($r.json | Where-Object { $_.id -eq $op.id }).Count -eq 1)
$r = Pedir GET "/corporaciones" $admin.token
Revisar "corporaciones" 200 $r | Out-Null
$corp = @($r.json | Where-Object { $_.canalCodigo -eq "SEG_ESCOLAR" })[0]
if (-not $corp) { throw "No esta la corporacion SEG_ESCOLAR" }
Revisar "unidad con un tipo que no existe" 400 (Pedir POST "/unidades" $admin.token @{ corporacionId = $corp.id; nombre = "No debe existir"; tipos = @("NO_EXISTE") }) | Out-Null
$r = Pedir POST "/unidades" $admin.token @{ corporacionId = $corp.id; nombre = ("Unidad prueba nube " + $aleatorio.Next(1000, 9999)); tipos = @("GENERICA") }
if (-not (Revisar "crear unidad de prueba en Seguridad IPN" 201 $r)) { throw "Sin unidad no se puede seguir" }
$unidad = $r.json
Revisar "operador sin unidad" 400 (Pedir PATCH ("/usuarios/" + $op.id + "/perfil") $admin.token @{ rol = "OPERADOR"; motivo = "Prueba en la nube" }) | Out-Null
Revisar "cambiar el perfil de la propia cuenta" 409 (Pedir PATCH ("/usuarios/" + $admin.id + "/perfil") $admin.token @{ rol = "INTEGRANTE"; motivo = "Prueba en la nube" }) | Out-Null
$r = Pedir PATCH ("/usuarios/" + $op.id + "/perfil") $admin.token @{ rol = "OPERADOR"; unidadId = $unidad.id; motivo = "Prueba en la nube" }
Revisar "volverlo Operador de la unidad" 200 $r | Out-Null
$op.esOperador = $true
Revisar "su sesion anterior ya no sirve" 401 (Pedir GET "/usuarios/me" $op.token) | Out-Null
Revisar "el operador entra desde la app (A-14)" 200 (Entrar $op) | Out-Null
$r = Pedir GET ("/unidades/" + $unidad.id) $admin.token
Revisar "detalle de la unidad" 200 $r | Out-Null
Comprobar "la unidad tiene al operador" (@($r.json.operadores | Where-Object { $_.usuarioId -eq $op.id -and $_.activo }).Count -eq 1)

# ---------------------------------------------------------------- 3. turno y atencion

Write-Host "3. Turno, acuse y atencion"
Revisar "una cuenta de la app no tiene unidad" 403 (Pedir GET "/unidades/mia" $a.token) | Out-Null
Revisar "el operador ve su unidad" 200 (Pedir GET "/unidades/mia" $op.token) | Out-Null
$r = Pedir PATCH "/unidades/mia" $op.token @{ estado = "DISPONIBLE"; latitud = $lat + 0.001; longitud = $lon }
Revisar "abrir turno con la ubicacion" 200 $r | Out-Null
Comprobar "la unidad quedo DISPONIBLE" ($r.json.estado -eq "DISPONIBLE") $r.json.estado

$id1 = [string][guid]::NewGuid()
Revisar "registrar alerta" 201 (Pedir POST "/alertas" $a.token (Alerta $id1 (Ahora -5) $lat $lon)) | Out-Null
$r = EsperarAsignacion $id1 $unidad.id
Comprobar "la alerta se asigno a la unidad de prueba" ($null -ne $r) "no se asigno en 40 s"
if ($r) {
    Revisar "cerrar turno con una alerta asignada" 409 (Pedir PATCH "/unidades/mia" $op.token @{ estado = "FUERA_DE_SERVICIO" }) | Out-Null
    Revisar "la persona no puede dar el acuse" 403 (Pedir POST "/alertas/$id1/acuse" $a.token) | Out-Null
    $r = Pedir POST "/alertas/$id1/acuse" $op.token
    Revisar "acuse del operador" 200 $r | Out-Null
    Comprobar "la alerta quedo ACUSADA" ($r.json.estado -eq "ACUSADA") $r.json.estado
    Revisar "en atencion" 200 (Pedir PATCH "/alertas/$id1/estado" $op.token @{ estado = "EN_ATENCION" }) | Out-Null
    Revisar "atendida" 200 (Pedir PATCH "/alertas/$id1/estado" $op.token @{ estado = "ATENDIDA" }) | Out-Null
    Revisar "cerrar sin resultado" 400 (Pedir PATCH "/alertas/$id1/estado" $op.token @{ estado = "CERRADA" }) | Out-Null
    $r = Pedir PATCH "/alertas/$id1/estado" $op.token @{ estado = "CERRADA"; resultadoCierre = "Prueba en la nube" }
    Revisar "cerrar" 200 $r | Out-Null
    Comprobar "la alerta quedo CERRADA" ($r.json.estado -eq "CERRADA") $r.json.estado
    $r = Pedir GET "/unidades/mia" $op.token
    Comprobar "la unidad volvio a DISPONIBLE" ($r.json.estado -eq "DISPONIBLE") $r.json.estado
}

# ---------------------------------------------------------------- 4. cola sin senal

Write-Host "4. Acuse y cancelacion por la cola sin senal (A-14)"
$id2 = [string][guid]::NewGuid()
$activada2 = Ahora -5
Revisar "registrar alerta" 201 (Pedir POST "/alertas" $a.token (Alerta $id2 $activada2 $lat $lon)) | Out-Null
$r = EsperarAsignacion $id2 $unidad.id
Comprobar "la alerta se asigno a la unidad de prueba" ($null -ne $r) "no se asigno en 40 s"
$acuse = @{ tipo = "ACUSE"; id = [string][guid]::NewGuid(); creadoEn = (Ahora); datos = @{
    idCortoAlerta = $id2.Substring(0, 8); idCortoUsuario = ($a.id -replace "-", "").Substring(24, 8)
    activadaEn = $activada2; acusadaEn = (Ahora); alertaId = $id2 } }
$acuseAjeno = $acuse.Clone(); $acuseAjeno.id = [string][guid]::NewGuid()
Revisar "la persona no manda acuses" 200 ($rp = Lote $a @($acuseAjeno)) | Out-Null
Comprobar "el acuse de la persona se rechaza" ((Resultados $rp) -like "RECHAZADO*") (Resultados $rp)
$r = Lote $op @($acuse)
Revisar "el operador manda su cola" 200 $r | Out-Null
Comprobar "el acuse entro" ((Resultados $r) -eq "ACEPTADO") (Resultados $r)
$r = Pedir GET "/alertas/$id2" $admin.token
Comprobar "la alerta quedo ACUSADA" ($r.json.estado -eq "ACUSADA") $r.json.estado
$r = Lote $op @($acuse)
Comprobar "reenviar el acuse no lo duplica" ((Resultados $r) -eq "DUPLICADO") (Resultados $r)
$cancelacion = @{ tipo = "CANCELACION"; id = [string][guid]::NewGuid(); creadoEn = (Ahora); datos = @{
    alertaId = $id2; dispositivoId = $a.dispositivo.id; metodo = "PIN"; exitoso = $true; motivo = "FALSA_ALARMA"; intentadoEn = (Ahora) } }
$r = Lote $a @($cancelacion)
Revisar "la persona manda su cancelacion por la cola" 200 $r | Out-Null
Comprobar "la cancelacion entro" ((Resultados $r) -eq "ACEPTADO") (Resultados $r)
$r = Pedir GET "/alertas/$id2" $a.token
Comprobar "la alerta quedo CANCELADA" ($r.json.estado -eq "CANCELADA") $r.json.estado
Start-Sleep -Seconds 2
$r = Pedir PATCH "/unidades/mia" $op.token @{ estado = "FUERA_DE_SERVICIO" }
Revisar "cerrar turno" 200 $r | Out-Null

# ---------------------------------------------------------------- 5. catalogo

Write-Host "5. Catalogo (CU-08, CU-09)"
$r = Pedir GET "/catalogo"
Revisar "catalogo vigente sin token" 200 $r | Out-Null
$numero = $r.json.numero
$acoso = @($r.json.tipos | Where-Object { $_.codigo -eq "ACOSO" })[0]
Comprobar "el catalogo trae ACOSO" ($null -ne $acoso)
Revisar "la app ya tiene la version vigente" 304 (Pedir GET "/catalogo?version=$numero") | Out-Null
Revisar "una cuenta de la app no edita el catalogo" 403 (Pedir GET "/catalogo/versiones" $a.token) | Out-Null
Revisar "versiones del catalogo" 200 (Pedir GET "/catalogo/versiones" $admin.token) | Out-Null
$r = Pedir POST "/catalogo/versiones" $admin.token @{}
if ($r.codigo -eq 409) {
    Write-Host "  (ya hay una version en edicion: no se toca)"
} elseif (Revisar "abrir un borrador" 201 $r) {
    $borrador = $r.json.version.numero
    if (-not $borrador) { $borrador = $r.json.numero }
    $r = Pedir POST "/catalogo/versiones/$borrador/validar" $admin.token @{}
    Revisar "validar el borrador" 200 $r | Out-Null
    Comprobar "el borrador no tiene errores" (@($r.json.errores).Count -eq 0) ((@($r.json.errores) | ForEach-Object { $_.codigo }) -join ",")
    Revisar "descartar el borrador" 204 (Pedir DELETE "/catalogo/versiones/$borrador" $admin.token) | Out-Null
}

# ---------------------------------------------------------------- 6. trayectos

Write-Host "6. Trayectos y check-ins (P11)"
$t1 = [string][guid]::NewGuid()
$trayecto = @{ id = $t1; tipoRuta = "MAS_CORTA"; origenLatitud = $lat; origenLongitud = $lon; destinoLatitud = $lat + 0.004
    destinoLongitud = $lon + 0.003; distanciaM = 550; duracionEstMin = 8; intervaloCheckinMin = 5; iniciadoEn = (Ahora -60); compartir = $false }
Revisar "iniciar trayecto" 201 (Pedir POST "/trayectos" $a.token $trayecto) | Out-Null
Revisar "reenviar el mismo trayecto" 200 (Pedir POST "/trayectos" $a.token $trayecto) | Out-Null
Revisar "check-in" 200 (Pedir POST "/trayectos/$t1/checkins" $a.token @{ esperadoEn = (Ahora); confirmadoEn = (Ahora) }) | Out-Null
Revisar "mandar ubicacion" 200 (Pedir PATCH "/trayectos/$t1" $a.token @{ latitud = $lat + 0.002; longitud = $lon + 0.001; ubicacionEn = (Ahora) }) | Out-Null
Revisar "otra persona no ve el trayecto" @(403, 404) (Pedir GET "/trayectos/$t1" $op.token) | Out-Null
$r = Pedir PATCH "/trayectos/$t1" $a.token @{ estado = "FINALIZADO"; finalizadoEn = (Ahora) }
Revisar "llegar al destino" 200 $r | Out-Null
Comprobar "el trayecto quedo FINALIZADO" ($r.json.estado -eq "FINALIZADO") $r.json.estado
Revisar "un trayecto terminado no se reabre" 409 (Pedir PATCH "/trayectos/$t1" $a.token @{ estado = "EN_CURSO" }) | Out-Null
$t2 = [string][guid]::NewGuid()
$tSync = $trayecto.Clone(); $tSync.id = $t2; $tSync.estado = "FINALIZADO"; $tSync.finalizadoEn = (Ahora)
$elemT = @{ tipo = "TRAYECTO"; id = $t2; creadoEn = (Ahora -60); datos = $tSync }
$elemC = @{ tipo = "CHECKIN"; id = [string][guid]::NewGuid(); creadoEn = (Ahora -30); datos = @{ trayectoId = $t2; esperadoEn = (Ahora -30); confirmadoEn = (Ahora -30) } }
$r = Lote $a @($elemC, $elemT)
Revisar "trayecto y check-in por la cola" 200 $r | Out-Null
Comprobar "los dos entraron" ((Resultados $r) -eq "ACEPTADO,ACEPTADO") (Resultados $r)

# ---------------------------------------------------------------- 7. reportes y moderacion

Write-Host "7. Reportes, moderacion e identidad protegida (P9)"
function Reporte($anonimo, $texto) {
    return @{ id = [string][guid]::NewGuid(); tipoIncidenteId = $acoso.id; descripcion = $texto; latitud = $lat; longitud = $lon
        precisionM = 10.0; esAnonimo = $anonimo; creadoEn = (Ahora -30) }
}
$e1 = Reporte $false "Prueba en la nube: reporte con nombre"
$r = Pedir POST "/reportes" $a.token $e1
Revisar "levantar reporte con nombre" 201 $r | Out-Null
$rep1 = $r.json.id
Comprobar "queda PENDIENTE" ($r.json.estado -eq "PENDIENTE") $r.json.estado
Revisar "reenviar el mismo reporte" 200 (Pedir POST "/reportes" $a.token $e1) | Out-Null
Revisar "reporte demasiado corto" 400 (Pedir POST "/reportes" $a.token (Reporte $false "corto")) | Out-Null
$r = Pedir POST "/reportes" $a.token (Reporte $true "Prueba en la nube: reporte anonimo")
Revisar "levantar reporte anonimo" 201 $r | Out-Null
$rep2 = $r.json.id
$r = Pedir GET "/reportes/mios" $a.token
Revisar "mis reportes" 200 $r | Out-Null
Comprobar "salen los reportes con nombre y no el anonimo" ((@($r.json | Where-Object { $_.id -eq $rep1 }).Count -eq 1) -and (@($r.json | Where-Object { $_.id -eq $rep2 }).Count -eq 0))
Revisar "una cuenta de la app no modera" 403 (Pedir GET "/moderacion/reportes" $a.token) | Out-Null
$r = Pedir GET "/moderacion/reportes?estado=PENDIENTE" $admin.token
Revisar "cola de moderacion" 200 $r | Out-Null
$r = Pedir GET "/moderacion/reportes/$rep2" $admin.token
Revisar "detalle del anonimo" 200 $r | Out-Null
Comprobar "la consola no ve el nombre del anonimo" ($r.json.autor -notlike "*Comunidad*") $r.json.autor
Revisar "identidad sin justificacion suficiente" 400 (Pedir POST "/reportes/$rep2/identidad" $admin.token @{ justificacion = "corta" }) | Out-Null
$r = Pedir POST "/reportes/$rep2/identidad" $admin.token @{ justificacion = "Prueba en la nube de la identidad protegida" }
Revisar "identidad con justificacion (queda en la bitacora)" 200 $r | Out-Null
Comprobar "la identidad es la de quien lo levanto" ($r.json.usuarioId -eq $a.id)
Revisar "rechazar sin motivo" 400 (Pedir POST "/reportes/$rep2/decision" $admin.token @{ decision = "RECHAZAR" }) | Out-Null
Revisar "rechazar el anonimo" 200 (Pedir POST "/reportes/$rep2/decision" $admin.token @{ decision = "RECHAZAR"; motivo = "Prueba en la nube" }) | Out-Null
$r = Pedir POST "/reportes/$rep1/decision" $admin.token @{ decision = "PUBLICAR" }
Revisar "publicar el reporte con nombre" 200 $r | Out-Null
Revisar "publicar dos veces" 409 (Pedir POST "/reportes/$rep1/decision" $admin.token @{ decision = "PUBLICAR" }) | Out-Null

# ---------------------------------------------------------------- 8. feed y mapa de calor

Write-Host "8. Feed y mapa de calor (P10)"
$cerca = "latitud=" + $lat.ToString($inv) + "&longitud=" + $lon.ToString($inv) + "&radioM=1000"
$r = Pedir GET "/feed?$cerca" $a.token
Revisar "feed cercano" 200 $r | Out-Null
Comprobar "el feed trae el reporte publicado" (@($r.json.reportes | Where-Object { $_.id -eq $rep1 }).Count -eq 1)
Revisar "una cuenta de la app no genera el mapa" 403 (Pedir POST "/mapa-calor/generar" $a.token @{}) | Out-Null
$r = Pedir POST "/mapa-calor/generar" $admin.token @{}
Revisar "generar el mapa de calor" 200 $r | Out-Null
$version = $r.json.version
$r = Pedir GET "/mapa-calor?periodo=30" $a.token
Revisar "capa de riesgo de 30 dias" 200 $r | Out-Null
Revisar "la app ya tiene la capa vigente" 304 (Pedir GET "/mapa-calor?version=$version" $a.token) | Out-Null
$r = Pedir POST "/reportes/$rep1/decision" $admin.token @{ decision = "RETIRAR"; motivo = "Prueba en la nube" }
Revisar "retirar el reporte publicado" 200 $r | Out-Null
$r = Pedir GET "/feed?$cerca" $a.token
Comprobar "el feed ya no lo trae" (@($r.json.reportes | Where-Object { $_.id -eq $rep1 }).Count -eq 0)
$rep1 = $null

# ---------------------------------------------------------------- 9. ruta segura y mapas

Write-Host "9. Ruta segura y mapas offline (P12)"
$r = Pedir POST "/rutas/segura" $a.token @{ origen = @{ latitud = $lat; longitud = $lon }; destino = @{ latitud = $lat + 0.004; longitud = $lon + 0.003 } }
if ($r.codigo -eq 422) {
    Write-Host ("  OK    ruta segura: todavia no hay grafo cargado en esta zona (" + $r.json.codigo + ")")
} else {
    Revisar "ruta segura" 200 $r | Out-Null
}
Revisar "grafo sin caja" 400 (Pedir GET "/grafo" $a.token) | Out-Null
Revisar "versiones para el telefono" 200 (Pedir GET "/mapas/version" $a.token) | Out-Null
Revisar "paquetes de mapa" 200 (Pedir GET "/mapas/paquetes" $a.token) | Out-Null
Revisar "puntos de interes" 200 (Pedir GET "/puntos-interes" $a.token) | Out-Null
Revisar "zonas" 200 (Pedir GET "/zonas" $a.token) | Out-Null
Revisar "una cuenta de la app no crea zonas" 403 (Pedir POST "/zonas" $a.token @{ nombre = "No"; minLatitud = 19.5; minLongitud = -99.15; maxLatitud = 19.51; maxLongitud = -99.14 }) | Out-Null
Revisar "una cuenta de la app no carga el grafo" 403 (Pedir POST "/grafo" $a.token @{ nodos = @(); aristas = @() }) | Out-Null

} finally {

# ---------------------------------------------------------------- limpieza

Write-Host "10. Limpieza"
foreach ($id in @($id1, $id2)) {
    if ($id -and $a -and $a.token) {
        $r = Pedir GET "/alertas/$id" $a.token
        if ($r.codigo -eq 200 -and @("CERRADA", "CANCELADA") -notcontains $r.json.estado) { Cancelar $a.token $id $true $a.dispositivo.id | Out-Null }
    }
}
if ($rep1 -and $admin -and $admin.token) { Pedir POST "/reportes/$rep1/decision" $admin.token @{ decision = "RETIRAR"; motivo = "Prueba en la nube" } | Out-Null }
if ($unidad -and $admin -and $admin.token) {
    Revisar "baja de la unidad de prueba" 200 (Pedir PATCH ("/unidades/" + $unidad.id) $admin.token @{ activa = $false }) | Out-Null
}
if ($op -and $op.esOperador -and $admin -and $admin.token) {
    Revisar "el operador regresa a Integrante" 200 (Pedir PATCH ("/usuarios/" + $op.id + "/perfil") $admin.token @{ rol = "INTEGRANTE"; motivo = "Fin de la prueba en la nube" }) | Out-Null
}
if ($admin -and $admin.promovida) {
    try {
        Sql ("UPDATE usuario SET rol = 'INTEGRANTE' WHERE id = '" + $admin.id + "'") | Out-Null
        Write-Host "  OK    la cuenta de consola regreso a Integrante"
    } catch {
        $fallas++
        Write-Host ("  FALLA no se pudo regresar la cuenta de consola a Integrante. Corre a mano: UPDATE usuario SET rol = 'INTEGRANTE' WHERE id = '" + $admin.id + "'") -ForegroundColor Red
    }
}
foreach ($c in @($a, $op, $admin)) {
    if ($c -and $c.id) {
        $r = Entrar $c
        if ($r.codigo -eq 200) { Revisar "baja de la cuenta de prueba" 204 (Pedir POST "/usuarios/me/baja" $c.token @{ contrasena = $c.contrasena }) | Out-Null }
        else { $fallas++; Write-Host ("  FALLA no se pudo entrar para dar de baja la cuenta " + $c.matricula) -ForegroundColor Red }
    }
}
$clavePg = $null

}

Write-Host ""
if ($fallas -eq 0) {
    Write-Host "OK: lo nuevo funciona en la nube. Se limpio todo lo de prueba." -ForegroundColor Green
} else {
    Write-Host ("Hubo " + $fallas + " fallas.") -ForegroundColor Red
    exit 1
}
