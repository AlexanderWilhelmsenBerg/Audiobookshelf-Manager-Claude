package com.example.shelfplayer.core.model.download

/**
 * PRODUCT_SPEC DL-004 / ADR-0018 decision 5 — what each kind of traffic may use.
 *
 * ### Wi-Fi is not a setting
 *
 * The owner's words: *"you should be able to turn cellular on for downloads and smart download, but you
 * can't turn off wifi."* So each category is one boolean — *may this also use cellular* — rather than the
 * three-way picker PRODUCT_SPEC DL-004 sketches. A category with no allowed network is not a preference
 * anybody holds; it is a broken app with an explanation.
 *
 * > **Deviation from DL-004**, recorded in ADR-0018. The requirement lists "Wi-Fi only / Wi-Fi and cellular
 * > / Ask on cellular" for streaming and manual downloads. The *Ask* option is not built: an interruption
 * > that appears when a listener presses play, on a phone in a pocket, is a worse answer than a setting they
 * > chose once. The requirement's intent — that cellular is never spent by surprise — is met by the
 * > defaults.
 *
 * ### The defaults differ on purpose
 *
 * Streaming a chapter costs a few megabytes and somebody pressing play on a train wants it to work.
 * Downloading a book costs hundreds and is nearly always something they meant to do at home.
 *
 * @property streamingOnCellular `true` by default.
 * @property downloadsOnCellular `false` by default.
 * @property smartDownloadsOnCellular `false` by default. Separate from [downloadsOnCellular] because a
 *   manual download is a decision the user just made and a smart one is the app deciding for them —
 *   spending cellular on the second without being asked is a different thing entirely.
 */
data class NetworkPolicy(
    val streamingOnCellular: Boolean = true,
    val downloadsOnCellular: Boolean = false,
    val smartDownloadsOnCellular: Boolean = false,
) {
    /** Whether [category] may run on the network described by [isUnmetered]. */
    fun allows(category: TrafficCategory, isUnmetered: Boolean): Boolean = isUnmetered || allowsCellular(category)

    /** Whether [category] may use a metered connection at all. */
    fun allowsCellular(category: TrafficCategory): Boolean = when (category) {
        TrafficCategory.Streaming -> streamingOnCellular
        TrafficCategory.ManualDownload -> downloadsOnCellular
        TrafficCategory.SmartDownload -> smartDownloadsOnCellular
    }

    companion object {
        /** What a device that has never opened the setting uses. */
        val Default: NetworkPolicy = NetworkPolicy()
    }
}

/**
 * The three kinds of traffic DL-004 distinguishes.
 *
 * Deliberately about *why* bytes are moving rather than about which component moves them. Cover art
 * fetched to render a shelf is streaming-shaped; the same bytes fetched as part of a download are not, and
 * a category keyed on the component could not tell them apart.
 */
enum class TrafficCategory {
    /** Audio played from the server as it arrives. */
    Streaming,

    /** A download the user asked for by pressing a button. */
    ManualDownload,

    /** PRODUCT_SPEC DL-005 — a download the app decided to start on its own. */
    SmartDownload,
}
