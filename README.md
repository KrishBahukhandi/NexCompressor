<div align="center">

# ⚡ NexCompress

**A privacy-first, offline file utility & converter for Android.**

Compress PDFs, convert images, and turn files between formats — almost entirely
**on-device**, with no account and no servers in the loop.

[![Android CI](https://github.com/KrishBahukhandi/NexCompressor/actions/workflows/android.yml/badge.svg)](https://github.com/KrishBahukhandi/NexCompressor/actions/workflows/android.yml)
![Platform](https://img.shields.io/badge/platform-Android%2026%2B-3DDC84)
![Language](https://img.shields.io/badge/kotlin-2.0-7F52FF)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)

</div>

---

## ✨ Features

### On-device (offline, native — no network)
| Tool | Description |
|------|-------------|
| **Compress PDF** | Recompresses the images inside the document (Recommended / Balanced / High-Fidelity profiles) while text & vectors stay sharp — never produces a larger file. |
| **Convert Images** | Any image (JPG/PNG/HEIC/BMP/WebP) → **JPG / PNG / WebP**, batch up to 5, per-file rename, drag-to-reorder. |
| **PDF → Images** | Export every PDF page as JPG / PNG / WebP. |
| **Images → PDF** | Combine photos into a single multi-page PDF (reorderable pages). |
| **Text → PDF** | Lay a `.txt` file out into a clean, paginated A4 PDF. |

### Edit & sign (offline, lossless PDF engine)
Powered by an on-device [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android)
engine — text stays selectable and **nothing is uploaded**.
| Tool | Description |
|------|-------------|
| **Sign PDF** | Draw a signature with your finger, then drag & resize it onto any page. The signed page is flattened; every other page stays lossless. |
| **Edit PDF pages** | Reorder (drag), rotate, or delete pages, then export. |
| **Merge PDFs** | Concatenate several PDFs into one (reorderable). |
| **Split PDF** | Extract a chosen set of pages into one PDF, or burst every page into its own file. |
| **Protect PDF** | Lock a PDF with a password (AES/standard security) or unlock a protected one — the password never leaves the device. |
| **Image Studio** | Rotate, flip, crop (draggable frame) and resize an image, then re-encode to JPG / PNG / WebP. |

### Office conversions (sidebar)
Four run **fully on-device** (content-faithful; complex layout simplified):
**Word → PDF**, **Excel → PDF**, **PDF → Word** (text-focused) and
**PDF → PowerPoint** (each page becomes a full-bleed slide image). Modern
formats only (.docx/.xlsx) — legacy .doc/.xls need the online service.
**PowerPoint → PDF** and **PDF → Excel** have no faithful offline equivalent
and require an optional conversion-service API key (see
[Online conversions](#-online-conversions)); without one they fail with a
clear message instead of producing placeholder output.

### Throughout
- 📊 **Performance ledger** — cumulative storage reclaimed + file history (Room).
- ✏️ Rename / 🔗 share / 👁 preview any output; files saved to `Downloads/NexCompress`.
- 🔒 Scoped-storage compliant, **no runtime storage permissions**.
- 💰 AdMob banner + interstitial bridge (test IDs included).

## 📱 Screenshots

| Home & ledger | Conversion suite | Image batch | Results |
|:---:|:---:|:---:|:---:|
| ![Home](docs/screenshots/01-home.png) | ![Drawer](docs/screenshots/02-conversions-drawer.png) | ![Batch](docs/screenshots/03-image-batch.png) | ![Results](docs/screenshots/04-results.png) |

## 🏗️ Architecture

Clean, decoupled layers with lightweight **manual DI** (no annotation-processing
DI framework) and a single-Activity Jetpack Compose UI:

```
com.nexcompress.app
├── data/
│   ├── local/        # Room entity, DAO, database (history ledger)
│   ├── processor/    # PdfCompressor, ImageConverter/Editor, PdfToImage, ImagesToPdf, TxtToPdf,
│   │                 #   PdfPageEditor, PdfMerger, PdfSplitter, PdfProtector, PdfSigner, FileStorageManager
│   ├── remote/       # OnlineConversionService + RestConversionService (configurable)
│   └── repository/   # HistoryRepository
├── domain/model/     # Sealed CompressionState, enums, result models
├── di/               # AppContainer (manual DI)
└── ui/               # Compose screens, navigation, theme, shared ViewModel
```

- **Heavy work** runs on Kotlin Coroutines (`Dispatchers.IO`); every bitmap/stream/PDF
  path is guarded against OOM & corruption.
- **State** is a sealed `CompressionState` (`Idle / Loading / Success / Error`).
- **No backend** except the AdMob SDK and the optional, pluggable conversion service.

## 🧰 Tech stack

Kotlin 2.0 · Jetpack Compose (Material 3) · Coroutines · Room · Navigation-Compose ·
[PDFBox-Android](https://github.com/TomRoush/PdfBox-Android) (offline PDF engine) ·
Google Mobile Ads · AGP 8.7 / Gradle 8.9 · `minSdk 26`, `compileSdk 35`.

## 🚀 Build & run

**Requirements:** JDK 17, Android SDK (Platform 35 + Build-Tools 35).

```bash
# Point the build at your SDK (or open in Android Studio, which writes this)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

> `local.properties` is intentionally **git-ignored** (machine-specific).

## 🌐 Online conversions

The online Office conversions use a **provider-agnostic REST client** configured at
build time — secrets stay out of version control:

```properties
# ~/.gradle/gradle.properties  (NOT committed)
CONVERT_API_KEY=your_api_key
CONVERT_BASE_URL=https://v2.convertapi.com   # default; any compatible endpoint works
```

- **No key** → built-in **demo mode** (simulated round-trip + labelled placeholder PDF).
- **Key set** → real multipart upload → convert → download.
- Swap providers (CloudConvert, self-hosted Gotenberg, your own proxy) by implementing
  `OnlineConversionService` — the UI doesn't change.

> **Production note:** a `BuildConfig` key is embedded in the APK and can be extracted.
> For release, route these calls through a thin backend proxy that holds the key.

## 📣 AdMob

Ships with Google's **official test** ad unit IDs and sample App ID. Replace them
(and the `APPLICATION_ID` in `AndroidManifest.xml`) before publishing.

## 🌿 Branching model

| Branch | Purpose |
|--------|---------|
| `main` | Production-ready, release-tagged. Protected. |
| `develop` | Integration branch for ongoing work. |
| `backup` | Safety mirror of `main`. |
| `feature/*`, `fix/*` | Short-lived topic branches → PR into `develop`. |

Releases are tagged `vMAJOR.MINOR.PATCH` (e.g. `v1.0.0`).

## 📄 License

Proprietary — © 2026 Krish Bahukhandi. All rights reserved. See [LICENSE](LICENSE).
