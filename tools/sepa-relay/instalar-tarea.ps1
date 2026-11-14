<#
.SYNOPSIS
    Registra sepa-relay.ps1 en el Programador de tareas de Windows.

.DESCRIPTION
    Crea (o reemplaza) la tarea "OfertAR SEPA relay": corre cada hora, todo el
    día. Cada pasada sin novedades es una consulta chica a la API de SEPA; los
    ~300 MB se bajan sólo cuando hay un dataset nuevo.

    Si la PC estaba apagada o suspendida a la hora programada, la tarea corre
    apenas vuelve (StartWhenAvailable). Queda registrada para el usuario
    actual y corre sólo con la sesión iniciada; para que corra también con la
    sesión cerrada hay que cambiarlo a mano en el Programador de tareas
    ("Ejecutar tanto si el usuario inició sesión como si no"), que pide la
    contraseña de Windows.

.PARAMETER Desde
    Hora de la primera pasada de cada día (HH:mm).

.PARAMETER CadaHoras
    Cada cuántas horas repetir.

.PARAMETER Desinstalar
    Borra la tarea.
#>
[CmdletBinding()]
param(
    [string]$Desde = '00:10',
    [ValidateRange(1, 12)][int]$CadaHoras = 1,
    [switch]$Desinstalar
)

$ErrorActionPreference = 'Stop'
$nombre = 'OfertAR SEPA relay'

if ($Desinstalar) {
    Unregister-ScheduledTask -TaskName $nombre -Confirm:$false
    Write-Host "Tarea '$nombre' borrada."
    return
}

$script = Join-Path $PSScriptRoot 'sepa-relay.ps1'
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'sepa-relay.config.psd1'))) {
    throw 'Falta sepa-relay.config.psd1 al lado del script: copiá sepa-relay.config.example.psd1 y completalo antes de instalar.'
}

$accion = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$script`"" `
    -WorkingDirectory $PSScriptRoot

# Disparador diario con repetición: la forma que acepta Windows PowerShell 5.1
# sin depender de una duración "indefinida", que según la versión de Windows
# falla al registrar.
$disparador = New-ScheduledTaskTrigger -Daily -At $Desde
$repeticion = New-ScheduledTaskTrigger -Once -At $Desde `
    -RepetitionInterval (New-TimeSpan -Hours $CadaHoras) `
    -RepetitionDuration (New-TimeSpan -Hours 23 -Minutes 50)
$disparador.Repetition = $repeticion.Repetition

$ajustes = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Hours 2) -MultipleInstances IgnoreNew

Register-ScheduledTask -TaskName $nombre -Action $accion -Trigger $disparador -Settings $ajustes `
    -Description 'Baja el dataset SEPA desde esta conexión argentina y lo sube al VPS de OfertAR (tools/sepa-relay).' `
    -Force | Out-Null

Write-Host "Tarea '$nombre' registrada: cada $CadaHoras h desde las $Desde."
Write-Host "Para correrla ahora:  Start-ScheduledTask -TaskName '$nombre'"
Write-Host "Log: $env:LOCALAPPDATA\ofertar-sepa\relay.log (o la CarpetaLocal de la configuración)"
