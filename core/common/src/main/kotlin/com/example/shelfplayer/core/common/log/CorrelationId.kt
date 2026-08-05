package com.example.shelfplayer.core.common.log

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 14.5 — correlation ids tie the events of one operation together.
 *
 * PRODUCT_SPEC PLAY-005 additionally requires offline listening sessions to carry a UUIDv4, and this
 * is the seam that makes that value deterministic in tests instead of a real random id.
 */
interface CorrelationIdGenerator {
    fun newId(): String
}

@Singleton
class UuidCorrelationIdGenerator @Inject constructor() : CorrelationIdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
