# Contributing to NexCompress

Thanks for helping improve NexCompress. This is a proprietary project; by
contributing you agree your contributions are licensed to the copyright holder.

## Branching model

- **`main`** — production-ready. Protected; merge only via reviewed PR. Tagged for releases.
- **`develop`** — integration branch. Day-to-day work merges here first.
- **`backup`** — safety mirror of `main`.
- **`feature/<name>`**, **`fix/<name>`** — short-lived topic branches off `develop`.

```
feature/* ─▶ develop ─▶ (release) ─▶ main ─▶ tag vX.Y.Z
                                       └────▶ backup (mirror)
```

## Workflow

1. Branch from `develop`: `git checkout -b feature/my-thing develop`
2. Build & verify locally:
   ```bash
   ./gradlew :app:assembleDebug
   ./gradlew :app:lintDebug
   ```
3. Open a PR into `develop`. CI (build + lint) must pass.
4. Releases: merge `develop` → `main`, tag `vX.Y.Z`, update `backup`.

## Commit style

Conventional Commits are encouraged:

```
feat: add Images → PDF reordering
fix: never emit a PDF larger than the source
docs: document online conversion config
```

## Code conventions

- **Architecture:** keep the `data` / `domain` / `ui` separation. UI talks to
  ViewModels; ViewModels use repositories/processors via the `AppContainer`.
- **Threading:** heavy file work runs on `Dispatchers.IO`; guard every
  bitmap/stream/PDF path against `OutOfMemoryError` and corruption.
- **No secrets in VCS.** API keys come from `gradle.properties`; signing keys stay local.
- **Compose:** Material 3, follow the existing screen/component structure.

## Reporting issues

Use the issue templates (Bug report / Feature request). Include device, Android
version, and reproduction steps.
