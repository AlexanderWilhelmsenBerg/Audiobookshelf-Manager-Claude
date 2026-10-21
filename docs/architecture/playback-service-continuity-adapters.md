# PlaybackService continuity event simplification — stacked PR C

This PR is based on PR B, which is based on PR A; all three must remain unmerged
until combined physical hardware acceptance.

PlaybackService continues to own the single ExoPlayer and MediaLibrarySession,
lifecycle/source callbacks, focused UI/notification exposure, media-button
commands, and actual Play invocation. It now supplies an immutable snapshot of
service-owned route, book generation, explicit output selection, and car-session
status to CarContinuityRecoveryCoordinator.

The coordinator forwards the platform focus/noisy event and car/projection
boundary to the *existing* CarArrivalResumeGate. It does not add a second gate
or another generation counter. Actual route securing and one-shot consumption
are still consolidated as in PR B. The AudioOutputRouter still owns routing,
including muted AudioTrack destination verification; this change does not
attribute hardware media buttons or treat preferred output as actual route.

The tests cover both callback orders, both platform interruption reasons,
duplicate Play rejection, stale explicit intent, deliberate Pause, physical
projection departure versus weaker legacy-controller departure, and the
service's actual delegated entrypoint references. Existing Robolectric
CarProjectionLifecycleTest and all Phase 1C continuity and service tests
remain required by verifyDebug.

A CI-green PR C is the gate for producing the single **combined A+B+C debug APK**.
CI cannot establish audible car/headset routing. Hardware acceptance must
exercise the original-headset-to-car transition, headset Play versus car Play,
same-name Bluetooth endpoints, cold start, disconnect/reconnect, focus/noisy
ordering, and explicit Pause/new book/profile/output changes. Keep all three
PRs separate until this physical acceptance is satisfied.

## 2026-10-21 extended hardware regression and safety stabilization

The initial physical PASS of build 2237 was superseded by a second Android Auto
session: external Play after paused car reconnect played to a measured RemoteSubmix,
not the previously heard A2DP endpoint. Virtual media-button metadata cannot prove
which physical button issued Play. The car-Play path explicitly selects Car, whereas
unattributed external Play does not select a headset. That asymmetry also exists on
main and is not established as an A/B/C refactoring regression. After an explicit
Car choice, profile/book replacement, or lost endpoint identity, no old headset
claim may be recreated merely from a connected Bluetooth device.

This C stabilization tightens the **existing guarded-headset safety path**: when
the actual playing AudioTrack is unmeasured, a predicted AudioManager media route
must not authorize audible playback. The router now passes only measured track
route IDs to its guard and pauses rather than trusting framework prediction.
It does not infer media-button origin, silently lock a headset, or implement a
new output handoff. Headset lock is #276; verified destination handoff is #277.

The Robolectric tests exercise repeated measured headset -> RemoteSubmix ->
unmeasured route changes, explicit Car supersession, and the production wiring
constraint that media-policy prediction cannot authenticate the playing track.
**Hardware acceptance remains OPEN**: verify the safety pause and test an explicit
headset output selection on repeated car reconnects, including new profile/book
and cold start. Do not interpret a safety pause as successful headset routing.
