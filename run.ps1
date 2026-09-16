#Requires -Version 5.1

<#
.SYNOPSIS
    Starts the RS3OS server and the game client.

.DESCRIPTION
    Starts the server in the background, waits until it is accepting
    connections, then launches the patched client. Closing the client stops the
    server again. The server log is written to logs\.

    Most gameplay lives behind an -Dopennxt.experiment.* switch so that a
    half-finished system can be turned off without touching code; the defaults
    below turn on everything that works.

.PARAMETER Cache
    Where the game cache lives. Defaults to data\cache inside the repository.

.PARAMETER Flags
    Extra -D switches appended after the defaults. The JVM takes the last value
    for a repeated property, so this overrides anything set below.

.PARAMETER Memory
    JVM maximum heap. Default 4g.

.PARAMETER ServerOnly
    Start the server and keep it running in this window; do not launch a client.

.PARAMETER ClientOnly
    Launch a client against a server that is already running.

.EXAMPLE
    .\run.ps1

.EXAMPLE
    .\run.ps1 -ServerOnly -Flags '-Dopennxt.experiment.combat.realdamage=false'
#>
[CmdletBinding()]
param(
    [string] $Cache,
    [string] $Flags = '',
    [string] $Memory = '4g',
    [switch] $ServerOnly,
    [switch] $ClientOnly
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
Set-Location $Root

$Build = 950
$ReadyMarker = 'Game server bound to'

function Fail($text) {
    Write-Host ''
    Write-Host $text -ForegroundColor Yellow
    exit 1
}

function Start-Client {
    $client = Join-Path $Root "data\clients\$Build\win64c\patched\rs2client.exe"
    if (-not (Test-Path $client)) { Fail 'No patched client yet - run .\setup.ps1 first.' }
    Write-Host 'Launching the client. Log in with any username and password;' -ForegroundColor Cyan
    Write-Host 'a new account is created the first time you use a name.' -ForegroundColor Cyan
    & python (Join-Path $Root 'tools\launch_client.py')
}

if ($ClientOnly) {
    Start-Client
    exit $LASTEXITCODE
}

$lib = Join-Path $Root 'build\install\rs3os\lib'
if (-not (Test-Path $lib)) { Fail 'The server is not built yet. Run .\setup.ps1 first.' }

if (-not $Cache) { $Cache = Join-Path $Root 'data\cache' }
if (-not (Test-Path $Cache)) { Fail "No cache at $Cache - run .\setup.ps1 first, or pass -Cache <path>." }

foreach ($f in 'rsa.toml', 'server.toml') {
    if (-not (Test-Path (Join-Path $Root "data\config\$f"))) { Fail "Missing data\config\$f - run .\setup.ps1 first." }
}
if (-not (Test-Path (Join-Path $Root 'data\rs3.sqlite'))) { Fail 'Missing data\rs3.sqlite - run .\setup.ps1 first.' }

$busy = Get-NetTCPConnection -State Listen -LocalPort 43594 -ErrorAction SilentlyContinue
if ($busy) {
    Fail "Port 43594 is already in use (process $($busy[0].OwningProcess)). Is a server already running?"
}

# Protocol and world.
#   prot.experimentalBuild=950   speak the build 950 protocol tables in data\prot\950
#   npc.dispatch                 route clicks on NPCs into content (talk, fish, attack)
#   banks.ui                     the bank window, deposits and withdrawals
#   equip                        wielding and removing equipment
$world = @(
    "-Dopennxt.prot.experimentalBuild=$Build"
    '-Dopennxt.experiment.npc.dispatch=true'
    '-Dopennxt.experiment.banks.ui=true'
    '-Dopennxt.experiment.equip=true'
)

# Combat.
#   combat              hit splats, damage, death, drops, combat xp
#   combat.realdamage   damage rolled from your stats and weapon instead of a flat value
#   abilityQueue        abilities queue behind the global cooldown
#   movementAbilities   Surge, Escape and similar
$combat = @(
    '-Dopennxt.experiment.combat=true'
    '-Dopennxt.experiment.combat.realdamage=true'
    '-Dopennxt.combat.abilityQueue=on'
    '-Dopennxt.combat.movementAbilities=on'
)

# Interface.
#   sendStats             push skill levels and xp to the skills panel, shortly after login
#   ui.varcReplay=false   do not replay stored interface variables at login
#   varpSmallAsLarge=off  send each player variable with its own packet size
$interface = @(
    '-Dopennxt.experiment.sendStats=true'
    '-Dopennxt.experiment.sendStats.delayTicks=20'
    '-Dopennxt.experiment.ui.varcReplay=false'
    '-Dopennxt.compat.varpSmallAsLarge=off'
)

# An optional table of player variables to send at login, one "id<TAB>value"
# per line. Not shipped; see the README.
$varps = Join-Path $Root "data\config\login-varps-$Build.tsv"
if (Test-Path $varps) { $interface += "-Dopennxt.experiment.varpFile=$varps" }

$jvm = @(
    "-Xmx$Memory"
    "-Dopennxt.cache=$Cache"
    '-Dorg.slf4j.simpleLogger.logFile=System.out'
) + $world + $combat + $interface
if ($Flags) { $jvm += ($Flags -split '\s+' | Where-Object { $_ }) }

$logs = Join-Path $Root 'logs'
New-Item -ItemType Directory -Force $logs | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$out = Join-Path $logs "server-$stamp.log"
$err = Join-Path $logs "server-$stamp.err.log"

$javaArgs = @($jvm | ForEach-Object { if ($_ -match '\s') { "`"$_`"" } else { $_ } })
$javaArgs += @('-cp', "`"$lib\*`"", 'com.opennxt.MainKt', 'run-server')

Write-Host "Starting the server (cache: $Cache)" -ForegroundColor Cyan
Write-Host "  log: $out"
$server = Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory $Root `
    -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden -PassThru
$null = $server.Handle

try {
    Write-Host 'Waiting for the server to finish loading (the first start takes a few minutes)...'
    $deadline = (Get-Date).AddMinutes(20)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if ($server.HasExited) {
            Write-Host ''
            Get-Content $out -Tail 25 -ErrorAction SilentlyContinue
            Get-Content $err -Tail 25 -ErrorAction SilentlyContinue
            Fail "The server stopped during startup (exit code $($server.ExitCode)). The log is $out"
        }
        if ((Test-Path $out) -and (Select-String -Path $out -SimpleMatch $ReadyMarker -Quiet)) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { Fail "The server did not finish loading within 20 minutes. The log is $out" }
    Write-Host 'Server is up on port 43594.' -ForegroundColor Green

    if ($ServerOnly) {
        Write-Host 'Press Ctrl+C to stop the server.'
        Wait-Process -Id $server.Id
    } else {
        Start-Client
        Write-Host 'Client closed.'
    }
}
finally {
    if (-not $server.HasExited) {
        Write-Host 'Stopping the server.'
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
}
