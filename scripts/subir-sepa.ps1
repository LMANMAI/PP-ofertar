<#
.SYNOPSIS
  Baja el dataset SEPA mas reciente desde tu PC, lo sube a un bucket S3-compatible
  (Cloudflare R2, Backblaze B2, AWS S3) y te da la URL temporal para el backend.

.DESCRIPTION
  El servidor no puede llegar a datos.produccion.gob.ar (bloqueo), asi que el zip
  se baja desde una PC con acceso y se le entrega al backend por SEPA_RESOURCE_URL.
  La URL es "presigned": el bucket puede seguir privado y la URL vence sola.

  Requisitos (una sola vez):
    - AWS CLI instalado (winget install Amazon.AWSCLI) y credenciales del bucket:
        aws configure        (Access Key y Secret del bucket; region: auto para R2)
    - Un bucket creado en tu proveedor.

.PARAMETER Bucket    Nombre del bucket.
.PARAMETER Endpoint  URL S3 del proveedor. R2: https://<accountid>.r2.cloudflarestorage.com
                     B2: https://s3.<region>.backblazeb2.com. Omitir para AWS S3.
.PARAMETER ZipPath   Usar un zip que ya bajaste (omite la descarga).
.PARAMETER Fecha     Fecha del dataset (YYYY-MM-DD). Obligatoria si se usa -ZipPath.
.PARAMETER Dia       Filtra el recurso por dia (por ejemplo martes), como el parametro dia del backend.
.PARAMETER Horas     Vigencia de la URL, en horas (maximo 168 = 7 dias). Por defecto 168.

.EXAMPLE
  .\scripts\subir-sepa.ps1 -Bucket ofertar-sepa -Endpoint https://abc123.r2.cloudflarestorage.com

.EXAMPLE
  .\scripts\subir-sepa.ps1 -Bucket ofertar-sepa -Endpoint https://abc123.r2.cloudflarestorage.com `
      -ZipPath C:\sepa\sepa_martes.zip -Fecha 2026-09-15
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Bucket,
    [string] $Endpoint,
    [string] $ZipPath,
    [string] $Fecha,
    [string] $Dia,
    [ValidateRange(1, 168)] [int] $Horas = 168
)

$ErrorActionPreference = 'Stop'
$CkanUrl = 'https://datos.produccion.gob.ar/api/3/action/package_show?id=sepa-precios'

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
    Fail 'No encuentro el AWS CLI. Instalalo con: winget install Amazon.AWSCLI (y reabri PowerShell).'
}
$endpointArgs = @()
if ($Endpoint) { $endpointArgs = @('--endpoint-url', $Endpoint) }

# -- 1. Conseguir el zip -------------------------------------------
if ($ZipPath) {
    if (-not (Test-Path $ZipPath)) { Fail "No existe $ZipPath" }
    if (-not ($Fecha -match '^\d{4}-\d{2}-\d{2}$')) { Fail 'Con -ZipPath hay que indicar -Fecha YYYY-MM-DD' }
} else {
    Write-Host 'Consultando el dataset SEPA...'
    $pkg = Invoke-RestMethod -Uri $CkanUrl -Headers @{ 'User-Agent' = 'OfertAR/1.0' } -TimeoutSec 60
    $mejor = $null
    foreach ($r in $pkg.result.resources) {
        $esZip = ($r.format -match 'zip') -or ($r.url -match '\.zip$')
        if (-not $r.url -or -not $esZip) { continue }
        if ($Dia) {
            $d = $Dia.ToLower()
            if (-not (("$($r.name) $($r.description)").ToLower().Contains($d))) { continue }
        }
        # Misma regla que el backend: primera fecha YYYY-MM-DD en descripcion, last_modified o url.
        $f = $null
        foreach ($txt in @($r.description, $r.last_modified, $r.url)) {
            if ($txt -match '(\d{4}-\d{2}-\d{2})') { $f = $Matches[1]; break }
        }
        if (-not $f) { $f = '0000-00-00' }
        if (-not $mejor -or $f -gt $mejor.Fecha) {
            $mejor = [pscustomobject]@{ Nombre = $r.name; Fecha = $f; Url = $r.url }
        }
    }
    if (-not $mejor) { Fail 'No encontre ningun zip en el dataset (revisa -Dia).' }
    $Fecha = $mejor.Fecha
    Write-Host "Recurso: $($mejor.Nombre)  fecha $Fecha"
    if ($Fecha -eq '0000-00-00') { Fail 'No pude determinar la fecha del dataset; bajalo a mano y usa -ZipPath con -Fecha.' }

    $ZipPath = Join-Path $env:TEMP "sepa_$Fecha.zip"
    Write-Host "Descargando a $ZipPath (cientos de MB, puede tardar)..."
    & curl.exe -L --fail --retry 3 -A 'OfertAR/1.0' -o $ZipPath $mejor.Url
    if ($LASTEXITCODE -ne 0) { Fail 'La descarga fallo.' }
}

$mb = [math]::Round((Get-Item $ZipPath).Length / 1MB, 1)
if ($mb -lt 1) { Fail "El archivo pesa $mb MB: no parece un zip valido." }
Write-Host "Zip listo: $mb MB"

# -- 2. Subir ------------------------------------------------------
$key = "sepa/sepa_$Fecha.zip"
Write-Host "Subiendo a s3://$Bucket/$key ..."
& aws s3 cp $ZipPath "s3://$Bucket/$key" @endpointArgs
if ($LASTEXITCODE -ne 0) { Fail 'La subida fallo (revisa credenciales, bucket y endpoint).' }

# -- 3. URL temporal -----------------------------------------------
$url = (& aws s3 presign "s3://$Bucket/$key" --expires-in ($Horas * 3600) @endpointArgs).Trim()
if ($LASTEXITCODE -ne 0 -or -not $url) { Fail 'No pude generar la URL temporal.' }

Write-Host ''
Write-Host 'Listo. Cargalo en el Entorno de ofertar-backend, reinicia y lanza POST /sepa/sync:' -ForegroundColor Green
Write-Host ''
Write-Host "SEPA_RESOURCE_URL=$url"
Write-Host "SEPA_RESOURCE_FECHA=$Fecha"
Write-Host 'SEPA_CACHE_DIR=/home/sepa/sepa'
Write-Host '(y sin SEPA_RESOURCE_FILE)'
Write-Host ''
Write-Host "La URL vence en $Horas h. Alcanza con que el backend termine de descargar antes."
try { Set-Clipboard -Value "SEPA_RESOURCE_URL=$url`nSEPA_RESOURCE_FECHA=$Fecha" ; Write-Host 'Las dos primeras lineas quedaron en el portapapeles.' } catch {}
