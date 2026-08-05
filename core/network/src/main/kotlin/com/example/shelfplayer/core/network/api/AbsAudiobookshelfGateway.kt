package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.AuthApi
import com.example.shelfplayer.core.network.gateway.CapabilityResolver
import com.example.shelfplayer.core.network.gateway.LibraryApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 10.4 — the Retrofit-backed gateway, assembled from its sub-APIs.
 *
 * It holds no state and makes no calls of its own. Each sub-API is a separate class because each has a
 * separate contract to be tested against, and a single class implementing all of them would make one
 * fixture change touch everything.
 *
 * This is what replaces `FakeAudiobookshelfGateway` in the `:app` graph. The fake is not deleted: it
 * backs the repository tests and the bundled demo document, and PRODUCT_SPEC 17.1 prefers a hand-written
 * fake to a mock.
 */
@Singleton
internal class AbsAudiobookshelfGateway @Inject constructor(
    override val auth: AuthApi,
    override val capabilities: CapabilityResolver,
    override val library: LibraryApi,
) : AudiobookshelfGateway
