# Prueba en la nube de las rutas del Modulo 1: cuentas (P1), contactos (P2) y cancelacion (P8).
# Crea una cuenta nueva de prueba, inicia y cierra sesion, guarda y borra un contacto, y al final pide la baja
# de la cuenta. No manda SMS ni push. No necesita az login ni ninguna llave.
# Correr:   powershell -ExecutionPolicy Bypass -File pruebas-nube\probar-cuentas.ps1

$ErrorActionPreference = "Stop"
$base = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1"
$fallas = 0

# Hace la peticion y regresa el codigo HTTP y el JSON, tambien cuando el servidor responde un error.
function Pedir($metodo, $ruta, $token = $null, $cuerpo = $null) {
    $encabezados = @{}
    if ($token) { $encabezados["Authorization"] = "Bearer $token" }
    $p = @{ Method = $metodo; Uri = "$base$ruta"; Headers = $encabezados; UseBasicParsing = $true }
    if ($null -ne $cuerpo) {
        $p["ContentType"] = "application/json; charset=utf-8"
        $p["Body"] = [Text.Encoding]::UTF8.GetBytes(($cuerpo | ConvertTo-Json -Depth 5 -Compress))
    }
    try {
        $r = Invoke-WebRequest @p
        $codigo = [int]$r.StatusCode
        $texto = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        # El servidor respondio con un error (4xx o 5xx). Sin respuesta (sin red) se detiene aqui.
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
    if ($texto) { $json = $texto | ConvertFrom-Json }
    return @{ codigo = $codigo; json = $json }
}

function Revisar($nombre, $esperado, $r) {
    if ($r.codigo -eq $esperado) {
        Write-Host ("  OK    " + $nombre + " (" + $r.codigo + ")")
    } else {
        $script:fallas++
        $extra = ""
        if ($r.json -and $r.json.codigo) { $extra = " " + $r.json.codigo + ": " + $r.json.mensaje }
        Write-Host ("  FALLA " + $nombre + ": se esperaba " + $esperado + " y llego " + $r.codigo + $extra) -ForegroundColor Red
    }
}

$aleatorio = New-Object Random
$numero = [string]$aleatorio.Next(10000000, 99999999)
$telefono = "55" + $numero
$matricula = "NUBE" + $numero
$contrasena = "Nube" + [string]$aleatorio.Next(100000, 999999)
$dispositivo = @{ id = [string][guid]::NewGuid(); modelo = "Prueba nube"; androidApi = 34; soportaWifiAware = $false }

Write-Host "1. Catalogos del registro"
$aviso = Pedir GET "/avisos-privacidad/vigente"; Revisar "aviso de privacidad" 200 $aviso
$inst = Pedir GET "/instituciones"; Revisar "instituciones" 200 $inst
$ipn = @($inst.json | Where-Object { $_.siglas -eq "IPN" })[0]
if (-not $ipn) { $ipn = @($inst.json)[0] }
$esc = Pedir GET ("/instituciones/" + $ipn.id + "/escuelas"); Revisar "escuelas" 200 $esc
$escuela = @($esc.json)[0]

Write-Host "2. Registro y perfil (cuenta de prueba nueva)"
$registro = @{
    nombre = "Prueba Nube"; telefonoMovil = $telefono; identificadorInstitucional = $matricula; tipoComunidad = "ALUMNO"
    escuelaId = $escuela.id; carreraAdscripcion = "Prueba en la nube"; contrasena = $contrasena; aceptaEntrenamientoIa = $false
    consentimiento = @{ avisoId = $aviso.json.id }; dispositivo = $dispositivo
}
$r = Pedir POST "/auth/registro" $null $registro; Revisar "registro" 201 $r
$token = $r.json.token
Revisar "registro repetido" 409 (Pedir POST "/auth/registro" $null $registro)
Revisar "ver perfil" 200 (Pedir GET "/usuarios/me" $token)
Revisar "cambiar perfil" 200 (Pedir PATCH "/usuarios/me" $token @{ carreraAdscripcion = "Prueba en la nube 2" })

Write-Host "3. Login"
$mala = Pedir POST "/auth/login" $null @{ usuario = $telefono; contrasena = "Incorrecta1"; dispositivo = $dispositivo }
Revisar "contrasena mala" 401 $mala
if ($mala.json.detalle.intentosRestantes -ne 4) { $fallas++; Write-Host "  FALLA intentosRestantes deberia ser 4" -ForegroundColor Red }
$r = Pedir POST "/auth/login" $null @{ usuario = $matricula.ToLower(); contrasena = $contrasena; dispositivo = $dispositivo }
Revisar "login con matricula" 200 $r
$token2 = $r.json.token

Write-Host "4. Contactos (no se manda aviso)"
$idContacto = [string][guid]::NewGuid()
$contacto = @{ id = $idContacto; nombre = "Contacto de prueba"; telefono = "5500000001"; prioridad = 1 }
Revisar "guardar contacto" 200 (Pedir PUT "/contactos/$idContacto" $token2 $contacto)
$lista = Pedir GET "/contactos" $token2; Revisar "listar contactos" 200 $lista
if (@($lista.json).Count -ne 1) { $fallas++; Write-Host "  FALLA deberia haber 1 contacto" -ForegroundColor Red }
Revisar "borrar contacto" 204 (Pedir DELETE "/contactos/$idContacto" $token2)
Revisar "circulo (ninguno)" 200 (Pedir GET "/circulos/mio" $token2)

Write-Host "5. Cancelacion"
$cancelar = @{ metodo = "PIN"; exitoso = $true; motivo = "FALSA_ALARMA" }
Revisar "alerta que no existe" 404 (Pedir POST ("/alertas/" + [guid]::NewGuid() + "/cancelaciones") $token2 $cancelar)

Write-Host "6. Cerrar sesion y baja"
Revisar "logout" 204 (Pedir POST "/auth/logout" $token2)
Revisar "token cerrado ya no sirve" 401 (Pedir GET "/usuarios/me" $token2)
Revisar "baja con contrasena mala" 401 (Pedir POST "/usuarios/me/baja" $token @{ contrasena = "Incorrecta1" })
Revisar "baja" 204 (Pedir POST "/usuarios/me/baja" $token @{ contrasena = $contrasena })
Revisar "login despues de la baja" 403 (Pedir POST "/auth/login" $null @{ usuario = $telefono; contrasena = $contrasena; dispositivo = $dispositivo })

Write-Host ""
if ($fallas -eq 0) {
    Write-Host "OK: las rutas del Modulo 1 funcionan en la nube. La cuenta de prueba quedo dada de baja." -ForegroundColor Green
} else {
    Write-Host ("Hubo " + $fallas + " fallas.") -ForegroundColor Red
    exit 1
}
