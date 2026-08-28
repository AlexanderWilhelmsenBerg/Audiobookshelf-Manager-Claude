# docs/device-test-0.9.14.md section 3 - listening sessions imported from the server.
#Requires -Version 7.0
. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step 'Section 3 - Manual preparation'
Write-Note '1. Pick a book this phone has never played.'
Write-Note '2. In the Audiobookshelf web client, using the same account, listen for at least 30 seconds.'
Write-Note "3. On the phone, open the book's three-dot menu, then History."
Write-Note "Expected: a row labelled 'Listened on another device'."
Wait-ForTester 'Complete those three steps.'

Write-Step 'Section 3.2 - Server session import'
$imports = @(Show-LogcatMatches -Pattern "Imported the server's sessions" -Last 10)
if ($imports.Count -eq 0) {
    Write-Bad 'No server-session import line was found.'
} else {
    $imports | ForEach-Object { Write-Output $_ }
    Write-Note 'fetched=<n> is the page size; imported=<m> is the number of history rows created.'
}

Write-Step 'Section 3.5 - Offline persistence'
Write-Note 'Enable aeroplane mode, close the history pane, and reopen it. The imported row must remain.'
Wait-ForTester 'Complete the offline check.'
$errors = @(Show-LogcatMatches -Pattern "Could not read the server's listening sessions" -Last 5)
$errors | ForEach-Object { Write-Output $_ }

Write-Step 'Section 3.2 - Privacy check'
Write-Note 'Inspect the redacted in-app event log. It must contain no title, author, device name, or hostname.'
