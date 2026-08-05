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

@Composable
fun ShelfPlayerNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = ShelfDestinations.HOME) {
        composable(ShelfDestinations.HOME) {
            HomeRoute(
                onLibrarySelected = { libraryId ->
                    navController.navigate(ShelfDestinations.library(libraryId))
                },
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
