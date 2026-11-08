#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateRange(10, 600)][int]$Seconds = 120,
    [string]$Serial
)

. (Join-Path $PSScriptRoot '_common.ps1')
Require-Adb
$devices = @(Get-AdbOutput devices | Where-Object { $_ -match "`tdevice$" } |
    ForEach-Object { ($_ -split "`t")[0] })
if (-not $Serial) {
    if ($devices.Count -ne 1) { throw 'Attach one authorised phone, or specify -Serial.' }
    $Serial = $devices[0]
}
if ($Serial -notin $devices) { throw 'The selected phone is not attached and authorised.' }

# Raw system logs may contain other apps and device identifiers. Never upload automatically.
$capture = Join-Path $RepoRoot ('build/focus-capture-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') +
    '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $capture | Out-Null

function Save-FocusCapture {
    param([string]$Name, [string[]]$Command, [int]$TimeoutSeconds = 15, [switch]$Streaming)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Adb
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in (@('-s', $Serial) + $Command)) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    $output = [IO.File]::Create((Join-Path $capture "$Name.txt"))
    $errors = [IO.File]::Create((Join-Path $capture "$Name-stderr.txt"))
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw "Could not start capture: $Name" }
        $copyOutput = $process.StandardOutput.BaseStream.CopyToAsync($output)
        $copyErrors = $process.StandardError.BaseStream.CopyToAsync($errors)
        $finished = $process.WaitForExit($TimeoutSeconds * 1000)
        if (-not $finished) { $process.Kill(); $process.WaitForExit() }
        $copyOutput.GetAwaiter().GetResult()
        $copyErrors.GetAwaiter().GetResult()
        if ((-not $Streaming -and -not $finished) -or ($Streaming -and $finished) -or
            ($finished -and $process.ExitCode -ne 0)) {
            throw "Capture failed: $Name; inspect its local stderr file."
        }
    } finally {
        if ($started -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $output.Dispose()
        $errors.Dispose()
        $process.Dispose()
    }
}

@("startedUtc=$([DateTime]::UtcNow.ToString('O'))", "durationSeconds=$Seconds",
    'Raw private system trace; availability of focus requester/type depends on the phone.') |
    Set-Content -LiteralPath (Join-Path $capture 'capture-info.txt')
Save-FocusCapture -Name 'audio-before' -Command @('shell', 'dumpsys', 'audio')
Save-FocusCapture -Name 'device-uptime-before' -Command @('shell', 'cat', '/proc/uptime')
Write-Host "Capture starting for $Seconds seconds. Connect Android Auto now; leave playback controls alone."
try {
    Save-FocusCapture -Name 'focus-logcat' -TimeoutSeconds $Seconds -Streaming -Command @(
        'logcat', '-T', '1', '-v', 'epoch', '-b', 'main', '-b', 'system', '-s',
        'MediaFocusControl:V', 'AudioService:V', 'AudioFocusManager:V', '*:S'
    )
} finally {
    Save-FocusCapture -Name 'audio-after' -Command @('shell', 'dumpsys', 'audio')
    Save-FocusCapture -Name 'device-uptime-after' -Command @('shell', 'cat', '/proc/uptime')
    Add-Content -LiteralPath (Join-Path $capture 'capture-info.txt') -Value "endedUtc=$([DateTime]::UtcNow.ToString('O'))"
    Write-Host "Saved locally: $capture. Export BookWave diagnostics separately."
}
