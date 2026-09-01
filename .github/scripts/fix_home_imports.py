from pathlib import Path

path = Path("app/src/main/kotlin/com/example/shelfplayer/feature/home/HomeScreen.kt")
text = path.read_text()
old = """import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.isSystemInDarkTheme
"""
new = """import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
"""
if old not in text:
    raise SystemExit("HomeScreen import block not found")
path.write_text(text.replace(old, new, 1))
