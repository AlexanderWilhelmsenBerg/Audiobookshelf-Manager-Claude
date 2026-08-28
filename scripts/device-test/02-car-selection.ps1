# docs/device-test-0.9.14.md 2.8 - tapping a book in the car. The half browse population does not cover.
#
# The 2026-08-28 run found a populated browse tree whose books would not open: the head unit stayed on
# "Henter valget ditt" forever. It could not be diagnosed, because a car tap that resolved logged nothing at
# all - the tree said children=6 and the next line was minutes later. Two candidate explanations were
# refuted by evidence that already existed, which left none for what did happen (R-71).
#
# This script captures the one window that was missing. Clear, tap, dump.

#Requires -Version 7.0

. (Join-Path $PSScriptRoot '_common.ps1')
Require-Device

Write-Step '2.8  Clear the log, so what follows is only the tap'
Clear-Logcat

Write-Step '2.8  Now tap a book in the car'
Write-Note 'In the DHU or the head unit: open a shelf, tap a book, and WAIT - up to 30 seconds. What is'
Write-Note 'being measured is whether it ever loads, so leaving too early records the wrong answer.'
Write-Note 'Watch the car: does the loading message clear, and does audio start?'
Wait-ForTester 'Once the book has either started or clearly hung.'

Write-Step '2.8  What the service was asked, and what it answered'
Show-LogcatMatches -Pattern 'A controller asked to set what plays' -Last 10
Write-Note 'kind=  the SHAPE of the id (book / at / tab / root), never the id - a book id names a book.'
Write-Note 'asked= how many items the car sent.  handedBack= how many this service resolved.'
Write-Warn 'handedBack=0 means this service could not resolve the tap: the defect is in resolution.'
Write-Warn 'handedBack=1 means the service answered correctly and the defect is downstream, in the player.'

Write-Step '2.8  What the player then did with it'
Show-LogcatMatches -Pattern 'The player changed state' -Last 15
Write-Note "Expect buffering then ready. Only 'idle', or no line at all, means the queue never reached the"
Write-Note 'player or was never prepared - a different defect from a queue that failed to load.'

Write-Step '2.8  Anything that threw'
Show-LogcatMatches -Pattern 'A browse request failed' -Last 10
Write-Note 'Expect nothing. This line covers EVERY session callback, so its absence rules out a throw in'
Write-Note 'the selection path - that is how the first hypothesis for this defect was refuted.'

Write-Step '2.8  Could the book be opened at all'
Show-LogcatMatches -Pattern 'Could not open a session for a browse or resume request' -Last 5
Write-Note 'If this appears, the server refused to open the session and resolution failed for that reason.'

Write-Step '2.8  And the same four lines from the in-app log'
Write-Note 'If logcat carried nothing above, this is not optional - it is the only record (R-70):'
Write-Note "  Settings > About > Diagnostics > Open the event log > search 'asked to set'"
Write-Note "Then search 'player changed state'. Copy both into the report."
