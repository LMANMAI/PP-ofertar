<#
.SYNOPSIS
    Baja el dataset SEPA desde una conexión argentina y lo deja en el VPS.

.DESCRIPTION
    datos.produccion.gob.ar le responde 403 a la IP del VPS (está en Chile),
    pero no a una conexión residencial argentina. Este script corre en un
    equipo de acá, baja el zip más reciente y lo sube por SSH a una carpeta
    del VPS que el backend vigila (SEPA_RESOURCE_DIR). El backend saca la fecha
    de adentro del zip, así que no hay que tocar ninguna variable a mano.

    Está pensado para correr cada hora desde el Programador de tareas
    (instalar-tarea.ps1). Cuando no hay nada nuevo sólo hace una consulta chica
    a la API y termina: los ~300 MB se bajan una vez por dataset.

    Códigos de salida:
      0  subido, o nada nuevo que subir
      1  error de configuración (no se arregla solo: hay que revisar el .psd1)
      2  error transitorio (sitio caído, red, SSH): la próxima pasada reintenta

.PARAMETER Config
    Ruta del archivo de configuración. Por defecto, sepa-relay.config.psd1 al
    lado de este script (copiar sepa-relay.config.example.psd1).

.PARAMETER ZipLocal
    Usar un zip ya bajado en vez de consultar el sitio. Sirve para probar la
    subida sin depender de que datos.produccion.gob.ar esté arriba.

.PARAMETER SinSubir
    Hace todo menos la subida: para probar la descarga y la validación.

.PARAMETER Forzar
    Sube aunque ese dataset ya se haya subido.

.EXAMPLE
    .\sepa-relay.ps1 -ZipLocal "$env:USERPROFILE\Downloads\sepa_martes.zip" -SinSubir
