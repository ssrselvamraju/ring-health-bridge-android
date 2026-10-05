[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Scan', 'Pair', 'Verify')]
    [string]$Action,

    [string]$NameContains = 'Oura',

    [ValidateRange(5, 120)]
    [int]$ScanTimeout = 30,

    [switch]$ConfirmFactoryReset
)

$ErrorActionPreference = 'Stop'

$workspace = Split-Path -Parent $PSScriptRoot
$upstream = Join-Path $workspace 'third_party\open_oura'
$python = Join-Path $upstream '.venv\Scripts\python.exe'
$protocolTool = Join-Path $upstream 'tools\oura_protocol.py'
$scanner = Join-Path $PSScriptRoot 'safe_scan.py'

if (-not (Test-Path -LiteralPath $python)) {
    throw "The isolated Python environment is missing: $python"
}

$privateRoot = Join-Path $env:LOCALAPPDATA 'OuraHealthBridge'
$keyPath = Join-Path $privateRoot 'gen3-horizon-auth-key.hex'
$capturePath = Join-Path $privateRoot 'provisioning-capture.jsonl'

function Protect-PrivateDirectory {
    New-Item -ItemType Directory -Force -Path $privateRoot | Out-Null

    # Remove inherited access and grant only this Windows account plus SYSTEM.
    # Using SIDs avoids failures caused by localized Windows account names.
    $userSid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $privateRoot '/inheritance:r' `
        "/grant:r" "*$userSid`:(OI)(CI)F" `
        "/grant:r" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restrict access to $privateRoot"
    }
}

switch ($Action) {
    'Scan' {
        & $python $scanner --name-contains $NameContains --timeout $ScanTimeout
        if ($LASTEXITCODE -ne 0) { throw 'Read-only BLE scan failed.' }
    }

    'Pair' {
        if (-not $ConfirmFactoryReset) {
            throw 'Pair is blocked. Factory-reset the ring, then rerun with -ConfirmFactoryReset.'
        }

        Protect-PrivateDirectory
        Write-Host 'Pairing will install a new application key on the factory-reset ring.'
        Write-Host "The key and diagnostic capture will remain outside OneDrive in: $privateRoot"

        & $python $protocolTool auth-safe-matrix `
            --set-auth-key `
            --auth-key-file $keyPath `
            --capture $capturePath `
            --name-contains $NameContains `
            --scan-timeout $ScanTimeout
        if ($LASTEXITCODE -ne 0) { throw 'Ring provisioning failed.' }

        Protect-PrivateDirectory
        Write-Host "Provisioning complete. Key saved locally at: $keyPath"
    }

    'Verify' {
        if (-not (Test-Path -LiteralPath $keyPath)) {
            throw "No provisioned key exists at $keyPath"
        }

        Protect-PrivateDirectory
        & $python $protocolTool auth-safe-matrix `
            --auth-key-file $keyPath `
            --capture $capturePath `
            --name-contains $NameContains `
            --scan-timeout $ScanTimeout
        if ($LASTEXITCODE -ne 0) { throw 'Authenticated verification failed.' }
    }
}
