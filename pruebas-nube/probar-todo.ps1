# Prueba completa en la nube: cuentas (P1, P2), registro de alertas (P4), dispersion y red de apoyo (P5),
# ubicacion en vivo (P6), cancelacion y PIN de coaccion (P8), relevo de la malla (RF-14), linea de tiempo e
# historial (P13), archivos (P14), sincronizacion offline (P15) y tiempo real de la consola (P16).
#
# Crea dos cuentas de prueba nuevas (la persona que activa las alertas y un telefono que retransmite por la malla),
# y al final cancela todas las alertas y da de baja las cuentas.
#
# Preguntas al empezar (las dos se pueden saltar con Enter):
#  - Un numero de telefono para recibir los SMS reales de la red de apoyo. Solo se usa en la primera alerta:
#    llegan 3 SMS (contacto agregado, alerta con enlace y alerta cancelada). El numero no se guarda en ningun archivo.
#  - La contrasena de la base de Azure, para probar tambien la consola (Administrador): con ella el script vuelve
#    Administrador una tercera cuenta de prueba por unos segundos y al final la regresa a Integrante y la da de baja.
#    La contrasena solo se usa en esta terminal. Necesita Docker prendido con el contenedor sendero-postgres.
#
# Correr:   powershell -ExecutionPolicy Bypass -File pruebas-nube\probar-todo.ps1
# Contra el servidor local:   ... -File pruebas-nube\probar-todo.ps1 -Base http://localhost:8080/api/v1

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

# Escucha la consola en tiempo real hasta ver un mensaje que traiga el texto buscado o hasta que pasen los segundos.
function EsperarEnConsola($ws, $buscado, $segundos) {
    $limite = [DateTime]::UtcNow.AddSeconds($segundos)
    while ([DateTime]::UtcNow -lt $limite -and $ws.State -eq "Open") {
        if (-not $script:wsPendiente) {
            $script:wsBufer = New-Object byte[] 65536
            $segmento = New-Object "System.ArraySegment[byte]" -ArgumentList (, $script:wsBufer)
            $script:wsPendiente = $ws.ReceiveAsync($segmento, [Threading.CancellationToken]::None)
        }
        $resta = [int]([Math]::Max(1, ($limite - [DateTime]::UtcNow).TotalMilliseconds))
        if ($script:wsPendiente.Wait($resta)) {
            $res = $script:wsPendiente.Result
            $script:wsPendiente = $null
            $texto = [Text.Encoding]::UTF8.GetString($script:wsBufer, 0, $res.Count)
            if ($texto.Contains($buscado)) { return $true }
        }
    }
    return $false
}

# ---------------------------------------------------------------- preguntas

Write-Host "Prueba completa contra $base"
Write-Host ""
$telefonoContacto = (Read-Host "Numero (10 digitos) que recibe los SMS de prueba, o Enter para no mandar SMS").Trim()
if ($telefonoContacto -and $telefonoContacto -notmatch '^[0-9]{10}$') { Write-Host "El numero debe tener 10 digitos." -ForegroundColor Red; exit 1 }
$conConsola = $false
$seguro = Read-Host "Contrasena de la base de Azure para probar la consola, o Enter para saltarla" -AsSecureString
if ($seguro.Length -gt 0) {
    $script:clavePg = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro))
    $conConsola = $true
}
Write-Host ""

$aleatorio = New-Object Random
$admin = $null
$wsPendiente = $null
$ws = $null

