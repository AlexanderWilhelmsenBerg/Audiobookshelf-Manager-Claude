package com.example.shelfplayer.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.shelfplayer.feature.book.BookRoute
import com.example.shelfplayer.feature.home.HomeRoute
import com.example.shelfplayer.feature.library.LibraryRoute
import com.example.shelfplayer.feature.onboarding.SignInRoute
import com.example.shelfplayer.feature.profiles.ProfileSwitcherRoute

/**
 * PRODUCT_SPEC 6.1 / AUTH-002 — where the app opens, and how it gets back to sign-in.
 *
 * [startDestination] is decided by the caller from observed state rather than read once here. The
 * difference matters in one direction: removing the last profile has to return the user to onboarding, and
 * a start destination captured at first composition never would.
 */
@Composable
fun ShelfPlayerNavHost(startDestination: String, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(ShelfDestinations.SIGN_IN) {
            SignInRoute(
                onSignedIn = {
                    // The sign-in screen is popped, not stacked under home: pressing back from a freshly
                    // signed-in home must not return to a password field.
                    navController.navigate(ShelfDestinations.HOME) {
                        popUpTo(ShelfDestinations.SIGN_IN) { inclusive = true }
                    }
                },
            )
        }
        composable(ShelfDestinations.HOME) {
            HomeRoute(
                onLibrarySelected = { libraryId ->
                    navController.navigate(ShelfDestinations.library(libraryId))
                },
                onProfilesSelected = { navController.navigate(ShelfDestinations.PROFILES) },
                onSignInSelected = { navController.navigate(ShelfDestinations.SIGN_IN) },
            )
        }
        composable(ShelfDestinations.PROFILES) {
            ProfileSwitcherRoute(
                onNavigateUp = navController::navigateUp,
                onAddProfile = { navController.navigate(ShelfDestinations.SIGN_IN) },
            )
        }
        composable(
            route = ShelfDestinations.LIBRARY,
            arguments = listOf(
                navArgument(ShelfDestinations.ARG_LIBRARY_ID) { type = NavType.StringType },
            ),
        ) {
            LibraryRoute(
                onBookSelected = { bookId ->
                    navController.navigate(ShelfDestinations.book(bookId))
                },
                onNavigateUp = navController::navigateUp,
            )
        }
        composable(
            route = ShelfDestinations.BOOK,
            arguments = listOf(
                navArgument(ShelfDestinations.ARG_BOOK_ID) { type = NavType.StringType },
            ),
        ) {
            BookRoute(onNavigateUp = navController::navigateUp)
        }
    }
}
