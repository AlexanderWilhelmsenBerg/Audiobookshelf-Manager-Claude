"""Architecture boundary regression fixtures: legitimate graph and unsafe imports."""

import importlib.util
import tempfile
import unittest
from pathlib import Path


TOOL = Path(__file__).resolve().parents[1] / "check_architecture_boundaries.py"
spec = importlib.util.spec_from_file_location("bookwave_architecture", TOOL)
assert spec and spec.loader
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ArchitectureBoundariesTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.write("settings.gradle.kts", 'include(":app")\ninclude(":domain")\ninclude(":core:model")\ninclude(":playback")\n')
        self.write("core/model/build.gradle.kts", 'plugins { id("shelfplayer.jvm.library") }\n')
        self.write("domain/build.gradle.kts", 'dependencies { api(projects.core.model) }\n')
        self.write("app/build.gradle.kts", 'dependencies { implementation(projects.playback) }\n')
        self.write("playback/build.gradle.kts", 'dependencies { implementation(projects.domain) }\n')

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def findings(self):
        return [message for _, _, message in guard.audit(self.root)[0]]

    def test_accepted_gradle_graph_and_media_controller(self):
        self.write("app/src/main/kotlin/feature/home/Home.kt",
                   "package example\nimport androidx.media3.session.MediaController\n")
        self.assertEqual([], self.findings())

    def test_domain_must_not_depend_on_android_playback(self):
        self.write("domain/build.gradle.kts", 'implementation(projects.playback)\n')
        self.assertTrue(any(":domain must not depend on :playback" in x for x in self.findings()))

    def test_model_must_not_depend_on_network(self):
        self.write("core/model/build.gradle.kts", 'api(projects.core.network)\n')
        self.assertTrue(any(":core:model must not depend on :core:network" in x for x in self.findings()))

    def test_forbid_data_to_ui_even_with_colon_project_syntax(self):
        self.write("settings.gradle.kts",
                   (self.root / "settings.gradle.kts").read_text() + 'include(":data:library")\n')
        self.write("data/library/build.gradle.kts", 'implementation(project(":app"))\n')
        self.assertTrue(any(":data:library must not depend on :app" in x for x in self.findings()))

    def test_only_playback_can_import_media_session_and_exoplayer(self):
        self.write("app/src/main/kotlin/feature/book/Player.kt",
                   "import androidx.media3.exoplayer.ExoPlayer\n"
                   "import androidx.media3.session.MediaSession\n")
        messages = self.findings()
        self.assertEqual(2, sum("ownership belongs to :playback" in x for x in messages))

    def test_feature_cannot_import_network_dto(self):
        self.write("app/src/main/kotlin/feature/home/HomeViewModel.kt",
                   "import com.example.shelfplayer.core.network.AudiobookshelfApi\n")
        self.assertTrue(any("repository/domain contracts" in x for x in self.findings()))

    def test_comments_cannot_create_false_dependencies(self):
        self.write("domain/build.gradle.kts",
                   "/* implementation(projects.playback) */\n"
                   "implementation(projects.core.model) // allowed\n")
        self.assertEqual([], self.findings())

    def test_test_only_imports_do_not_constrain_production_policy(self):
        self.write("app/src/test/kotlin/FakePlayer.kt", "import androidx.media3.exoplayer.ExoPlayer\n")
        self.assertEqual([], self.findings())

    def test_other_modules_must_not_add_exoplayer_gradle_dependency(self):
        self.write("app/build.gradle.kts", "implementation(libs.androidx.media3.exoplayer)\n")
        self.assertTrue(any("must not declare Media3 ExoPlayer" in x for x in self.findings()))


if __name__ == "__main__":
    unittest.main()
