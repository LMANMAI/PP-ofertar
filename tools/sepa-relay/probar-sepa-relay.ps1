<#
.SYNOPSIS
    Verifica sepa-relay.ps1 sin bajar nada del sitio ni subir nada al VPS.

.DESCRIPTION
    powershell -ExecutionPolicy Bypass -File .\probar-sepa-relay.ps1

    Las reglas de fecha y de elección del recurso tienen que ser las mismas que
    las del backend (SepaService): si el relay y el backend no coincidieran en
    qué fecha tiene un zip, el backend podría ignorar lo que el relay acaba de
    subir. Con SEPA_TEST_ZIP apuntando a un zip real, lo prueba también.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'sepa-relay.ps1')
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:fallas = 0
function Test-Caso([string]$Nombre, [scriptblock]$Cuerpo) {
    try {
        & $Cuerpo
        Write-Host "  ok   $Nombre"
    } catch {
        $script:fallas++
        Write-Host "  FAIL $Nombre"
        Write-Host "       $($_.Exception.Message)"
    }
}
function Assert-Igual($Esperado, $Obtenido, [string]$Que = '') {
    if ($Esperado -ne $Obtenido) { throw "esperaba '$Esperado' y obtuve '$Obtenido' $Que" }
}

$tmp = Join-Path ([IO.Path]::GetTempPath()) ('sepa-relay-prueba-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null

function New-Zip([string]$Nombre, [string[]]$Entradas) {
    $ruta = Join-Path $tmp $Nombre
    $fs = [IO.File]::Create($ruta)
    try {
        $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($e in $Entradas) {
                $entrada = $zip.CreateEntry($e)
                if (-not $e.EndsWith('/')) {
                    $w = New-Object IO.StreamWriter($entrada.Open())
                    try { $w.Write('x') } finally { $w.Dispose() }
                }
            }
        } finally { $zip.Dispose() }
    } finally { $fs.Dispose() }
    return $ruta
}
function New-ZipSepa([string]$Nombre, [string]$Fecha) {
    return New-Zip $Nombre @("$Fecha/", "$Fecha/sepa_1_comercio-sepa-12_${Fecha}_09-05-10.zip", "$Fecha/sepa_2_comercio-sepa-11_${Fecha}_01-05-08.zip")
}

