# Vuelve Administrador una cuenta que ya existe en la nube, para entrar a la consola web.
# Se corre una sola vez (o cada vez que quieras dar de alta otro Administrador).
#
# Antes: registra la cuenta desde la app con su matricula o numero de empleado. Despues de este script esa cuenta
# ya no entra por la app, solo por la consola. Por eso conviene que sea una cuenta solo para la consola.
#
# Pide la contrasena de la base de Azure. Solo se usa en esta terminal. Necesita Docker prendido con el
# contenedor sendero-postgres.
#
# Correr:   powershell -ExecutionPolicy Bypass -File pruebas-nube\crear-administrador.ps1

param(
    [string]$Contenedor = "sendero-postgres",
    [string]$ConexionBase = "host=sendero-seguro-db.postgres.database.azure.com port=5432 dbname=sendero_seguro user=sendero sslmode=require"
)

$ErrorActionPreference = "Stop"

function Sql($consulta) {
    $ErrorActionPreference = "Continue"
    for ($i = 0; $i -lt 3; $i++) {
        $salida = docker exec -e "PGPASSWORD=$script:clavePg" $Contenedor psql $ConexionBase -v ON_ERROR_STOP=1 -tAc $consulta 2>&1
        if ($LASTEXITCODE -eq 0) { return ($salida | Out-String).Trim() }
        Start-Sleep -Seconds 2
    }
    throw ("psql no pudo conectarse: " + ($salida | Out-String))
}

$matricula = (Read-Host "Matricula o numero de empleado de la cuenta").Trim().ToUpper()
if ($matricula -notmatch '^[A-Z0-9]{3,30}$') { Write-Host "Solo letras y numeros." -ForegroundColor Red; exit 1 }
$seguro = Read-Host "Contrasena de la base de Azure" -AsSecureString
$clavePg = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro))

try {
    $cuenta = Sql ("SELECT nombre || ' | ' || rol || ' | ' || estado FROM usuario WHERE upper(identificador_institucional) = '" + $matricula + "'")
    if (-not $cuenta) { Write-Host "No hay ninguna cuenta con esa matricula. Registrala primero desde la app." -ForegroundColor Red; exit 1 }
    Write-Host ("Cuenta: " + $cuenta)
    if ($cuenta -like "*| ADMINISTRADOR |*") { Write-Host "Ya es Administrador." -ForegroundColor Green; exit 0 }
    $si = (Read-Host "Volverla Administrador? Ya no entrara por la app (s/n)").Trim().ToLower()
    if ($si -ne "s") { Write-Host "No se cambio nada."; exit 0 }
    Sql ("UPDATE operador SET activo = false WHERE usuario_id = (SELECT id FROM usuario WHERE upper(identificador_institucional) = '" + $matricula + "')") | Out-Null
    Sql ("UPDATE usuario SET rol = 'ADMINISTRADOR', actualizado_en = now() WHERE upper(identificador_institucional) = '" + $matricula + "'") | Out-Null
    Sql ("UPDATE sesion SET revocada_en = now() WHERE revocada_en IS NULL AND usuario_id = (SELECT id FROM usuario WHERE upper(identificador_institucional) = '" + $matricula + "')") | Out-Null
    Write-Host "Listo: ya es Administrador. Entra a la consola con su matricula o telefono y su contrasena." -ForegroundColor Green
} finally {
    $clavePg = $null
}
