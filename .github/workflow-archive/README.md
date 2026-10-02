# GitHub workflow archive

BookWave keeps only operational or reusable maintenance workflows under `.github/workflows/`.

Historical one-shot migration, repair, dependency-probe and PR-diagnostic workflows may still have
GitHub Actions workflow identities even after their files were removed. Run **Maintenance →
cleanup-retired-workflows** first with `cleanup_dry_run=true`, review the candidates, then rerun with
`cleanup_dry_run=false` to disable those retired identities.

The cleanup task never disables:
- a workflow whose file still exists in `.github/workflows/`;
- GitHub-managed workflow identities outside that path (for example Dependabot's dependency graph).

The previous standalone Contract capture and Codex environment compatibility workflows were consolidated
into **Maintenance** so their capabilities remain available without permanently occupying separate
workflow entries.
