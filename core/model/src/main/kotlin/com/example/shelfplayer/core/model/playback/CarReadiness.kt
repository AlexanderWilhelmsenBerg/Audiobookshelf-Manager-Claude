package com.example.shelfplayer.core.model.playback

import java.time.Instant

/**
 * PRODUCT_SPEC PLAY-001 / ROUTE-002 — why the app is, or is not, in the car's list of media apps.
 *
 * ### Why a reading rather than a fix
 *
 * Two device runs reported the same thing: *"the app doesn't show among the apps"*. The first had a real
 * cause — the `com.google.android.gms.car.application` metadata was missing, and Android Auto enumerates
 * media apps by that and nothing else. The second run had the metadata (it is in the shipped APK, verified
 * against the binary manifest) and the app still did not appear, which means the remaining causes are all
 * **outside the APK**: whether Android Auto is installed, whether it will show an app it did not get from
 * Play, and whether it has ever managed to bind.
 *
 * None of those can be guessed from inside the app, and every guess costs a drive. So the app asks the
 * platform the same four questions Android Auto asks, and prints the answers. A reading that says
 * "declared: yes, installed from: sideloaded, ever connected: never" is a diagnosis; another round of
 * "it doesn't show" is not.
 *
 * @property isDeclared whether this package declares the car metadata that makes it a media app to Android
 *   Auto. `false` is the wave 5 defect, and would mean it regressed.
 * @property hasBrowserService whether an **exported** service in this package answers
 *   `android.media.browse.MediaBrowserService`. Auto binds to that and nothing else; a service that is not
 *   exported is a service the car cannot reach.
 * @property isAndroidAutoInstalled whether Android Auto is on the phone at all.
 * @property installer the package that installed this build, or `null` when nothing did — which is what
 *   `adb install` and a downloaded APK both look like. **This is the field that usually explains it:**
 *   Android Auto hides media apps it did not get from Play unless *Unknown sources* is turned on in its
 *   developer settings, and turning on developer settings is not the same as turning that on.
 * @property lastConnectedAt when a car last connected to this app's session, or `null` for never. Held in
 *   memory, so it is only as old as the process — but "never, and the app has been running all drive" is
 *   exactly the observation that separates a discovery problem from a browse-tree problem.
 */
data class CarReadiness(
    val isDeclared: Boolean = false,
    val hasBrowserService: Boolean = false,
    val isAndroidAutoInstalled: Boolean = false,
    val installer: String? = null,
    val lastConnectedAt: Instant? = null,
) {
    /** Whether this build came from an app store rather than from a cable. */
    val isSideloaded: Boolean get() = installer == null

    /**
     * Whether everything the *APK* controls is right.
     *
     * Deliberately not "the car will work": the two fields it leaves out are the phone's business, and an
     * app that reported readiness on their behalf would be making the same promise that produced two
     * fruitless device runs.
     */
    val isDeclaredCorrectly: Boolean get() = isDeclared && hasBrowserService
}
