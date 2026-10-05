package com.example.shelfplayer.domain.library

/** A pending authorized query is distinct from a completed query with no available author. */
data class AuthorShelfObservation(val shelf: AuthorShelf? = null, val isLoading: Boolean = false)