#>
[CmdletBinding()]
param(
    [string]$Config,
    [string]$ZipLocal,
    [switch]$SinSubir,
    [switch]$Forzar
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'

# El valor por defecto va acá y no en param(): en Windows PowerShell 5.1,
# $PSScriptRoot todavía está vacía cuando se evalúan los valores por defecto
# de los parámetros, y Join-Path falla con "cadena vacía".
if (-not $Config) {
    $Config = Join-Path $PSScriptRoot 'sepa-relay.config.psd1'
}

$script:ArchivoLog = $null

function Write-Log([string]$Mensaje) {
    $linea = '{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Mensaje
    Write-Host $linea
    if ($script:ArchivoLog) {
        Add-Content -LiteralPath $script:ArchivoLog -Value $linea -Encoding UTF8
    }
}

# Un error de configuración no se arregla reintentando: sale con 1 para que se
# distinga en el historial de la tarea de un sitio caído (2).
class ErrorDeConfiguracion : System.Exception {
    ErrorDeConfiguracion([string]$m) : base($m) {}
}

function Get-Propiedad($Objeto, [string]$Nombre) {
    # Con StrictMode, leer una propiedad que el JSON no trae es un error; la
    # API de CKAN no siempre manda description o last_modified.
    if ($null -eq $Objeto) { return $null }
    $p = $Objeto.PSObject.Properties[$Nombre]
    if ($null -eq $p) { return $null }
    return $p.Value
}

function Read-Configuracion([string]$Ruta) {
    if (-not (Test-Path -LiteralPath $Ruta -PathType Leaf)) {
        throw [ErrorDeConfiguracion]::new("No existe la configuración $Ruta. Copiá sepa-relay.config.example.psd1 como sepa-relay.config.psd1 y completala.")
    }
    $c = Import-PowerShellDataFile -LiteralPath $Ruta
    $valores = @{
        VpsHost          = [string]$c['VpsHost']
        VpsUsuario       = [string]$c['VpsUsuario']
        Puerto           = if ($c.ContainsKey('Puerto')) { [int]$c['Puerto'] } else { 22 }
        ClaveSsh         = [Environment]::ExpandEnvironmentVariables([string]$c['ClaveSsh'])
        CarpetaRemota    = [string]$c['CarpetaRemota']
        ConservarRemotos = if ($c.ContainsKey('ConservarRemotos')) { [int]$c['ConservarRemotos'] } else { 2 }
        CarpetaLocal     = if ($c.ContainsKey('CarpetaLocal')) { [Environment]::ExpandEnvironmentVariables([string]$c['CarpetaLocal']) } else { Join-Path $env:LOCALAPPDATA 'ofertar-sepa' }
        CkanUrl          = if ($c.ContainsKey('CkanUrl')) { [string]$c['CkanUrl'] } else { 'https://datos.produccion.gob.ar/api/3/action/package_show?id=sepa-precios' }
    }
    foreach ($k in 'VpsHost', 'VpsUsuario', 'ClaveSsh', 'CarpetaRemota') {
        if ([string]::IsNullOrWhiteSpace($valores[$k])) {
            throw [ErrorDeConfiguracion]::new("Falta $k en $Ruta")
        }
    }
    # La carpeta remota va dentro de un comando de shell en el VPS: sólo se
    # aceptan caracteres que no necesitan escaparse.
    if ($valores.CarpetaRemota -notmatch '^/[A-Za-z0-9._/-]+$') {
        throw [ErrorDeConfiguracion]::new("CarpetaRemota tiene que ser una ruta absoluta sin espacios ni caracteres especiales: $($valores.CarpetaRemota)")
    }
    if ($valores.VpsUsuario -notmatch '^[A-Za-z0-9._-]+$' -or $valores.VpsHost -notmatch '^[A-Za-z0-9.-]+$') {
        throw [ErrorDeConfiguracion]::new('VpsUsuario o VpsHost tienen caracteres inválidos')
    }
    if ($valores.ConservarRemotos -lt 1) {
        throw [ErrorDeConfiguracion]::new('ConservarRemotos tiene que ser 1 o más: con 0 se borraría el zip recién subido')
    }
    return $valores
}

function Get-PrimeraFecha([string[]]$Textos) {
    # La misma regla que SepaService.extractDate, para que el relay y el
    # backend elijan el mismo recurso de CKAN.
    foreach ($t in $Textos) {
        if ($t) {
            $m = [regex]::Match($t, '(\d{4}-\d{2}-\d{2})')
            if ($m.Success) { return $m.Groups[1].Value }
        }
    }
    return '0000-00-00'
}

function Select-RecursoSepa($Paquete) {
    # Igual que SepaService.resolveResource sin día: entre los recursos zip,
    # el de fecha más reciente; a igual fecha, el primero.
    $mejor = $null
    foreach ($r in @(Get-Propiedad (Get-Propiedad $Paquete 'result') 'resources')) {
        $url = [string](Get-Propiedad $r 'url')
        $formato = ([string](Get-Propiedad $r 'format')).ToLowerInvariant()
        if (-not $url) { continue }
        if (-not ($formato.Contains('zip') -or $url.ToLowerInvariant().EndsWith('.zip'))) { continue }
        $modificado = [string](Get-Propiedad $r 'last_modified')
        $candidato = [pscustomobject]@{
            Nombre     = [string](Get-Propiedad $r 'name')
            Fecha      = Get-PrimeraFecha @([string](Get-Propiedad $r 'description'), $modificado, $url)
            Url        = $url
            Modificado = $modificado
        }
        if ($null -eq $mejor -or [string]::CompareOrdinal($candidato.Fecha, $mejor.Fecha) -gt 0) {
            $mejor = $candidato
        }
    }
    return $mejor
}

function Get-FechaDelZip([string]$Ruta) {
    # La misma regla que SepaService.fechaDelZip: manda la carpeta YYYY-MM-DD/
    # con la que SEPA arma el zip; si no hay, la fecha de los zips internos
    # siempre que sea una sola. Sin zips internos no es un dataset SEPA.
    if (-not (Test-Path -LiteralPath $Ruta -PathType Leaf)) { return $null }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $carpetas = New-Object 'System.Collections.Generic.SortedSet[string]'
    $internos = New-Object 'System.Collections.Generic.SortedSet[string]'
    $zipsInternos = 0
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($Ruta)
        try {
            foreach ($e in $zip.Entries) {
                $nombre = $e.FullName -replace '\\', '/'
                $m = [regex]::Match($nombre, '^(\d{4}-\d{2}-\d{2})/')
                if ($m.Success) { [void]$carpetas.Add($m.Groups[1].Value) }
                if ($nombre.ToLowerInvariant().EndsWith('.zip')) {
                    $zipsInternos++
                    $hoja = $nombre.Substring($nombre.LastIndexOf('/') + 1)
                    $mi = [regex]::Match($hoja, '_(\d{4}-\d{2}-\d{2})_')
                    if ($mi.Success) { [void]$internos.Add($mi.Groups[1].Value) }
                }
            }
        } finally {
            $zip.Dispose()
        }
    } catch {
        return $null
    }
    if ($zipsInternos -eq 0) { return $null }
    if ($carpetas.Count -eq 1) {
        $fecha = $carpetas.Min
    } elseif ($carpetas.Count -eq 0 -and $internos.Count -eq 1) {
        $fecha = $internos.Min
    } else {
        return $null
    }
    $d = [datetime]::MinValue
    $ok = [datetime]::TryParseExact($fecha, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::None, [ref]$d)
    if ($ok) { return $fecha }
    return $null
}

