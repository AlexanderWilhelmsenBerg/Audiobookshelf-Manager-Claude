package com.example.shelfplayer.domain.usecase

import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-002 — the ways to read the same visible rows, injected as one.
 *
 * A holder rather than a parameter each, and not only to keep the count down: they move together. They
 * all read `visibleBooks`, they all apply the same grant, and a screen that offers the Books axis
 * offers the other axes too — there is no caller that wants one of them alone. Splitting them across a
 * constructor made that invisible and made the next axis a change to every ViewModel that browses.
 *
 * [searchServer] is the one that writes rather than observes, and it belongs here anyway: it exists to
 * put rows in front of [books], and a screen that searches is by definition a screen that browses.
 */
class BrowseUseCases @Inject constructor(
    val books: ObserveAccessibleBooksUseCase,
    val shelves: ObserveHomeShelvesUseCase,
    val series: ObserveSeriesShelvesUseCase,
    val groups: ObserveBookGroupsUseCase,
    val searchServer: SearchServerUseCase,
)
