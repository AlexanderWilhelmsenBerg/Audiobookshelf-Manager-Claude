package com.example.shelfplayer.navigation

import androidx.navigation.NavHostController

/** Navigation owns root/pushed context; route Back never means wizard Change Server. */
internal fun NavHostController.signInBackAction(): (() -> Unit)? = if (previousBackStackEntry == null) {
    null
} else {
    { navigateUp() }
}