function Get-ComandoRemoto([string]$Carpeta, [string]$Fecha, [int]$Conservar) {
    # Se sube a un ".part" y se renombra acá: mv en el mismo disco es atómico,
    # así que el backend, que ignora lo que no termina en .zip, nunca ve un
    # archivo a medio subir. Después se dejan los $Conservar más nuevos: los
    # nombres llevan la fecha, así que el orden alfabético inverso es el
    # cronológico.
    $final = "sepa_$Fecha.zip"
    return ("set -e; cd '{0}'; mv -f '{1}.part' '{1}'; " +
        "ls -1 sepa_????-??-??.zip | sort -r | tail -n +{2} | xargs -r rm -f --") -f $Carpeta, $final, ($Conservar + 1)
}

function Invoke-Programa([string]$Programa, [string[]]$Argumentos, [string]$Que) {
    # A Out-Host y no al pipeline: lo que imprima el programa se mezclaría con
    # el valor que devuelve Invoke-Relay, que es el código de salida.
    & $Programa @Argumentos | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "$Que falló (código $LASTEXITCODE)"
    }
}

function Save-Descarga([string]$Url, [string]$Destino) {
    # curl.exe y no "curl": en Windows PowerShell "curl" es un alias de
    # Invoke-WebRequest, que no reanuda ni reintenta. Se baja a un .part y se
    # reanuda si una pasada anterior quedó a medias (-C -).
    $parte = "$Destino.part"
    $base = @('-fL', '-sS', '--retry', '5', '--retry-delay', '30', '--retry-connrefused',
        '--connect-timeout', '30', '-A', 'OfertAR-relay/1.0', '-o', $parte)
    & curl.exe @($base + @('-C', '-', $Url)) | Out-Host
    if ($LASTEXITCODE -eq 33) {
        # El servidor no acepta reanudar: se empieza de cero.
        Remove-Item -LiteralPath $parte -ErrorAction SilentlyContinue
        & curl.exe @($base + @($Url)) | Out-Host
    }
    if ($LASTEXITCODE -ne 0) {
        throw "La descarga falló (curl terminó con código $LASTEXITCODE)"
    }
    Move-Item -LiteralPath $parte -Destination $Destino -Force
}

