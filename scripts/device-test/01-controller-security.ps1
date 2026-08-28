# docs/device-test-0.9.14.md section 1 - trusted and untrusted media controllers.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 1 - Media sessions currently registered'
Write-Note 'ADB holds MEDIA_CONTENT_CONTROL, so commands from this script exercise the trusted branch.'
Write-Note 'The untrusted branch still requires a third-party controller app.'
$sessions = Get-AdbOutput shell dumpsys media_session
@($sessions | Select-String -SimpleMatch 'Sessions Stack' -Context 0,40 | Select-Object -First 1) |
    ForEach-Object { $_.ToString() }

Write-Step "Section 1 - BookWave session ($Package)"
@($sessions | Select-String -SimpleMatch $Package -Context 0,6 | Select-Object -First 4) |
    ForEach-Object { $_.ToString() }

Write-Step 'Section 1.4 - Transport from a trusted caller'
Write-Note 'Watch the phone; every command should move playback.'
foreach ($entry in @(
    @{ Key = '85'; Name = 'play/pause' },
    @{ Key = '87'; Name = 'next' },
    @{ Key = '88'; Name = 'previous' }
)) {
    Write-Host "  press $($entry.Key) ($($entry.Name))"
    Invoke-Adb shell input keyevent $entry.Key
    Start-Sleep -Seconds 2
}

Show-AppLog -Last 120
Write-Step 'Manual untrusted-controller check'
Write-Note 'Settings -> About -> Diagnostics -> Open the event log -> Copy.'
Write-Note 'Expected: A controller connected without library access; controller=<package>.'
