package dev.ebookd.reader.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.ebookd.reader.ui.account.AccountScreen
import dev.ebookd.reader.ui.detail.BookDetailScreen
import dev.ebookd.reader.ui.downloads.DownloadsScreen
import dev.ebookd.reader.ui.library.LibraryScreen
import dev.ebookd.reader.ui.login.LoginScreen
import dev.ebookd.reader.ui.reader.ReaderScreen
import dev.ebookd.reader.ui.serversetup.ServerSetupScreen
import dev.ebookd.reader.ui.shelf.ShelfScreen

object Routes {
    const val SERVER = "server"
    const val LOGIN = "login"
    const val SHELF = "shelf"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val ACCOUNT = "account"

    /** The book id is a path argument, which is what makes the detail screen restorable after a
     * process death instead of depending on a value that only lived in memory. */
    const val BOOK = "book/{bookId}"

    /** Same reason for the reader: reopening after the system reclaimed the process has to be able
     * to find the book again without anything that only lived in a ViewModel. */
    const val READER = "reader/{bookId}"

    fun book(id: String): String = "book/$id"

    fun reader(id: String): String = "reader/$id"
}

@androidx.compose.runtime.Composable
fun EbookdNavHost(startRoute: String, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = startRoute) {

        composable(Routes.SERVER) {
            ServerSetupScreen(
                onConfigured = {
                    // The address screen is finished with once it has an address; leaving it on the
                    // back stack would let the reader "go back" into a form that would overwrite a
                    // working configuration.
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SERVER) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onSignedIn = {
                    navController.navigate(Routes.SHELF) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.SHELF) {
            ShelfScreen(
                onOpenBook = { id -> navController.navigate(Routes.book(id)) },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
            )
        }

        composable(Routes.DOWNLOADS) {
            DownloadsScreen(onOpenBook = { id -> navController.navigate(Routes.book(id)) })
        }

        composable(Routes.ACCOUNT) {
            AccountScreen()
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onBack = { navController.popBackStack() },
                onOpenBook = { id -> navController.navigate(Routes.book(id)) },
            )
        }

        composable(
            route = Routes.BOOK,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) {
            BookDetailScreen(
                onBack = { navController.popBackStack() },
                onRead = { id -> navController.navigate(Routes.reader(id)) },
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            // The default is a 700 ms cross-fade, and that fade is not free: it keeps the main thread
            // drawing 25–35 ms frames for its whole duration, and the reader screen's first page has
            // to queue behind them. Measured on the emulator, the open's continuation waited 119–125
            // ms for a main thread that was busy fading — three times the open itself. The page simply
            // arriving is also the honest picture: by then the book has been on disk for 30 ms.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            ReaderScreen(onBack = { navController.popBackStack() })
        }
    }
}
