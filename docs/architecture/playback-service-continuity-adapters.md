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
