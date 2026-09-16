#Requires -Version 5.1

<#
.SYNOPSIS
    Sets up an RS3OS server from a fresh clone: build, keys, client, cache, database.

.DESCRIPTION
    Runs every setup step that has not already been done, in order, and stops at
    the first failure with an explanation. Safe to run again -- finished steps
    are detected and skipped, so an interrupted download can be resumed by
    simply running the script a second time.

    Steps, in order:

      check     Java 21 and Python 3 are present
      build     compile the server            (gradlew installDist)
      config    data/config/server.toml       (from server.example.toml)
      keys      data/config/rsa.toml          (run-tool rsa-key-generator)
      client    data/clients/950/             (run-tool client-downloader)
      patch     the client trusts your key    (run-tool client-patcher)
      cache     data/cache/                   (run-tool cache-downloader)
      database  data/rs3.sqlite               (run-tool db-builder)
      world     collision and placements      (run-tool map-builder)
      seed      data/seed/npc_cache.json      (tools/seed_from_cache.py)

.PARAMETER Hostname
    The address your client will connect to. Default 127.0.0.1 (this machine).

.PARAMETER Cache
    Use a game cache that already exists at this path instead of downloading a
    new one into data\cache. The cache step is skipped when this is given.

.PARAMETER Step
    Run one step only, by name. Useful for retrying a single failure.

.PARAMETER Force
    Redo steps even if their output already exists.

.EXAMPLE
    .\setup.ps1

.EXAMPLE
    .\setup.ps1 -Step cache
#>
[CmdletBinding()]
param(
    [string] $Hostname = '127.0.0.1',
    [string] $Cache,
    [ValidateSet('check', 'build', 'config', 'keys', 'client', 'patch', 'cache', 'database', 'world', 'seed')]
    [string] $Step,
    [switch] $Force
)

$ErrorActionPreference = 'Stop'

$script:CachePath = ''
if ($Cache) {
    if (-not (Test-Path $Cache)) { throw "no such cache directory: $Cache" }
    $script:CachePath = (Resolve-Path $Cache).Path
}
$Root = $PSScriptRoot
Set-Location $Root

$Build = 950
$BinaryType = 'win64c'

# --------------------------------------------------------------------------
# output helpers
# --------------------------------------------------------------------------
$script:StepNo = 0
function Write-Step($name, $text) {
    $script:StepNo++
    Write-Host ''
    Write-Host ("[{0}] {1}" -f $script:StepNo, $text) -ForegroundColor Cyan
}
function Write-Ok($text)   { Write-Host "    OK    $text" -ForegroundColor Green }
function Write-Skip($text) { Write-Host "    skip  $text" -ForegroundColor DarkGray }
function Write-Info($text) { Write-Host "          $text" -ForegroundColor Gray }
function Fail($text) {
    Write-Host ''
    Write-Host "FAILED: $text" -ForegroundColor Red
    exit 1
}

function Set-ToolOptions {
    # The generated launcher expands its options unquoted, so a value
    # containing a space has to carry its own quotes.
    $opts = @("-Dopennxt.prot.experimentalBuild=$Build")
    if ($script:CachePath) { $opts += '"-Dopennxt.cache=' + $script:CachePath + '"' }
    $env:RS3OS_OPTS = $opts -join ' '
}

function Invoke-Rs3os {
    <#  Runs the built server CLI and fails loudly on a non-zero exit.  #>
    param([string[]] $Arguments, [string] $What)

    $exe = Join-Path $Root 'build\install\rs3os\bin\rs3os.bat'
    if (-not (Test-Path $exe)) {
        Fail "the server is not built yet. Run: .\setup.ps1 -Step build"
    }
    Write-Info "run-tool: $($Arguments -join ' ')"
    Set-ToolOptions
    # The tools log to stderr. Windows PowerShell 5.1 turns redirected native
    # stderr into terminating errors under 'Stop', so relax it for the call.
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $exe @Arguments } finally { $ErrorActionPreference = $eap }
    if ($LASTEXITCODE -ne 0) { Fail "$What (exit code $LASTEXITCODE)" }
}

function Should-Run($name) {
    if ($Step) { return $Step -eq $name }
    return $true
}

# --------------------------------------------------------------------------
# 1. prerequisites
# --------------------------------------------------------------------------
if (Should-Run 'check') {
    Write-Step 'check' 'Checking prerequisites'

    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        Fail @"
Java is not on your PATH.

Install a JDK 21 (Temurin is the usual choice) and reopen this terminal:
    https://adoptium.net/temurin/releases/?version=21
"@
    }

    # `java -version` prints to stderr. Windows PowerShell 5.1 wraps redirected
    # native stderr in an ErrorRecord and throws while $ErrorActionPreference is
    # 'Stop', so the call drops to 'Continue' for the duration.
    $vLine = $null
    try {
        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $vLine = (& java -version 2>&1 | Select-Object -First 1) -as [string]
    } finally { $ErrorActionPreference = $eap }
    if ($vLine -match '"(\d+)') {
        $major = [int]$Matches[1]
        # Java 21 exactly. The Kotlin compiler this project uses does not
        # recognise newer JDKs and aborts with an internal error that names no cause.
        if ($major -ne 21) {
            Fail @"
Java $major found, but this project builds with Java 21 specifically.

  $vLine

Newer versions do not work: the Kotlin compiler aborts with an internal error
that does not say why. Install Java 21 and make it the one on your PATH:

    https://adoptium.net/temurin/releases/?version=21

It can live alongside the Java you already have.
"@
        }
        Write-Ok "Java $major"
    } else {
        Write-Info "could not parse the Java version from: $vLine"
        Write-Info "continuing anyway"
    }

    $py = Get-Command python -ErrorAction SilentlyContinue
    if (-not $py) {
        Fail @"
Python 3 is not on your PATH.

It is needed to extract NPC data during setup and to launch the client.
Install it from https://www.python.org/downloads/ (tick "Add python.exe to PATH").
"@
    }
    Write-Ok 'Python 3'
}

