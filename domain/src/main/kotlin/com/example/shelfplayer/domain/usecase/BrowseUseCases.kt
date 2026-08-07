package com.example.shelfplayer.domain.usecase

import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-002 — the four ways to read the same visible rows, injected as one.
 *
 * A holder rather than four constructor parameters, and not only to keep the parameter count down: the
 * four move together. They all read `visibleBooks`, they all apply the same grant, and a screen that
 * offers the Books axis offers the other three — there is no caller that wants one of them alone.
 * Splitting them across a constructor made that invisible and made the next axis a change to every
 * ViewModel that browses.
 */
class BrowseUseCases @Inject constructor(
    val books: ObserveAccessibleBooksUseCase,
    val shelves: ObserveHomeShelvesUseCase,
    val series: ObserveSeriesShelvesUseCase,
    val groups: ObserveBookGroupsUseCase,
)
