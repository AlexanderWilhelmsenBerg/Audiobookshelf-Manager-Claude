package com.example.shelfplayer.a11y

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp

/**
 * PRODUCT_SPEC §51 / 2.10 — the two accessibility properties that can be checked without a device.
 *
 * ### Why an assertion over the whole tree, and not one per control
 *
 * Because the failure this is guarding against is *the next screen*, not this one. A test that names each
 * button and checks its label passes forever and says nothing about the button somebody adds next week.
 * These walk everything the semantics tree reports as interactive, so a new unlabelled control fails a
 * test nobody had to remember to write.
 *
 * That is also the honest limit. This is the semantics tree, which is what TalkBack consumes — so an
 * unlabelled button really is a button TalkBack announces as "button" and nothing more. What it cannot
 * check is whether the label is *useful*, whether the contrast is sufficient, or what a real screen reader
 * does with the order. Those need eyes and hardware; `docs/risks.md` R-29 says so.
 */

/**
 * Every node the tree reports as clickable has something to announce.
 *
 * A control is labelled if it carries a content description, its own text, or — because these are read
 * from the *merged* tree — the text of a child it merges. That is exactly the rule TalkBack applies, so a
 * `Button { Text("Play") }` passes without needing a redundant description and an `IconButton` with a bare
 * `Icon` does not.
 */
fun ComposeContentTestRule.assertEveryControlIsLabelled() {
    val unlabelled = interactiveNodes().filterNot { node ->
        val config = node.config
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() }
        val text = config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() }
        description == true || text == true
    }

    check(unlabelled.isEmpty()) {
        "These controls announce nothing to a screen reader: " + unlabelled.joinToString { it.describe() }
    }
}

/**
 * Every interactive node is at least [MINIMUM_VISUAL_TARGET] in both directions.
 *
 * ### Why the threshold is 40dp and not 48dp
 *
 * 48dp is the platform's figure for a *touch target* — roughly a fingertip, and the size below which a
 * user with a tremor, or a passenger in a moving car, cannot reliably hit a control. This does not assert
 * 48, and the reason is worth writing down rather than quietly rounding.
 *
 * The first version of this asserted 48dp and immediately failed on the back arrow of every `TopAppBar`,
 * at 40dp by 40dp. That was a false positive. Material 3's `IconButton` draws a 40dp state layer while
 * `minimumInteractiveComponentSize` expands what the platform actually *dispatches* to 48dp — a finger
 * landing 4dp outside the ripple still presses the button. The rectangle that would prove it is
 * `touchBoundsInRoot`, and it is internal to Compose; the public test API offers only equality assertions
 * against a known size, which cannot express "at least".
 *
 * So this checks the visual bounds against 40dp, which is Material's own smallest component. That is not
 * vacuous: it still fails a 24dp icon used as a row's only control, a 32dp chip, and any hand-rolled
 * `Modifier.clickable` sized by its content — which is the failure mode this exists for, since Material's
 * components are correct by construction and hand-rolled rows are not.
 *
 * The remaining 8dp is a claim about Compose, not about this app, and the way to confirm it is TalkBack on
 * a device. `docs/risks.md` R-29 records that as owed.
 */
fun ComposeContentTestRule.assertEveryControlIsBigEnough() {
    val density = density
    val small = interactiveNodes().filter { node ->
        with(density) {
            node.size.width.toDp() < MINIMUM_VISUAL_TARGET || node.size.height.toDp() < MINIMUM_VISUAL_TARGET
        }
    }

    check(small.isEmpty()) {
        "These controls are under $MINIMUM_VISUAL_TARGET: " + small.joinToString { node ->
            with(density) { "${node.describe()} (${node.size.width.toDp()} x ${node.size.height.toDp()})" }
        }
    }
}

/**
 * Everything the tree says a user can act on.
 *
 * `OnClick` rather than a role or a type: a role is advisory and a `Modifier.clickable` on a plain `Row`
 * carries no role at all, which is precisely the case worth catching. Nodes with zero size are dropped —
 * a lazy list keeps placeholders for items it has not laid out, and they are not controls anybody can
 * reach.
 */
private fun ComposeContentTestRule.interactiveNodes(): List<SemanticsNode> =
    onAllNodes(SemanticsMatcherOnClick).fetchSemanticsNodes(atLeastOneRootRequired = false)
        .filter { node -> node.size.width > 0 && node.size.height > 0 }

private val SemanticsMatcherOnClick = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
    SemanticsActions.OnClick,
)

/** Enough to find the offender: what it says, or what it is, and where it is. */
private fun SemanticsNode.describe(): String {
    val text = config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
    val role = config.getOrNull(SemanticsProperties.Role)?.toString()
    return listOfNotNull(text, description, role, "id=$id").first { it.isNotBlank() }
}

/**
 * The smallest visual size a control may have.
 *
 * Material's own smallest component, and deliberately not the platform's 48dp touch target — see
 * [assertEveryControlIsBigEnough] for why the two differ and which one is checkable here.
 */
val MINIMUM_VISUAL_TARGET = 40.dp