try {

# ---------------------------------------------------------------- 1. servidor y cuentas

Write-Host "1. Servidor y catalogos"
$salud = Pedir GET "/salud"; Revisar "salud" 200 $salud | Out-Null
Comprobar "base de datos OK" ($salud.json.componentes.baseDatos -eq "OK")
$aviso = Pedir GET "/avisos-privacidad/vigente"; Revisar "aviso de privacidad" 200 $aviso | Out-Null
$inst = Pedir GET "/instituciones"; Revisar "instituciones" 200 $inst | Out-Null
$ipn = @($inst.json | Where-Object { $_.siglas -eq "IPN" })[0]
if (-not $ipn) { $ipn = @($inst.json)[0] }
$esc = Pedir GET ("/instituciones/" + $ipn.id + "/escuelas"); Revisar "escuelas" 200 $esc | Out-Null
$escuela = @($esc.json)[0]

Write-Host "2. Cuentas de prueba (P1)"
$a = NuevaCuenta "Prueba Nube"
if (-not (Revisar "registro de quien activa las alertas" 201 $a.r)) { throw "Sin la cuenta de prueba no se puede seguir" }
$b = NuevaCuenta "Relevo Nube"
if (-not (Revisar "registro del telefono que retransmite" 201 $b.r)) { throw "Sin la segunda cuenta no se puede seguir" }
Revisar "registro repetido" 409 (Pedir POST "/auth/registro" $null $a.registro) | Out-Null
Revisar "ver perfil" 200 (Pedir GET "/usuarios/me" $a.token) | Out-Null
$mala = Pedir POST "/auth/login" $null @{ usuario = $a.telefono; contrasena = "Incorrecta1"; dispositivo = $a.dispositivo }
Revisar "login con contrasena mala" 401 $mala | Out-Null
$r = Pedir POST "/auth/login" $null @{ usuario = $a.matricula.ToLower(); contrasena = $a.contrasena; dispositivo = $a.dispositivo }
Revisar "login con matricula" 200 $r | Out-Null
$a.token = $r.json.token
Revisar "token push del telefono (P16)" 204 (Pedir PUT ("/dispositivos/" + $a.dispositivo.id + "/push") $a.token @{ fcmToken = "prueba-nube-" + [guid]::NewGuid() }) | Out-Null
Revisar "token push de un telefono ajeno" 403 (Pedir PUT ("/dispositivos/" + $b.dispositivo.id + "/push") $a.token @{ fcmToken = "prueba-nube-" + [guid]::NewGuid() }) | Out-Null

if ($conConsola) {
    Write-Host "3. Cuenta de consola (Administrador temporal)"
    $admin = NuevaCuenta "Consola Nube"
    if (Revisar "registro de la cuenta de consola" 201 $admin.r) {
        Sql ("UPDATE usuario SET rol = 'ADMINISTRADOR' WHERE id = '" + $admin.id + "'") | Out-Null
        $admin.promovida = $true
        Revisar "la app no deja entrar a la consola" 403 (Pedir POST "/auth/login" $null @{ usuario = $admin.telefono; contrasena = $admin.contrasena; dispositivo = $admin.dispositivo }) | Out-Null
        $r = Pedir POST "/auth/login" $null @{ usuario = $admin.telefono; contrasena = $admin.contrasena }
        Revisar "login de consola sin telefono" 200 $r | Out-Null
        $admin.token = $r.json.token
        Revisar "una cuenta de la app no ve la lista de alertas" 403 (Pedir GET "/alertas" $a.token) | Out-Null
        Revisar "lista de alertas de la consola" 200 (Pedir GET "/alertas?estado=ACTIVAS&limite=5" $admin.token) | Out-Null
        try {
            $wsBase = $base -replace '^http', 'ws'
            $ws = New-Object System.Net.WebSockets.ClientWebSocket
            $ws.ConnectAsync([Uri]("$wsBase/ws/consola?token=" + $admin.token), [Threading.CancellationToken]::None).Wait(15000) | Out-Null
            Comprobar "consola en tiempo real conectada (P16)" ($ws.State -eq "Open") ("estado " + $ws.State)
        } catch {
            $fallas++
            Write-Host ("  FALLA consola en tiempo real: " + $_.Exception.InnerException.Message + ". Revisa que Web sockets este en On en el App Service.") -ForegroundColor Red
            $ws = $null
        }
    }
} else {
    Write-Host "3. Consola: se salta (sin contrasena de la base)"
}

# ---------------------------------------------------------------- 4. alerta directa con red de apoyo

Write-Host "4. Alerta por internet (P4, P5, P6, P8)"
$idContacto = [string][guid]::NewGuid()
if ($telefonoContacto) {
    $contacto = @{ id = $idContacto; nombre = "Contacto de prueba"; telefono = $telefonoContacto; prioridad = 1 }
    Revisar "guardar contacto con tu numero" 200 (Pedir PUT "/contactos/$idContacto" $a.token $contacto) | Out-Null
    Revisar "aviso de contacto agregado (SMS 1)" 202 (Pedir POST "/red-apoyo/avisos" $a.token @{ tipo = "CONTACTO_AGREGADO"; contactoId = $idContacto }) | Out-Null
}
$id1 = [string][guid]::NewGuid()
$alerta1 = Alerta $id1 (Ahora -60)
$r = Pedir POST "/alertas" $a.token $alerta1
Revisar "registrar alerta" 201 $r | Out-Null
$folio1 = $r.json.folio
Comprobar "la alerta trae folio" ($folio1 -like "ALR-*") $folio1
Revisar "reenvio de la misma alerta" 200 (Pedir POST "/alertas" $a.token $alerta1) | Out-Null
Revisar "el mismo id desde otra cuenta" 409 (Pedir POST "/alertas" $b.token $alerta1) | Out-Null
if ($ws) { Comprobar "la consola ve la alerta nueva al instante" (EsperarEnConsola $ws $id1 20) "no llego en 20 s" }
$lote = @{ lecturas = (Lecturas 3 -5) }
$r = Pedir POST "/alertas/$id1/ubicaciones" $a.token $lote
Revisar "ubicacion en vivo (3 lecturas)" 200 $r | Out-Null
Comprobar "entraron 3 lecturas" ($r.json.insertadas -eq 3) ("insertadas " + $r.json.insertadas)
$r = Pedir POST "/alertas/$id1/ubicaciones" $a.token $lote
Comprobar "reenviar las mismas lecturas no las duplica" ($r.codigo -eq 200 -and $r.json.duplicadas -eq 3) ("codigo " + $r.codigo + ", duplicadas " + $r.json.duplicadas)
Revisar "ubicaciones a la alerta de otra persona" 404 (Pedir POST "/alertas/$id1/ubicaciones" $b.token $lote) | Out-Null
Start-Sleep -Seconds 3
$r = Pedir GET "/alertas/$id1" $a.token
Revisar "detalle de la alerta (quien la envio)" 200 $r | Out-Null
Revisar "linea de tiempo (P13)" 200 (Pedir GET "/alertas/$id1/timeline" $a.token) | Out-Null
if ($admin -and $admin.token) {
    $r = Pedir GET "/alertas/$id1/timeline" $admin.token
    Revisar "linea de tiempo en la consola" 200 $r | Out-Null
    Comprobar "la linea de tiempo trae eventos" (@($r.json.eventos).Count -ge 1) ("eventos " + @($r.json.eventos).Count)
}
if ($telefonoContacto) {
    Write-Host ""
    Write-Host "  En tu telefono debio llegar el SMS de la alerta con un enlace. Abrelo: debe mostrar la ubicacion." -ForegroundColor Yellow
    Read-Host "  Presiona Enter cuando lo hayas revisado (si no llega en 1 minuto, presiona Enter igual)" | Out-Null
}
Revisar "cancelar con el PIN" 204 (Cancelar $a.token $id1 $true $a.dispositivo.id) | Out-Null
$r = Pedir GET "/alertas/$id1" $a.token
Comprobar "la alerta quedo CANCELADA" ($r.json.estado -eq "CANCELADA") $r.json.estado
Revisar "ubicaciones despues de cancelar" 409 (Pedir POST "/alertas/$id1/ubicaciones" $a.token @{ lecturas = (Lecturas 1 60) }) | Out-Null
if ($telefonoContacto) {
    Write-Host "  Debe llegarte el SMS de alerta cancelada y el enlace ya no debe abrir." -ForegroundColor Yellow
    Revisar "quitar el contacto (las demas alertas no mandan SMS)" 204 (Pedir DELETE "/contactos/$idContacto" $a.token) | Out-Null
}

# ---------------------------------------------------------------- 5. PIN de coaccion

Write-Host "5. PIN de coaccion (P8)"
$id2 = [string][guid]::NewGuid()
Revisar "registrar alerta" 201 (Pedir POST "/alertas" $a.token (Alerta $id2 (Ahora))) | Out-Null
Revisar "cancelar con el PIN de coaccion" 204 (Cancelar $a.token $id2 $false $a.dispositivo.id) | Out-Null
$r = Pedir GET "/alertas/$id2" $a.token
Comprobar "la persona la ve CANCELADA" ($r.json.estado -eq "CANCELADA") $r.json.estado
Revisar "el telefono sigue mandando la ubicacion en silencio" 200 (Pedir POST "/alertas/$id2/ubicaciones" $a.token @{ lecturas = (Lecturas 2 -1) }) | Out-Null
if ($admin -and $admin.token) {
    $r = Pedir GET "/alertas/$id2" $admin.token
    Comprobar "la consola la ve activa" ($r.json.estado -ne "CANCELADA") $r.json.estado
    Comprobar "la consola ve la marca de coaccion" ($r.json.coaccionDetectada -eq $true)
    Revisar "escalar a mano (P5)" @(200, 409) (Pedir POST "/alertas/$id2/escalar" $admin.token @{ comentario = "Prueba en la nube" }) | Out-Null
} elseif ($conConsola -eq $false) {
    Write-Host "  (sin consola no se revisa que siga activa en el servidor)"
}
Revisar "cancelar de verdad para limpiar" 204 (Cancelar $a.token $id2 $true $a.dispositivo.id) | Out-Null

# ---------------------------------------------------------------- 6. malla

Write-Host "6. Alerta por la malla (RF-14, A-01)"
$id3 = [string][guid]::NewGuid()
$activada3 = Ahora -5
$relevo = @{
    idCortoAlerta = $id3.Substring(0, 8); idCortoUsuario = ($a.id -replace "-", "").Substring(24, 8)
    latitud = 19.5046; longitud = -99.1470; nivelBateria = 70; ttl = 3; saltosMalla = 2; activadaEn = $activada3
}
$r = Pedir POST "/malla/relevos" $b.token $relevo
Revisar "otro telefono entrega el paquete" 201 $r | Out-Null
$idServidor3 = $r.json.id
$folio3 = $r.json.folio
Comprobar "el servidor uso el mismo id corto" ($idServidor3 -and $idServidor3.StartsWith($id3.Substring(0, 8))) $idServidor3
$r = Pedir POST "/malla/relevos" $b.token $relevo
Revisar "el mismo paquete por un segundo telefono" 200 $r | Out-Null
Comprobar "no se duplico" ($r.json.folio -eq $folio3) $r.json.folio
$otro = $relevo.Clone(); $otro.idCortoUsuario = "00000000"
Revisar "paquete de una persona que no existe" 404 (Pedir POST "/malla/relevos" $b.token $otro) | Out-Null
$r = Pedir POST "/alertas" $a.token (Alerta $id3 $activada3)
Revisar "la persona recupera internet y manda su alerta" 200 $r | Out-Null
Comprobar "se reconoce como la misma alerta" ($r.json.folio -eq $folio3) ($r.json.folio + " vs " + $folio3)
Revisar "ubicacion con el id del telefono" 200 (Pedir POST "/alertas/$id3/ubicaciones" $a.token @{ lecturas = (Lecturas 2 -1) }) | Out-Null
Revisar "cancelar con el id del telefono" 204 (Cancelar $a.token $id3 $true $a.dispositivo.id) | Out-Null
$r = Pedir GET "/alertas/$idServidor3" $a.token
Comprobar "la alerta de la malla quedo CANCELADA" ($r.json.estado -eq "CANCELADA") $r.json.estado

# ---------------------------------------------------------------- 7. sincronizacion offline

Write-Host "7. Cola sin senal (P15)"
$id4 = [string][guid]::NewGuid()
$activada4 = Ahora -60
$elemAlerta = @{ tipo = "ALERTA"; id = $id4; creadoEn = $activada4; datos = (Alerta $id4 $activada4) }
$elemUbic = @{ tipo = "UBICACION"; id = [string][guid]::NewGuid(); creadoEn = (Ahora -30); datos = @{ alertaId = $id4; lecturas = (Lecturas 2 -30) } }
$r = Pedir POST "/sync/lote" $a.token @{ dispositivoId = $a.dispositivo.id; elementos = @($elemAlerta, $elemUbic) }
Revisar "mandar la cola" 200 $r | Out-Null
$res = @($r.json.resultados)
$vistos = (@($res | ForEach-Object { $_.resultado }) -join ",")
Comprobar "la alerta entro" ($res.Count -eq 2 -and $res[0].resultado -eq "ACEPTADO") $vistos
Comprobar "las ubicaciones entraron" ($res.Count -eq 2 -and $res[1].resultado -eq "ACEPTADO") $vistos
$r = Pedir POST "/sync/lote" $a.token @{ dispositivoId = $a.dispositivo.id; elementos = @($elemAlerta) }
Comprobar "reenviar la cola no duplica la alerta" ($r.codigo -eq 200 -and @($r.json.resultados)[0].resultado -eq "DUPLICADO")
Revisar "cola con el telefono de otra persona" 403 (Pedir POST "/sync/lote" $a.token @{ dispositivoId = $b.dispositivo.id; elementos = @($elemAlerta) }) | Out-Null
Revisar "cancelar la alerta de la cola" 204 (Cancelar $a.token $id4 $true $a.dispositivo.id) | Out-Null

# ---------------------------------------------------------------- 8. historial, archivos y consola

Write-Host "8. Historial y archivos (P13, P14)"
$r = Pedir GET "/historial?tipo=ALERTA" $a.token
Revisar "historial propio" 200 $r | Out-Null
Comprobar "el historial trae las 4 alertas" (@($r.json.elementos).Count -ge 4) ("elementos " + @($r.json.elementos).Count)
$png = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
$r = PedirArchivo "/archivos/evidencia" $a.token $png "image/png"
Revisar "subir una foto de evidencia" 201 $r | Out-Null
if ($r.codigo -eq 201) {
    $nombre = $r.json.clave.Substring($r.json.clave.LastIndexOf("/") + 1)
    $d = Pedir GET "/archivos/evidencia/$nombre" $a.token
    Revisar "descargar la foto" 200 $d | Out-Null
}
Revisar "subir un tipo no permitido" 415 (PedirArchivo "/archivos/evidencia" $a.token ([Text.Encoding]::UTF8.GetBytes("hola")) "text/plain") | Out-Null
Revisar "una cuenta de la app no publica mapas" 403 (PedirArchivo "/archivos/mapa" $a.token $png "application/zip") | Out-Null

if ($admin -and $admin.token) {
    Write-Host "9. Consola (P13)"
    $hoy = [DateTime]::Now.ToString("yyyy-MM-dd", $inv)
    $hace7 = [DateTime]::Now.AddDays(-7).ToString("yyyy-MM-dd", $inv)
    Revisar "estadisticas" 200 (Pedir GET "/estadisticas/alertas?desde=$hace7&hasta=$hoy" $admin.token) | Out-Null
    $r = Pedir GET "/estadisticas/exportar?desde=$hace7&hasta=$hoy&formato=csv" $admin.token
    Revisar "exportar CSV" 200 $r | Out-Null
    $r = Pedir GET "/bitacora/verificar" $admin.token
    Revisar "verificar la bitacora" 200 $r | Out-Null
    Comprobar "la bitacora esta integra" ($r.json.integra -eq $true) ("primerIdAlterado " + $r.json.primerIdAlterado)
    Revisar "historial de otra persona sin justificacion" 400 (Pedir GET ("/usuarios/" + $a.id + "/historial") $admin.token) | Out-Null
    Revisar "historial de otra persona con justificacion" 200 (Pedir GET ("/usuarios/" + $a.id + "/historial") $admin.token $null @{ "X-Justificacion" = "Prueba en la nube" }) | Out-Null
    Revisar "una cuenta de la app no ve la bitacora" 403 (Pedir GET "/bitacora/verificar" $a.token) | Out-Null
}

} finally {

# ---------------------------------------------------------------- limpieza

Write-Host "10. Limpieza"
if ($ws) { try { $ws.Dispose() } catch {} }
if ($admin -and $admin.promovida) {
    try {
        Sql ("UPDATE usuario SET rol = 'INTEGRANTE' WHERE id = '" + $admin.id + "'") | Out-Null
        Write-Host "  OK    la cuenta de consola regreso a Integrante"
    } catch {
        $fallas++
        Write-Host ("  FALLA no se pudo regresar la cuenta de consola a Integrante. Corre a mano: UPDATE usuario SET rol = 'INTEGRANTE' WHERE id = '" + $admin.id + "'") -ForegroundColor Red
    }
}
foreach ($c in @($a, $b, $admin)) {
    if ($c -and $c.id) {
        $r = Pedir POST "/auth/login" $null @{ usuario = $c.telefono; contrasena = $c.contrasena; dispositivo = $c.dispositivo }
        if ($r.codigo -eq 200) { Revisar "baja de la cuenta de prueba" 204 (Pedir POST "/usuarios/me/baja" $r.json.token @{ contrasena = $c.contrasena }) | Out-Null }
        else { $fallas++; Write-Host ("  FALLA no se pudo entrar para dar de baja la cuenta " + $c.matricula) -ForegroundColor Red }
    }
}
$clavePg = $null

}

Write-Host ""
if ($fallas -eq 0) {
    Write-Host "OK: todo funciona en la nube. Las alertas quedaron canceladas y las cuentas de prueba dadas de baja." -ForegroundColor Green
} else {
    Write-Host ("Hubo " + $fallas + " fallas.") -ForegroundColor Red
    exit 1
}
