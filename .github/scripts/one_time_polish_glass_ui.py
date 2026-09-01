from pathlib import Path

path = Path("app/src/main/kotlin/com/example/shelfplayer/feature/home/HomeScreen.kt")
text = path.read_text()

text = text.replace(
    "import androidx.compose.foundation.background\n",
    "import androidx.compose.foundation.background\n"
    "import androidx.compose.foundation.selection.selectable\n"
    "import androidx.compose.foundation.selection.selectableGroup\n",
    1,
)
text = text.replace(
    "import androidx.compose.foundation.layout.fillMaxSize\n"
    "import androidx.compose.foundation.layout.fillMaxWidth\n",
    "import androidx.compose.foundation.layout.fillMaxHeight\n"
    "import androidx.compose.foundation.layout.fillMaxSize\n"
    "import androidx.compose.foundation.layout.fillMaxWidth\n"
    "import androidx.compose.foundation.layout.height\n",
    1,
)
for old_import in (
    "import androidx.compose.material3.NavigationBar\n",
    "import androidx.compose.material3.NavigationBarItem\n",
    "import androidx.compose.material3.NavigationBarItemDefaults\n",
):
    text = text.replace(old_import, "", 1)
text = text.replace(
    "import androidx.compose.ui.semantics.LiveRegionMode\n",
    "import androidx.compose.ui.semantics.LiveRegionMode\nimport androidx.compose.ui.semantics.Role\n",
    1,
)

old_padding = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HomeContent(uiState = uiState, actions = actions)
        }
"""
new_padding = """        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            HomeContent(uiState = uiState, actions = actions)
        }
"""
if old_padding not in text:
    raise SystemExit("HomeScreen content padding block not found")
text = text.replace(old_padding, new_padding, 1)

marker = "/** PRODUCT_SPEC LIB-002 — the four browse axes, one tap apart. */"
start = text.index(marker)
end = text.index("\nprivate fun HomeAxis.icon()", start)
replacement = '''/** PRODUCT_SPEC LIB-002 — the four browse axes, one tap apart. */
@Composable
private fun HomeAxisBar(
    current: HomeAxis,
    onAxisChanged: (HomeAxis) -> Unit,
    motion: HomeAxisBarMotionState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .followHomeAxisBarMotion(motion)
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = AXIS_BAR_GLASS_ALPHA),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AXIS_BAR_BORDER_ALPHA),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AXIS_BAR_HEIGHT)
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeAxis.entries.forEach { axis ->
                val selected = axis == current
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            onClick = { onAxisChanged(axis) },
                            role = Role.Tab,
                        ),
                    shape = CircleShape,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = AXIS_SELECTION_ALPHA)
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 5.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = axis.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(axis.labelRes()),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private val AXIS_BAR_HEIGHT = 46.dp
private const val AXIS_BAR_GLASS_ALPHA = 0.38f
private const val AXIS_BAR_BORDER_ALPHA = 0.42f
private const val AXIS_SELECTION_ALPHA = 0.46f
'''
text = text[:start] + replacement + text[end:]
path.write_text(text)
