package com.example.shelfplayer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.feature.book.BookRoute
import com.example.shelfplayer.feature.downloads.DownloadsRoute
import com.example.shelfplayer.feature.home.HomeRoute
import com.example.shelfplayer.feature.metadata.EditMetadataScreen
import com.example.shelfplayer.feature.onboarding.SignInRoute
import com.example.shelfplayer.feature.onboarding.SignInViewModel
import com.example.shelfplayer.feature.profiles.ProfileSwitcherRoute
import com.example.shelfplayer.feature.series.SeriesRoute
import com.example.shelfplayer.feature.settings.SettingsRoute
import com.example.shelfplayer.feature.users.ServerUsersScreen

/**
 * PRODUCT_SPEC 6.1 / AUTH-002 — where the app opens, and how it gets back to sign-in.
 *
 * [startDestination] is decided by the caller from observed state rather than read once here. The
 * difference matters in one direction: removing the last profile has to return the user to onboarding, and
 * a start destination captured at first composition never would.
 */
@Composable
fun ShelfPlayerNavHost(
    startDestination: String,
    onBookPlaySelected: (LibraryItemId) -> Unit,
    playbackMessage: String?,
    onPlaybackMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable(
            route = ShelfDestinations.SIGN_IN,
            arguments = listOf(
                // Defaulted rather than nullable, so `sign-in` with no query resolves and the ViewModel
                // reads "" instead of having to distinguish absent from empty.
                navArgument(SignInViewModel.ARG_SERVER_URL) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(SignInViewModel.ARG_USERNAME) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            SignInRoute(
                onSignedIn = {
                    // The sign-in screen is popped, not stacked under home: pressing back from a freshly
                    // signed-in home must not return to a password field. `popUpTo` names the route
                    // pattern, which matches the entry whatever arguments it carries.
                    navController.navigate(ShelfDestinations.HOME) {
                        popUpTo(ShelfDestinations.SIGN_IN) { inclusive = true }
                    }
                },
            )
        }
        composable(ShelfDestinations.HOME) {
            HomeRoute(
                onBookSelected = { bookId ->
                    navController.navigate(ShelfDestinations.book(bookId))
                },
                onBookPlaySelected = onBookPlaySelected,
                onSeriesSelected = { seriesId ->
                    navController.navigate(ShelfDestinations.series(seriesId))
                },
                onProfilesSelected = { navController.navigate(ShelfDestinations.PROFILES) },
                onSettingsSelected = { navController.navigate(ShelfDestinations.SETTINGS) },
                onSignInSelected = { navController.navigate(ShelfDestinations.signIn()) },
                playbackMessage = playbackMessage,
                onPlaybackMessageShown = onPlaybackMessageShown,
            )
        }
        composable(ShelfDestinations.PROFILES) {
            ProfileSwitcherRoute(
                onNavigateUp = navController::navigateUp,
                onAddProfile = { navController.navigate(ShelfDestinations.signIn()) },
                // PRODUCT_SPEC AUTH-004 — reauthenticating carries the address and username the app
                // already has, so only the password is asked for again.
                onSignInAgain = { serverUrl, username ->
                    navController.navigate(ShelfDestinations.signIn(serverUrl, username))
                },
            )
        }
        composable(ShelfDestinations.SETTINGS) {
            // PRODUCT_SPEC SET-002 / 6.1 step 9 — Settings *scopes* the shelf; it is not a second place
            // to browse from. There used to be a library screen behind this list with its own tabs,
            // search and sort chips, which a device run called out as "two different places for the same
            // functions". Choosing a library here narrows the home screen, and the home screen is where
            // browsing happens.
            SettingsRoute(
                onNavigateUp = navController::navigateUp,
                onManageDownloads = { navController.navigate(ShelfDestinations.DOWNLOADS) },
                onManageServerUsers = { navController.navigate(ShelfDestinations.SERVER_USERS) },
            )
        }
        // PRODUCT_SPEC DL-003 / ADR-0018 decision 6 — reachable from Settings and from a book's own menu,
        // because both are places somebody wonders where their space went.
        composable(ShelfDestinations.DOWNLOADS) {
            DownloadsRoute(onNavigateUp = navController::navigateUp)
        }
        composable(
            route = ShelfDestinations.BOOK,
            arguments = listOf(
                navArgument(ShelfDestinations.ARG_BOOK_ID) { type = NavType.StringType },
            ),
        ) {
            BookRoute(
                onNavigateUp = navController::navigateUp,
                onManageDownloads = { navController.navigate(ShelfDestinations.DOWNLOADS) },
                onEditMetadata = { bookId -> navController.navigate(ShelfDestinations.editMetadata(bookId)) },
            )
        }
        composable(ShelfDestinations.SERVER_USERS) {
            ServerUsersScreen(onBack = navController::navigateUp)
        }
        composable(
            route = ShelfDestinations.EDIT_METADATA,
            arguments = listOf(
                navArgument(ShelfDestinations.ARG_BOOK_ID) { type = NavType.StringType },
            ),
        ) {
            EditMetadataScreen(onBack = navController::navigateUp)
        }
        composable(
            route = ShelfDestinations.SERIES,
            arguments = listOf(
                navArgument(ShelfDestinations.ARG_SERIES_ID) { type = NavType.StringType },
            ),
        ) {
            SeriesRoute(
                onBookSelected = { bookId ->
                    navController.navigate(ShelfDestinations.book(bookId))
                },
                onNavigateUp = navController::navigateUp,
            )
        }
    }
}
