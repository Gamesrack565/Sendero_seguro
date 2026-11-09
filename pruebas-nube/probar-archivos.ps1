# Prueba en la nube de la subida y descarga de archivos (POST y GET /archivos).
# Sube una imagen PNG de 1x1 como evidencia, la vuelve a bajar y compara que sea igual.
# Usa un JWT de prueba firmado aqui mismo con JWT_SECRETO, que se lee de Azure y no se imprime ni se guarda.
# Antes: az login.   Correr:   powershell -ExecutionPolicy Bypass -File pruebas-nube\probar-archivos.ps1

$ErrorActionPreference = "Stop"
$base = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1"

function B64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

Write-Host "Leyendo JWT_SECRETO de Azure (no se muestra)..."
$secreto = az webapp config appsettings list --resource-group sendero-seguro --name sendero-seguro --query "[?name=='JWT_SECRETO'].value | [0]" -o tsv
if (-not $secreto) { throw "No se pudo leer JWT_SECRETO. Revisa que hiciste az login." }

# JWT de 10 minutos para una cuenta de prueba con rol INTEGRANTE.
$exp = [DateTimeOffset]::UtcNow.AddMinutes(10).ToUnixTimeSeconds()
$encabezado = B64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
$datos = '{"iss":"sendero-seguro","aud":"sendero-seguro-api","sub":"' + [guid]::NewGuid() + '","rol":"INTEGRANTE","exp":' + $exp + '}'
$cuerpo = B64Url ([Text.Encoding]::UTF8.GetBytes($datos))
$hmac = New-Object System.Security.Cryptography.HMACSHA256 (, [Text.Encoding]::UTF8.GetBytes($secreto))
$firma = B64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$encabezado.$cuerpo")))
$jwt = "$encabezado.$cuerpo.$firma"
$secreto = $null
$encabezados = @{ Authorization = "Bearer $jwt" }

# Imagen PNG de 1x1 pixel.
$png = [Convert]::FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
$esperado = (New-Object System.Security.Cryptography.SHA256Managed).ComputeHash($png) | ForEach-Object { $_.ToString("x2") }
$esperado = $esperado -join ""

Write-Host "Subiendo una imagen de prueba..."
$r = Invoke-RestMethod -Method Post -Uri "$base/archivos/evidencia" -Headers $encabezados -ContentType "image/png" -Body $png
Write-Host ("  clave: " + $r.clave)
Write-Host ("  tamano: " + $r.tamanoBytes + " bytes")
if ($r.hashSha256 -ne $esperado) { throw "El SHA-256 que respondio el servidor no coincide." }

Write-Host "Bajandola otra vez..."
$archivo = Join-Path $env:TEMP "sendero-prueba-archivo.png"
Invoke-WebRequest -Uri ("$base" + $r.url) -Headers $encabezados -OutFile $archivo
$bajado = [IO.File]::ReadAllBytes($archivo)
Remove-Item $archivo
if ([Convert]::ToBase64String($bajado) -ne [Convert]::ToBase64String($png)) { throw "El archivo bajado no es igual al que se subio." }

Write-Host ""
Write-Host "OK: el archivo se guardo en el almacen de la nube y se bajo igual."
