from pathlib import Path

controller_path = Path("playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackController.kt")
controller = controller_path.read_text()

controller_import = "import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository\n"
controller_import_replacement = (
    controller_import
    + "import com.example.shelfplayer.domain.usecase.OpenPlaybackSessionUseCase\n"
)
if controller.count(controller_import) != 1:
    raise SystemExit("PlaybackController import anchor did not match exactly once")
controller = controller.replace(controller_import, controller_import_replacement, 1)

controller_field = "    private val playbackRepository: PlaybackRepository,\n"
controller_field_replacement = (
    controller_field
    + "    private val openPlaybackSession: OpenPlaybackSessionUseCase,\n"
)
if controller.count(controller_field) != 1:
    raise SystemExit("PlaybackController constructor anchor did not match exactly once")
controller = controller.replace(controller_field, controller_field_replacement, 1)

controller_call = "return when (val opened = playbackRepository.openSession(bookId)) {"
if controller.count(controller_call) != 1:
    raise SystemExit("PlaybackController openSession call did not match exactly once")
controller = controller.replace(
    controller_call,
    "return when (val opened = openPlaybackSession(bookId)) {",
    1,
)
controller_path.write_text(controller)

service_path = Path("playback/src/main/kotlin/com/example/shelfplayer/playback/PlaybackService.kt")
service = service_path.read_text()

service_import = "import com.example.shelfplayer.domain.usecase.NextInSeriesUseCase\n"
service_import_replacement = (
    "import com.example.shelfplayer.domain.usecase.OpenPlaybackSessionUseCase\n"
    + service_import
)
if service.count(service_import) != 1:
    raise SystemExit("PlaybackService import anchor did not match exactly once")
service = service.replace(service_import, service_import_replacement, 1)

service_field = "    @Inject\n    internal lateinit var playbackRepository: PlaybackRepository\n"
service_field_replacement = (
    service_field
    + "\n    @Inject\n"
    + "    internal lateinit var openPlaybackSession: OpenPlaybackSessionUseCase\n"
)
if service.count(service_field) != 1:
    raise SystemExit("PlaybackService injection anchor did not match exactly once")
service = service.replace(service_field, service_field_replacement, 1)

service_call = "when (val opened = playbackRepository.openSession(bookId)) {"
if service.count(service_call) != 1:
    raise SystemExit("PlaybackService openSession call did not match exactly once")
service = service.replace(
    service_call,
    "when (val opened = openPlaybackSession(bookId)) {",
    1,
)
service_path.write_text(service)