function Read-Estado([string]$Ruta) {
    if (Test-Path -LiteralPath $Ruta -PathType Leaf) {
        try { return Get-Content -LiteralPath $Ruta -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
    }
    return $null
}

function Invoke-Relay {
    $cfg = Read-Configuracion $Config
    New-Item -ItemType Directory -Force -Path $cfg.CarpetaLocal | Out-Null
    $script:ArchivoLog = Join-Path $cfg.CarpetaLocal 'relay.log'
    if ((Test-Path -LiteralPath $script:ArchivoLog) -and (Get-Item -LiteralPath $script:ArchivoLog).Length -gt 5MB) {
        Move-Item -LiteralPath $script:ArchivoLog -Destination "$($script:ArchivoLog).1" -Force
    }
    $rutaEstado = Join-Path $cfg.CarpetaLocal 'estado.json'
    $estado = Read-Estado $rutaEstado
    $fechaSubida = [string](Get-Propiedad $estado 'Fecha')

    $bajado = $null
    if ($ZipLocal) {
        $zip = (Resolve-Path -LiteralPath $ZipLocal).Path
        $recurso = [pscustomobject]@{ Nombre = '(local)'; Fecha = ''; Url = $zip; Modificado = '' }
        Write-Log "Usando el zip local $zip"
    } else {
        # Windows PowerShell 5.1 no habilita TLS 1.2 por defecto.
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $paquete = $null
        for ($intento = 1; $intento -le 3 -and $null -eq $paquete; $intento++) {
            try {
                $paquete = Invoke-RestMethod -Uri $cfg.CkanUrl -TimeoutSec 60 -UserAgent 'OfertAR-relay/1.0'
            } catch {
                Write-Log "Intento $intento de consultar la API de SEPA falló: $($_.Exception.Message)"
                if ($intento -lt 3) { Start-Sleep -Seconds (20 * $intento) }
            }
        }
        if ($null -eq $paquete) { throw 'La API de SEPA no respondió (¿sitio caído?)' }
        $recurso = Select-RecursoSepa $paquete
        if ($null -eq $recurso) { throw 'La API de SEPA no devolvió ningún recurso zip' }
        Write-Log "Recurso más reciente: '$($recurso.Nombre)' ($($recurso.Fecha)) modificado $($recurso.Modificado)"

        # Mismo recurso y misma modificación que lo ya subido: nada que bajar.
        # Así la pasada horaria cuesta una consulta a la API y no 300 MB.
        if (-not $Forzar -and $estado -and
            [string](Get-Propiedad $estado 'Url') -eq $recurso.Url -and
            [string](Get-Propiedad $estado 'Modificado') -eq $recurso.Modificado -and $recurso.Modificado) {
            Write-Log "Sin cambios desde la última subida ($fechaSubida). Nada que hacer."
            return 0
        }
        $bajado = Join-Path $cfg.CarpetaLocal 'descarga.zip'
        Write-Log "Bajando $($recurso.Url)..."
        Save-Descarga $recurso.Url $bajado
        $zip = $bajado
        Write-Log ('Bajado: {0:N0} MB' -f ((Get-Item -LiteralPath $zip).Length / 1MB))
    }

    $fecha = Get-FechaDelZip $zip
    if (-not $fecha) {
        if ($bajado) { Remove-Item -LiteralPath $bajado -ErrorAction SilentlyContinue }
        throw 'El archivo no es un dataset SEPA legible (¿descarga cortada o el sitio devolvió otra cosa?)'
    }
    Write-Log "Fecha del dataset (leída del zip): $fecha"

    if (-not $Forzar -and $fechaSubida -and [string]::CompareOrdinal($fecha, $fechaSubida) -le 0) {
        Write-Log "El dataset $fecha no es más nuevo que el ya subido ($fechaSubida). No se sube."
        if (-not $ZipLocal) {
            [pscustomobject]@{ Url = $recurso.Url; Modificado = $recurso.Modificado; Fecha = $fechaSubida; Subido = (Get-Date -Format o) } |
                ConvertTo-Json | Set-Content -LiteralPath $rutaEstado -Encoding UTF8
        }
        if ($bajado) { Remove-Item -LiteralPath $bajado -ErrorAction SilentlyContinue }
        return 0
    }

    if ($SinSubir) {
        Write-Log "Listo sin subir (-SinSubir): el dataset $fecha está en $zip"
        return 0
    }

    $destino = "$($cfg.VpsUsuario)@$($cfg.VpsHost)"
    $opciones = @('-i', $cfg.ClaveSsh, '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=30')
    $remotoParte = "$($cfg.CarpetaRemota)/sepa_$fecha.zip.part"
    Write-Log "Subiendo a ${destino}:$remotoParte ..."
    Invoke-Programa 'scp.exe' (@('-q', '-P', [string]$cfg.Puerto) + $opciones + @($zip, "${destino}:$remotoParte")) 'La subida por scp'
    Invoke-Programa 'ssh.exe' (@('-p', [string]$cfg.Puerto) + $opciones + @($destino, (Get-ComandoRemoto $cfg.CarpetaRemota $fecha $cfg.ConservarRemotos))) 'El renombrado en el VPS'
    Write-Log "Subido: $($cfg.CarpetaRemota)/sepa_$fecha.zip"

    if (-not $ZipLocal) {
        [pscustomobject]@{ Url = $recurso.Url; Modificado = $recurso.Modificado; Fecha = $fecha; Subido = (Get-Date -Format o) } |
            ConvertTo-Json | Set-Content -LiteralPath $rutaEstado -Encoding UTF8
    }
    if ($bajado) { Remove-Item -LiteralPath $bajado -ErrorAction SilentlyContinue }
    return 0
}

# Al hacer dot-source (". .\sepa-relay.ps1") sólo se cargan las funciones: es
# lo que usa probar-sepa-relay.ps1 para verificarlas sin bajar ni subir nada.
if ($MyInvocation.InvocationName -ne '.') {
    try {
        exit (Invoke-Relay)
    } catch [ErrorDeConfiguracion] {
        Write-Log "ERROR de configuración: $($_.Exception.Message)"
        exit 1
    } catch {
        Write-Log "ERROR (se reintenta en la próxima pasada): $($_.Exception.Message)"
        exit 2
    }
}
