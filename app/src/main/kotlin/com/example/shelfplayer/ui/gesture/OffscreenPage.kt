package com.example.shelfplayer.ui.gesture

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * PRODUCT_SPEC 2.10 / §51 — keeps a pager's off-screen pages out of the accessibility tree.
 *
 * ### The bug this exists for, which a test found before a person did
 *
 * A pager composes the pages either side of the visible one so that a drag reveals real content from its
 * first pixel. Composed means *present in the semantics tree*, and the semantics tree is what TalkBack
 * reads — so without this, a page nobody is looking at contributes its controls, its labels and, worst of
 * all, its **live regions**. Home's neighbouring axes render a loading state whose title is a polite live
 * region, and three of them would have announced "Loading your books" over whatever the reader was
 * actually on.
 *
 * It also makes the touch-target net honest. `assertEveryControlIsBigEnough` walks the tree for the tab
 * under test; with neighbours composed it was reaching into other tabs and reporting their controls
 * against the wrong screen's name.
 *
 * `clearAndSetSemantics {}` with an empty block removes the subtree's semantics entirely rather than
 * marking it hidden, which is the right answer for a page that is not on screen: there is nothing there to
 * describe yet. The page regains its semantics the moment it becomes current, which for a pager is at the
 * half-way point of the drag — by which time it is the page the reader is looking at.
 *
 * A modifier rather than a wrapper composable so it composes with whatever layout the page already has.
 */
internal fun Modifier.offscreenPage(isCurrent: Boolean): Modifier =
    if (isCurrent) this else this.clearAndSetSemantics { }
