package com.zero7goat.tung.core.navigation

/**
 * Top-level destinations of TUNG.
 *
 * Each module (Notes, Passwords, Files, Settings) has one route. Detail and
 * folder destinations will be added under these as the features are built.
 */
sealed interface Route {
    val path: String

    data object Notes : Route {
        override val path = "notes"
    }

    data object Passwords : Route {
        override val path = "passwords"
    }

    data object Files : Route {
        override val path = "files"
    }

    data object Settings : Route {
        override val path = "settings"
    }

    companion object {
        val all: List<Route> = listOf(Notes, Passwords, Files, Settings)

        /** Resolves a saved path back to a route, falling back to [Notes]. */
        fun fromPath(path: String): Route = all.firstOrNull { it.path == path } ?: Notes
    }
}
