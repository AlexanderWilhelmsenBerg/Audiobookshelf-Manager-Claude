# BookWave GitHub Actions

Only two user-facing workflow files are maintained in `.github/workflows/`:

- **CI · Verification** (`pull-request.yml`): Quick/Standard/Intensive checks and main branch gates.
- **Build APK** (`apk.yml`): manual signed debug/release APKs and an explicitly requested, verified-PR APK handoff.

PR CI does not package an APK automatically. APK signing stays in its separately permissioned workflow.

## Supporting jobs within CI

- Main pushes run release lint, release unit tests, an SBOM, vulnerability scanning, and an unsigned release build in addition to the normal debug verification. The old separate weekly `main.yml` workflow is retired.
- Changes to `ci/bookwave-ci/` that reach trusted `main` build, verify, and publish the CI container image. The verification job continues using a pinned image digest until that pin is updated in a reviewed PR. No PR code can publish a CI image.
- A closed same-repository PR triggers cache cleanup without checking out or executing PR code.
- When a workflow-file change reaches `main`, a trusted housekeeping job disables old registered Actions workflows whose files no longer exist. It excludes GitHub-managed/dynamic workflows and does not erase historical run logs.

One-time upgrade, formatting, migration and diagnostic workflows must not be left permanently registered. The normal CI checks and the APK producer remain the supported surfaces. Contract fixture scripts and the Codex bootstrap remain available locally if explicitly needed; no recurring maintenance workflow runs them automatically.

Keep action tokens least-privileged, preserve stable APK signing, and never expose release secrets or privileged Actions/Packages tokens to PR-controlled code.
