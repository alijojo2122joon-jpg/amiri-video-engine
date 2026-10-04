# 🐾 Amiri Video Engine

A personal Android app for the **realme GT3**: give it a prompt (and optionally a first frame, a last frame or a reference image), press **GENERATE VIDEO**, get a real AI video.

Simple on the outside, modular underneath.

---

## What it does

| You give | You get |
|---|---|
| Prompt only | Text-to-video |
| Prompt + one image | The image comes to life (first-frame-to-video) |
| Prompt + first frame + last frame | A controlled transition between the two |
| Prompt + two images (no roles) | The engine decides: if the prompt describes a change ("turns into", "becomes"…) they become start/end frames |

Nothing is faked: if no engine can do a request, the app says so.

## Engines (providers) currently implemented

| Engine | Cost | Can do | Notes |
|---|---|---|---|
| **LTX Video** — `Lightricks/ltx-video-distilled` (Hugging Face Space) | Free | text→video, first frame→video | Fastest |
| **Wan 2.2** — `zerogpu-aoti/wan2-2-fp8da-aoti-faster` | Free | first frame→video | Best free quality |
| **Wan 2.2 First→Last** — `multimodalart/wan-2-2-first-last-frame` | Free | first + last frame transition | |
| **Your own Spaces** (added in Settings) | Free | detected automatically | |
| **Google Veo 3.1** (official Gemini API) | Paid, your key | everything, with audio | Only 9:16 and 16:9 |
| **xAI Grok Imagine** (official xAI API) | Paid, your key | text→video, first frame→video | |
| **On-device** | — | **NOT AVAILABLE** | See "Local generation" |

The free engines run open-source models on Hugging Face's free ZeroGPU. There is a **daily free GPU allowance**, so it is free but not unlimited. Clips are short (about 2–5 s). Use **CONTINUE FROM LAST FRAME** on the result screen to chain clips into longer videos.

## Install (phone only)

1. Open the repository's **Releases** page on GitHub.
2. Download `AmiriVideoEngine.apk` from the newest release.
3. Open it and allow "install unknown apps" for your browser / file manager when Android asks.

Every push to `main` builds a new APK automatically (`.github/workflows/build.yml`). All builds are signed with the same key, so new versions install over old ones and keep your projects.

## Build (if you ever have a PC)

```
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```
Requires JDK 17+. Min Android 10, target Android 15.

## Where to put keys / tokens

**In the app:** `⚙ Settings → AI Providers`.

- **Hugging Face token (free, optional, recommended)** — huggingface.co → Settings → Access Tokens → New token (type *Read*). Gives you more free GPU time.
- **Google Gemini API key** — only for Veo (paid).
- **xAI API key** — only for Grok Imagine (paid).

Keys are encrypted with an AES-256-GCM key that lives in the **Android Keystore**. They are never in the source code, never in Git, never logged, never shown again in full, never written into project files. `.env.example` only documents which keys exist.

## How the router works

```
USER REQUEST → images prepared → PROMPT ENGINE → CAPABILITY CHECK
→ RANK engines (Fast = speed first, High/Max = quality first,
  paid engines first on Maximum if you configured them,
  multilingual engines first for non-English prompts,
  your Advanced → Model choice first)
→ try engine A → unavailable/busy/quota → "Trying another available engine…" → engine B → …
→ download → local post-processing → saved to history
```
If everything fails you get one clear message and a **RETRY** button — never raw HTTP errors.

Free Spaces are driven through the public Gradio API. The app reads each Space's live API description (`/info`) and fills parameters by name (prompt, image, seed, size, duration, mode…), so Spaces that change keep working without an app update, and you can add new Spaces yourself.

## Prompt engine

Your sentence is kept exactly as written. The engine analyses it (subject, action, environment, camera, lighting, time, weather, motion, style, realism, transition) and only **adds** short guidance for what you didn't mention — e.g. "steady cinematic camera", "smooth natural motion", and for first-frame mode "keep identity, clothing, environment and composition". You can see the exact prompt that was sent on the result screen.

## Local generation

Real video diffusion models need many GB of GPU memory; they don't run usefully on a phone, so the on-device engine reports **"Local generation unavailable for this model on this device."** The `LocalProvider` slot is ready: implement it with an on-device model and the router will use it automatically, including offline.

Everything else is local: image import, EXIF rotation, cropping/resizing (originals are never modified), thumbnails, metadata, frame extraction, saving to the Gallery, sharing, history, cache.

## Works offline

Open the app, browse and play past projects, save/share them, prepare images and prompts, settings. **Needs internet:** generating videos.

## Privacy

No ads, analytics, tracking or account. Projects live in app-private storage. Your prompt and images go only to the engine that makes the video, at the moment you press Generate (the home screen tells you this when images are attached).

## Add another provider (for developers)

1. Implement `ai/providers/VideoGenerationProvider` (capabilities, `canGenerate`, `getStatus`, `generate`, `cancelGeneration`).
2. Add it to `AppContainer.providers()` in `AmiriApp.kt`.
3. If it needs a key, add a name in `SecretStore` and a `SecretField` in `SettingsScreen`.

For another free Hugging Face Space you don't need code at all: Settings → "Add another free Space".

## Project layout

```
app/src/main/java/com/amiri/videoengine/
  AmiriApp.kt              app + dependency container
  ai/model/                requests, capabilities, states
  ai/providers/            provider interface
    gradio/                free Hugging Face Spaces (Gradio API client + catalog)
    cloud/                 Google Veo, xAI Grok (your own keys)
    local/                 on-device slot
  ai/router/               ModelRouter (ranking + fallback)
  ai/prompt/               PromptEngine
  ai/jobs/                 GenerationJobManager (queue/cancel/retry/delete)
  image/                   ImagePreprocessor
  video/                   VideoPostProcessor
  storage/                 projects, provider settings, cache
  security/                SecretStore (Android Keystore)
  network/                 HTTP + ConnectionManager
  ui/                      home, generation, result, projects, settings
```
