from pathlib import Path


def remove_between(path: str, start: str, end: str) -> None:
    p = Path(path)
    text = p.read_text()
    start_at = text.find(start)
    if start_at < 0:
        raise SystemExit(f"{path}: start marker not found: {start!r}")
    end_at = text.find(end, start_at)
    if end_at < 0:
        raise SystemExit(f"{path}: end marker not found: {end!r}")
    p.write_text(text[:start_at] + text[end_at:])


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old!r}")
    p.write_text(text.replace(old, new))


# #93 makes the ordinary Media3 Play boundary the only freshness owner. Retire the old
# first-party custom adoption API rather than preserving a second, now-unowned resume path.
controller = "playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackController.kt"
remove_between(
    controller,
    "    /**\n     * PRODUCT_SPEC SYNC-002 — resumes, adopting [position] when another device has moved the book.\n",
    "    /**\n     * PRODUCT_SPEC PLAY-001 — retry after a failure the service gave up on.\n",
)
for unused_import in (
    "import androidx.media3.session.SessionError\n",
    "import androidx.media3.session.SessionResult\n",
    "import com.example.shelfplayer.core.model.resultOf\n",
):
    replace_once(controller, unused_import, "")

service = "playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt"
remove_between(
    service,
    "        /**\n         * PRODUCT_SPEC SYNC-002 — runs the atomic resume and answers with whether it worked.\n",
    "        override fun onCustomCommand(\n",
)
replace_once(
    service,
    "                        // PRODUCT_SPEC SYNC-002 — the app's own atomic resume. Granted on the same\n"
    "                        // condition as the four above and refused to everything else: a seek followed by a\n"
    "                        // play, driven from outside the app, is exactly the pair `ControllerTrust`\n"
    "                        // withholds. See `ResumeCommand`.\n"
    "                        .add(ResumeCommand.command())\n",
    "",
)
replace_once(
    service,
    "            // PRODUCT_SPEC SYNC-002 — the one command with an answer, so it is handled before the\n"
    "            // fire-and-forget four rather than inside their `when`.\n"
    "            if (customCommand.customAction == ResumeCommand.ACTION) return resumeCommand(args)\n",
    "",
)
