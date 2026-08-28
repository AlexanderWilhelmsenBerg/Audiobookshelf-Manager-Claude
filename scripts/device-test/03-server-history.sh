#!/usr/bin/env bash
# docs/device-test-0.9.14.md §3 — listening sessions imported from the server.
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"
require_device

step "§3  Before you start"
note "1. Pick a book this phone has NEVER played."
note "2. In the Audiobookshelf web client, as the SAME account, listen to it for 30+ seconds."
note "3. On the phone: the book's screen → three-dot menu → History."
note "Expect a row reading 'Listened on another device'."

step "§3.2  Did the import run, and what did it filter"
logcat_grep "Imported the server's sessions" 10
note "fetched=<n> is the page size; imported=<m> is how many became rows for this book."

step "§3.5  The offline case"
note "Turn aeroplane mode on, close the pane, reopen it. The row must still be there."
logcat_grep "Could not read the server's listening sessions" 5

step "§3.2  Nothing private in the log"
note "Checked against the *in-app* log, which is the redacted one. No book title, no author, no device"
note "name, no server hostname. Counts and durations are expected (14.5)."
