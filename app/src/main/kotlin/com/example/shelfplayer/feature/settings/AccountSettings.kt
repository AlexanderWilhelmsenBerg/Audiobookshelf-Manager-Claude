package com.example.shelfplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileRole

/**
 * PRODUCT_SPEC 5.1 / 5.2 / USER-001 — who is signed in, what the server lets them do, and the one screen
 * that is only theirs if they are an administrator.
 *
 * ### Why this section exists
 *
 * Because *Manage server accounts* used to be absent for a non-admin, and a row that is absent is
 * indistinguishable from a row that was never built. A device run reported exactly that — "there is no
 * manage server" — for an account that was working correctly and simply was not an administrator.
 *
 * So the row is now always drawn, and when it cannot be used it says why. The earlier reasoning was that a
 * disabled row promises that pressing it might one day work; the answer to that is a sentence naming the
 * account type, which promises nothing and answers the question the absence raised.
 *
 * ### Why the grants are shown as readings
 *
 * Four of PRODUCT_SPEC's management actions are gated on a server-side permission this app cannot change
 * (`ManagementPermissions`). When an edit button is missing, the honest question is "does my account have
 * the grant?" — and until this section existed, the only way to find out was to read the server's own web
 * interface. These rows are the answer, and they are readings: nothing here is a control, because none of
 * it is this app's to set.
 */
internal fun LazyListScope.accountSection(profile: Profile?, onManageServerUsers: () -> Unit) {
    item { SubHeader(text = stringResource(R.string.settings_section_account)) }

    if (profile == null) {
        item { Hint(text = stringResource(R.string.settings_account_none)) }
        return
    }

    // The username as the server spells it, which is what the person managing the server will search for.
    // A display name would be friendlier and would not match anything they can look up.
    item { TextRow(labelRes = R.string.settings_account_username, value = profile.username) }
    item {
        TextRow(
            labelRes = R.string.settings_account_role,
            value = stringResource(profile.role.labelRes()),
        )
    }

    item { GrantRow(labelRes = R.string.settings_account_may_download, granted = profile.canDownload) }
    item { GrantRow(labelRes = R.string.settings_account_may_update, granted = profile.canUpdate) }
    item { GrantRow(labelRes = R.string.settings_account_may_upload, granted = profile.canUpload) }
    item { GrantRow(labelRes = R.string.settings_account_may_delete, granted = profile.canDelete) }
    item { Hint(text = stringResource(R.string.settings_account_grants_hint)) }

    // PRODUCT_SPEC USER-001 — "only admin/root profiles can open server user management". Enforced here by
    // not being clickable, and again by the screen behind it, which re-reads the role rather than trusting
    // that it was reached legitimately.
    val isAdmin = profile.role == ProfileRole.Admin
    item {
        SettingsNavigationRow(
            label = stringResource(R.string.settings_server_users),
            onClick = onManageServerUsers,
            enabled = isAdmin,
            supportingText = if (isAdmin) null else stringResource(R.string.settings_server_users_blocked),
        )
    }
}

/** PRODUCT_SPEC 5.2 — one server-side permission, as a word rather than a tick. */
@Composable
private fun GrantRow(labelRes: Int, granted: Boolean) {
    TextRow(
        labelRes = labelRes,
        value = stringResource(
            if (granted) R.string.settings_account_allowed else R.string.settings_account_not_allowed,
        ),
    )
}

/**
 * PRODUCT_SPEC 5.1 — the role bucket, in the user's words.
 *
 * `Editor` and `Manager` have no Audiobookshelf account type behind them — `ProfileRole.ofAccountType` maps
 * `root` and `admin` to [ProfileRole.Admin] and everything else to [ProfileRole.Listener] — but the enum has
 * four entries and a `when` over it has to be exhaustive, so all four get a word.
 */
private fun ProfileRole.labelRes(): Int = when (this) {
    ProfileRole.Admin -> R.string.settings_account_role_admin
    ProfileRole.Manager -> R.string.settings_account_role_manager
    ProfileRole.Editor -> R.string.settings_account_role_editor
    ProfileRole.Listener -> R.string.settings_account_role_listener
}

/**
 * A row that opens another screen — or explains why it will not.
 *
 * A disabled `ListItem` rather than a hidden one, and [supportingText] is what makes that defensible: the
 * greyed row plus a sentence naming the reason is strictly more informative than nothing, which is what was
 * there before. Without the sentence it would be a dead control, and hiding it would be better.
 */
@Composable
internal fun SettingsNavigationRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        colors = if (enabled) {
            ListItemDefaults.colors()
        } else {
            ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = if (enabled) modifier.clickable(onClick = onClick) else modifier,
    )
}
