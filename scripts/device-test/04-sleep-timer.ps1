# docs/device-test-0.9.14.md section 4 - sleep timer and notification action.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 4 - Actions on the phone'
Write-Note "1. Play a book and set a 5-minute timer -> 'Sleep timer set'."
Write-Note "2. Extend it from the notification or shake -> 'Sleep timer extended'."
Write-Note "3. Set a 30-second custom timer and let it finish -> 'Sleep timer ended playback'."
Write-Note "4. Expect 'Rewound after the sleep timer'."
Write-Note "5. Tap the ended-timer row in the player's pane; the book screen is read-only by design."
Wait-ForTester 'Start a timer and fully expand the media notification.'

Write-Step 'Section 4 - Notification actions while the timer is active'
Show-Command "adb shell dumpsys notification --noredact | Select-String -Pattern 'NotificationRecord\(.*pkg=$Package' -Context 0,80"
$notification = Get-AdbOutput shell dumpsys notification --noredact
$escapedPackage = [regex]::Escape($Package)
$record = $notification |
    Select-String -Pattern "NotificationRecord\(.*pkg=$escapedPackage" -Context 0,80 |
    Select-Object -First 1
if (-not $record) {
    Write-Bad 'No active BookWave notification was found.'
    throw 'Start playback, set a timer, and expand the notification before retrying.'
}

$recordLines = @($record.Line) + @($record.Context.PostContext)
$header = $recordLines | Select-Object -First 1
$actionLines = @($recordLines | Where-Object { $_ -match '^\s+\[\d+\]\s+".*"\s+->' })
Write-Host "  $($header.Trim())"
$actionLines | ForEach-Object { Write-Host "  $($_.Trim())" }

$timerAction = $actionLines | Where-Object { $_ -match '^\s+\[3\]\s+' } | Select-Object -First 1
if ($timerAction) {
    Write-Ok 'The fourth notification action is present while the sleep timer is active.'
} else {
    Write-Bad 'The fourth sleep-timer action is missing while the timer is active.'
}
Write-Note 'Its localized label shows the remaining sleep time; pressing it extends the timer.'
Write-Note 'Missing while active is a defect; missing without a timer is correct.'