# --------------------------------------------------------------------------
# 2. build
# --------------------------------------------------------------------------
if (Should-Run 'build') {
    Write-Step 'build' 'Building the server'

    $exe = Join-Path $Root 'build\install\rs3os\bin\rs3os.bat'
    if ((Test-Path $exe) -and -not $Force) {
        Write-Skip 'already built (pass -Force to rebuild)'
    } else {
        Write-Info 'this takes a few minutes the first time'
        & (Join-Path $Root 'gradlew.bat') installDist --console=plain -q
        if ($LASTEXITCODE -ne 0) { Fail "the build failed (exit code $LASTEXITCODE)" }
        Write-Ok 'built to build\install\rs3os\'
    }
}

# --------------------------------------------------------------------------
# 3. configuration
# --------------------------------------------------------------------------
if (Should-Run 'config') {
    Write-Step 'config' 'Writing data\config\server.toml'

    $cfg = Join-Path $Root 'data\config\server.toml'
    if ((Test-Path $cfg) -and -not $Force) {
        Write-Skip 'server.toml already exists (pass -Force to overwrite)'
    } else {
        $text = @"
# Where your client will look for this server. 127.0.0.1 means this machine.
hostname = "$Hostname"

# The NXT build this server speaks. The cache and the client must match it.
build = $Build

configUrl = "http://${Hostname}/jav_config.ws?binaryType=2"

[networking.ports]
game = 43594
http = 80
https = 443
"@
        # BOM-less UTF-8 on both PowerShell editions; the TOML parser reads a
        # BOM as part of the first key.
        [System.IO.File]::WriteAllText($cfg, $text, (New-Object System.Text.UTF8Encoding $false))
        Write-Ok "hostname = $Hostname, build = $Build"
    }

    $mods = Join-Path $Root 'data\config\mods.json'
    if (-not (Test-Path $mods)) {
        Copy-Item (Join-Path $Root 'data\config\mods.example.json') $mods
        Write-Ok 'mods.json created - edit it to give your account admin rights'
    }
}

# --------------------------------------------------------------------------
# 4. RSA keys
# --------------------------------------------------------------------------
if (Should-Run 'keys') {
    Write-Step 'keys' 'Generating your RSA key pair'

    $rsa = Join-Path $Root 'data\config\rsa.toml'
    if ((Test-Path $rsa) -and -not $Force) {
        Write-Skip 'rsa.toml already exists'
        Write-Info 'regenerating it would invalidate the client you have already patched'
    } else {
        Invoke-Rs3os @('run-tool', 'rsa-key-generator') 'the key generator failed'
        Write-Ok 'data\config\rsa.toml written'
        Write-Info 'this is a PRIVATE KEY. It is gitignored. Never publish it.'
    }
}

# --------------------------------------------------------------------------
# 5. the client
# --------------------------------------------------------------------------
if (Should-Run 'client') {
    Write-Step 'client' 'Downloading the game client'

    $orig = Join-Path $Root "data\clients\$Build\$BinaryType\original"
    if ((Test-Path (Join-Path $orig 'rs2client.exe')) -and -not $Force) {
        Write-Skip 'the client is already downloaded'
    } else {
        Invoke-Rs3os @('run-tool', 'client-downloader') 'the client downloader failed'

        # The downloader files its result under the build Jagex is serving right
        # now. Say so plainly if that is not the build this server speaks.
        if (-not (Test-Path (Join-Path $orig 'rs2client.exe'))) {
            $others = @(Get-ChildItem (Join-Path $Root 'data\clients') -Directory -ErrorAction SilentlyContinue |
                        Where-Object { $_.Name -ne "$Build" } | ForEach-Object { $_.Name })
            $downloaded = if ($others) { $others -join ', ' } else { 'nothing' }
            Fail @"
The downloader fetched build $downloaded, but this server speaks build $Build.

It always fetches whatever Jagex is serving today, and there is no way to ask
it for an older build. Once the live game moves past $Build, a build $Build
client has to come from a copy you already have, placed (with its DLLs and its
jav_config.ws) in:

    data\clients\$Build\$BinaryType\original\
"@
        }
        Write-Ok "client downloaded to data\clients\$Build\$BinaryType\original"
    }
}

