package com.example.shelfplayer.feature.home

/** LIB-001/002, AUTH-002 / #228: count authorized Room-backed entities, without fetching another shelf. */
internal val HomeUiState.visibleEntityCount: Int
    get() = when (axis) {
        HomeAxis.Books -> if (booksView == BooksView.Shelves) {
            shelves.totalBookCount
        } else {
            books.asSequence().map { it.id }.distinct().count()
        }

        HomeAxis.Series -> series.asSequence().map { it.series.id }.distinct().count()

        HomeAxis.Authors, HomeAxis.Genres -> groups.asSequence().map { it.key }.distinct().count()
    }
