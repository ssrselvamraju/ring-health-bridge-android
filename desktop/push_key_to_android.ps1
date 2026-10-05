[CmdletBinding()]
param(
    [string]$Package = 'dev.local.ourahealthbridge',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
$keyPath = Join-Path $env:LOCALAPPDATA 'OuraHealthBridge\gen3-horizon-auth-key.hex'

if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "ADB is missing: $AdbPath"
}
if (-not (Test-Path -LiteralPath $keyPath)) {
    throw "The locally provisioned Oura key is missing: $keyPath"
}
if ($Package -notmatch '^[a-zA-Z][a-zA-Z0-9_.]*$') {
    throw 'The Android package name contains unsafe characters.'
}

$keyBytes = [IO.File]::ReadAllBytes($keyPath)
try {
    $text = [Text.Encoding]::ASCII.GetString($keyBytes).Trim()
    if ($text -notmatch '^[0-9a-fA-F]{32}$') {
        throw 'The Oura key file does not contain exactly 32 hexadecimal characters.'
    }

    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $AdbPath
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # Windows PowerShell uses .NET Framework, where ProcessStartInfo.ArgumentList
    # is unavailable. Every interpolated character is constrained above; the key
    # itself is sent through stdin and is never part of this argument string.
    $start.Arguments = "exec-in run-as $Package sh -c `"cat > files/.oura-key-import.hex`""

    $process = [Diagnostics.Process]::Start($start)
    $process.StandardInput.BaseStream.Write($keyBytes, 0, $keyBytes.Length)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $stderr = $process.StandardError.ReadToEnd()
    if ($process.ExitCode -ne 0) {
        throw "ADB private key staging failed: $stderr"
    }

    & $AdbPath shell am force-stop $Package | Out-Null
    & $AdbPath shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'The app could not be restarted to import the staged key.'
    }

    Write-Host 'Key streamed into the debug app sandbox; import was triggered.'
    Write-Host 'The key value was not printed or copied to shared phone storage.'
} finally {
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
}