try {
    Write-Host 'Fecha leída de adentro del zip (misma regla que SepaService.fechaDelZip)'

    Test-Caso 'un zip como los del sitio: manda la carpeta YYYY-MM-DD/' {
        Assert-Igual '2026-09-15' (Get-FechaDelZip (New-ZipSepa 'sepa_martes.zip' '2026-09-15'))
    }
    Test-Caso 'manda la carpeta aunque un interno sea del día siguiente' {
        $z = New-Zip 'a.zip' @('2026-09-15/', '2026-09-15/sepa_1_comercio-sepa-12_2026-09-15_23-55-00.zip', '2026-09-15/sepa_2_comercio-sepa-11_2026-09-16_00-10-00.zip')
        Assert-Igual '2026-09-15' (Get-FechaDelZip $z)
    }
    Test-Caso 'sin carpeta y con una sola fecha en los internos, vale ésa' {
        $z = New-Zip 'b.zip' @('sepa_1_comercio-sepa-12_2026-09-15_09-05-10.zip', 'sepa_2_comercio-sepa-11_2026-09-15_01-05-08.zip')
        Assert-Igual '2026-09-15' (Get-FechaDelZip $z)
    }
    Test-Caso 'sin carpeta y con fechas distintas no se adivina' {
        $z = New-Zip 'c.zip' @('sepa_1_comercio-sepa-12_2026-09-15_09-05-10.zip', 'sepa_2_comercio-sepa-11_2026-09-16_01-05-08.zip')
        Assert-Igual $null (Get-FechaDelZip $z)
    }
    Test-Caso 'sin zips internos no es un dataset SEPA' {
        Assert-Igual $null (Get-FechaDelZip (New-Zip 'd.zip' @('2026-09-15/', '2026-09-15/leeme.txt')))
    }
    Test-Caso 'un archivo que no es zip, o uno cortado, da null sin excepción' {
        $falso = Join-Path $tmp 'falso.zip'; Set-Content -LiteralPath $falso -Value 'no soy un zip'
        Assert-Igual $null (Get-FechaDelZip $falso)
        $entero = New-ZipSepa 'entero.zip' '2026-09-15'
        $bytes = [IO.File]::ReadAllBytes($entero)
        $cortado = Join-Path $tmp 'cortado.zip'
        [IO.File]::WriteAllBytes($cortado, $bytes[0..([int]($bytes.Length / 2))])
        Assert-Igual $null (Get-FechaDelZip $cortado)
    }
    if ($env:SEPA_TEST_ZIP -and (Test-Path -LiteralPath $env:SEPA_TEST_ZIP)) {
        Test-Caso "el zip real ($(Split-Path -Leaf $env:SEPA_TEST_ZIP)) dice su fecha" {
            $f = Get-FechaDelZip $env:SEPA_TEST_ZIP
            if ($f -notmatch '^\d{4}-\d{2}-\d{2}$') { throw "fecha inválida: '$f'" }
            Write-Host "       fecha: $f"
        }
    }

    Write-Host "`nElección del recurso de CKAN (misma regla que SepaService.resolveResource)"

    $paquete = '{"result":{"resources":[
        {"name":"sepa_lunes","format":"ZIP","description":"Precios del 2026-09-14","last_modified":"2026-09-14T09:00:00","url":"https://x/sepa_lunes.zip"},
        {"name":"sepa_martes","format":"ZIP","description":"Precios del 2026-09-15","last_modified":"2026-09-15T09:00:00","url":"https://x/sepa_martes.zip"},
        {"name":"leeme","format":"PDF","description":"2026-09-30","url":"https://x/leeme.pdf"},
        {"name":"sin descripcion","format":"","last_modified":"2026-09-13T09:00:00","url":"https://x/sepa_domingo.zip"},
        {"name":"sin url","format":"ZIP","description":"2026-09-29"}
    ]}}' | ConvertFrom-Json

    Test-Caso 'elige el zip de fecha más reciente' {
        $r = Select-RecursoSepa $paquete
        Assert-Igual 'https://x/sepa_martes.zip' $r.Url
        Assert-Igual '2026-09-15' $r.Fecha
    }
    Test-Caso 'ignora lo que no es zip y lo que no tiene url, aunque su fecha sea mayor' {
        $r = Select-RecursoSepa $paquete
        if ($r.Url -like '*.pdf' -or -not $r.Url) { throw "eligió $($r.Url)" }
    }
    Test-Caso 'un recurso sin description toma la fecha de last_modified sin romper' {
        $solo = '{"result":{"resources":[{"name":"d","format":"","last_modified":"2026-09-13T09:00:00","url":"https://x/sepa_domingo.zip"}]}}' | ConvertFrom-Json
        Assert-Igual '2026-09-13' (Select-RecursoSepa $solo).Fecha
    }
    Test-Caso 'sin recursos zip devuelve null' {
        Assert-Igual $null (Select-RecursoSepa ('{"result":{"resources":[]}}' | ConvertFrom-Json))
    }

    Write-Host "`nConfiguración"

    function New-Config([hashtable]$Valores) {
        $ruta = Join-Path $tmp ('cfg-' + [guid]::NewGuid().ToString('N') + '.psd1')
        $lineas = foreach ($k in $Valores.Keys) {
            $v = $Valores[$k]
            if ($v -is [int]) { "    $k = $v" } else { "    $k = '$v'" }
        }
        Set-Content -LiteralPath $ruta -Value (@('@{') + $lineas + @('}')) -Encoding UTF8
        return $ruta
    }
    $valida = @{ VpsHost = 'vps.ejemplo.com'; VpsUsuario = 'sepa'; ClaveSsh = '%USERPROFILE%\.ssh\ofertar_sepa'; CarpetaRemota = '/opt/ofertar/sepa-incoming'; CarpetaLocal = (Join-Path $tmp 'local') }

    Test-Caso 'una configuración válida se lee y expande %USERPROFILE%' {
        $c = Read-Configuracion (New-Config $valida)
        Assert-Igual (Join-Path $env:USERPROFILE '.ssh\ofertar_sepa') $c.ClaveSsh
        Assert-Igual 2 $c.ConservarRemotos
        Assert-Igual 22 $c.Puerto
    }
    foreach ($malo in @(
            @{ Que = 'una carpeta remota con ; (iría dentro de un comando de shell)'; Clave = 'CarpetaRemota'; Valor = '/opt/x; rm -rf ~' },
            @{ Que = 'una carpeta remota con espacios'; Clave = 'CarpetaRemota'; Valor = '/opt/mi carpeta' },
            @{ Que = 'una carpeta remota relativa'; Clave = 'CarpetaRemota'; Valor = 'sepa-incoming' },
            @{ Que = 'un host con caracteres raros'; Clave = 'VpsHost'; Valor = 'vps.com -oProxyCommand=calc' },
            @{ Que = 'ConservarRemotos = 0 (borraría lo recién subido)'; Clave = 'ConservarRemotos'; Valor = 0 },
            @{ Que = 'sin VpsHost'; Clave = 'VpsHost'; Valor = '' })) {
        $m = $malo
        Test-Caso "se rechaza $($m.Que)" {
            $v = $valida.Clone(); $v[$m.Clave] = $m.Valor
            try { Read-Configuracion (New-Config $v) | Out-Null } catch [ErrorDeConfiguracion] { return }
            throw 'no la rechazó'
        }
    }

    Write-Host "`nEl comando remoto, corrido de verdad en bash"

    $bash = @("$env:ProgramFiles\Git\bin\bash.exe", "${env:ProgramFiles(x86)}\Git\bin\bash.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($bash) {
        Test-Caso 'renombra el .part y deja sólo los N más nuevos' {
            $remota = Join-Path $tmp 'remota'
            New-Item -ItemType Directory -Path $remota | Out-Null
            foreach ($n in 'sepa_2026-09-01.zip', 'sepa_2026-09-08.zip', 'sepa_2026-09-14.zip', 'sepa_2026-09-15.zip.part', 'otro.txt') {
                Set-Content -LiteralPath (Join-Path $remota $n) -Value 'x'
            }
            $posix = (& $bash -c "cygpath -u '$remota'").Trim()
            & $bash -c (Get-ComandoRemoto $posix '2026-09-15' 2)
            if ($LASTEXITCODE -ne 0) { throw "bash salió con $LASTEXITCODE" }
            $quedan = (Get-ChildItem -LiteralPath $remota -Name | Sort-Object) -join ','
            Assert-Igual 'otro.txt,sepa_2026-09-14.zip,sepa_2026-09-15.zip' $quedan
        }
        Test-Caso 'si el .part no está, falla y no borra nada' {
            $remota = Join-Path $tmp 'remota2'
            New-Item -ItemType Directory -Path $remota | Out-Null
            foreach ($n in 'sepa_2026-09-01.zip', 'sepa_2026-09-08.zip', 'sepa_2026-09-14.zip') {
                Set-Content -LiteralPath (Join-Path $remota $n) -Value 'x'
            }
            $posix = (& $bash -c "cygpath -u '$remota'").Trim()
            # En Windows PowerShell, redirigir el stderr de un programa con
            # ErrorActionPreference = Stop lo convierte en excepción: el "mv:
            # cannot stat" esperado haría fallar el caso por el motivo equivocado.
            $antes = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            & $bash -c (Get-ComandoRemoto $posix '2026-09-15' 1) 2>&1 | Out-Null
            $codigo = $LASTEXITCODE
            $ErrorActionPreference = $antes
            if ($codigo -eq 0) { throw 'debería haber fallado' }
            Assert-Igual 3 (Get-ChildItem -LiteralPath $remota).Count
        }
    } else {
        Write-Host '  (sin bash de Git: se saltea)'
    }

    Write-Host "`nEl script entero, como lo corre la tarea (sin red)"

    function Invoke-Script([string[]]$Argumentos) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'sepa-relay.ps1') @Argumentos | Out-Null
        return $LASTEXITCODE
    }
    $cfg = New-Config $valida

    Test-Caso '-ZipLocal con un zip SEPA y -SinSubir: sale con 0 y anota la fecha en el log' {
        Assert-Igual 0 (Invoke-Script @('-Config', $cfg, '-ZipLocal', (New-ZipSepa 'local.zip' '2026-09-15'), '-SinSubir'))
        $log = Get-Content -LiteralPath (Join-Path $valida.CarpetaLocal 'relay.log') -Raw -Encoding UTF8
        if ($log -notmatch 'Fecha del dataset \(leída del zip\): 2026-09-15') { throw "el log no dice la fecha: $log" }
    }
    Test-Caso 'un zip que no es SEPA: sale con 2 (reintentable)' {
        Assert-Igual 2 (Invoke-Script @('-Config', $cfg, '-ZipLocal', (New-Zip 'no-sepa.zip' @('hola.txt')), '-SinSubir'))
    }
    Test-Caso 'sin configuración: sale con 1 (no se arregla reintentando)' {
        Assert-Igual 1 (Invoke-Script @('-Config', (Join-Path $tmp 'no-existe.psd1'), '-SinSubir'))
    }
    Test-Caso 'sin -Config toma el sepa-relay.config.psd1 de al lado, como lo corre la tarea' {
        # Todos los casos de arriba pasan -Config, así que nunca evaluaban el
        # valor por defecto. Ese valor rompía en Windows PowerShell 5.1
        # ($PSScriptRoot vacía dentro de param()) y es exactamente el camino
        # de la tarea programada y del paso a paso del README.
        $copia = Join-Path $tmp 'copia'
        New-Item -ItemType Directory -Path $copia | Out-Null
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'sepa-relay.ps1') -Destination $copia
        Copy-Item -LiteralPath $cfg -Destination (Join-Path $copia 'sepa-relay.config.psd1')
        $antes = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $copia 'sepa-relay.ps1') `
            -ZipLocal (New-ZipSepa 'sin-config.zip' '2026-09-15') -SinSubir 2>&1 | Out-Null
        $codigo = $LASTEXITCODE
        $ErrorActionPreference = $antes
        Assert-Igual 0 $codigo
    }
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:fallas) { Write-Host "`n$($script:fallas) caso(s) fallaron."; exit 1 }
Write-Host "`nTodo OK."
exit 0