# --------------------------------------------------------------------------
# 6. patch the client
# --------------------------------------------------------------------------
if (Should-Run 'patch') {
    Write-Step 'patch' 'Patching the client to trust your key'

    $patched = Join-Path $Root "data\clients\$Build\$BinaryType\patched\rs2client.exe"
    if ((Test-Path $patched) -and -not $Force) {
        Write-Skip 'a patched client already exists (pass -Force to redo)'
    } else {
        Invoke-Rs3os @('run-tool', 'client-patcher') 'the client patcher failed'
        Write-Ok 'the client now trusts your RSA key and points at your server'
    }
}

# --------------------------------------------------------------------------
# 7. the cache
# --------------------------------------------------------------------------
if (Should-Run 'cache') {
    Write-Step 'cache' 'Downloading the game cache'

    $cacheDir = Join-Path $Root 'data\cache'
    $existing = if (Test-Path $cacheDir) { @(Get-ChildItem $cacheDir -File -ErrorAction SilentlyContinue) } else { @() }
    if ($script:CachePath) {
        Write-Skip "using the existing cache at $script:CachePath"
    } elseif ($existing.Count -gt 0 -and -not $Force) {
        Write-Skip "data\cache already holds $($existing.Count) file(s)"
        Write-Info 'run this step again at any time to top it up: .\setup.ps1 -Step cache'
    } else {
        Write-Info 'This is tens of gigabytes and will take a long time.'
        Write-Info 'It resumes where it left off, so interrupting it is safe.'
        Invoke-Rs3os @('run-tool', 'cache-downloader') 'the cache download failed'
        Write-Ok 'cache downloaded'
    }
}

# --------------------------------------------------------------------------
# 8. the definition database
# --------------------------------------------------------------------------
if (Should-Run 'database') {
    Write-Step 'database' 'Building data\rs3.sqlite from the cache'

    $db = Join-Path $Root 'data\rs3.sqlite'
    if ((Test-Path $db) -and -not $Force) {
        Write-Skip 'rs3.sqlite already exists (pass -Force to rebuild)'
    } else {
        # Build to a temporary name and rename on success, so an interrupted or
        # failed build is never mistaken for a finished one on the next run.
        $tmp = Join-Path $Root 'data\rs3.building.sqlite'
        Remove-Item "$tmp*" -Force -ErrorAction SilentlyContinue
        Remove-Item "$db*" -Force -ErrorAction SilentlyContinue
        $dbArgs = @('run-tool', 'db-builder', '--output', 'data/rs3.building.sqlite', '--force')
        Invoke-Rs3os $dbArgs 'building the database failed'
        Move-Item $tmp $db
        Write-Ok 'rs3.sqlite built'
    }
}

# --------------------------------------------------------------------------
# 9. the world: collision and object placements
# --------------------------------------------------------------------------
if (Should-Run 'world') {
    Write-Step 'world' 'Decoding the world map from the cache'

    $db = Join-Path $Root 'data\rs3.sqlite'
    if (-not (Test-Path $db)) {
        Fail 'data\rs3.sqlite does not exist yet. Run: .\setup.ps1 -Step database'
    }

    # map-builder refuses a populated set unless forced, so let it make that
    # call rather than second-guessing it from a row count here.
    $mapArgs = @('run-tool', 'map-builder', '--database', 'data/rs3.sqlite')
    if ($Force) { $mapArgs += '--force' }
    Write-Info 'terrain, collision and object placements - a few minutes'
    Set-ToolOptions
    $eap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & (Join-Path $Root 'build\install\rs3os\bin\rs3os.bat') @mapArgs } finally { $ErrorActionPreference = $eap }
    if ($LASTEXITCODE -ne 0) {
        if ($Force) { Fail 'building the world map failed' }
        Write-Skip 'the map tables already hold data (pass -Force to rebuild them)'
    } else {
        Write-Ok 'collision and placements written'
    }
}

# --------------------------------------------------------------------------
# 10. NPC combat seed, from your own cache
# --------------------------------------------------------------------------
if (Should-Run 'seed') {
    Write-Step 'seed' 'Extracting NPC combat data from your database'

    $seed = Join-Path $Root 'data\seed\npc_cache.json'
    if ((Test-Path $seed) -and -not $Force) {
        Write-Skip 'npc_cache.json already exists (pass -Force to rebuild)'
    } else {
        $eap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & python (Join-Path $Root 'tools\seed_from_cache.py') } finally { $ErrorActionPreference = $eap }
        if ($LASTEXITCODE -ne 0) { Fail 'extracting the NPC seed failed' }
        Write-Ok 'data\seed\npc_cache.json written from your own cache'
    }
}

# --------------------------------------------------------------------------
# done
# --------------------------------------------------------------------------
if (-not $Step) {
    Write-Host ''
    Write-Host 'Setup complete.' -ForegroundColor Green
    Write-Host ''
    Write-Host '  Start the server and the client:' -ForegroundColor White
    Write-Host '      .\run.ps1'
    Write-Host ''
    Write-Host '  Give yourself admin rights by putting your account name in' -ForegroundColor White
    Write-Host '      data\config\mods.json'
    Write-Host ''
}
