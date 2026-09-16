#Requires -Version 5.1

<#
.SYNOPSIS
    One command: set everything up if needed, then start the server and the client.

.DESCRIPTION
    Runs .\setup.ps1 (which skips every step that is already done) and then
    .\run.ps1. The first run downloads the client and the game cache and builds
    the database, which takes a long time; later runs go straight to the game.

.PARAMETER Cache
    Use a game cache that already exists at this path instead of downloading one.

.EXAMPLE
    .\play.ps1
#>
[CmdletBinding()]
param(
    [string] $Cache
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot

$setupArgs = @{}
$runArgs = @{}
if ($Cache) { $setupArgs.Cache = $Cache; $runArgs.Cache = $Cache }

& (Join-Path $Root 'setup.ps1') @setupArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& (Join-Path $Root 'run.ps1') @runArgs
exit $LASTEXITCODE
