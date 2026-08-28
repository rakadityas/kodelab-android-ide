# Kodelab

A tab-based, themeable, multi-language code IDE that runs **natively on Android**
devices (phone / tablet / DeX / ChromeOS), with a real Linux terminal, per-folder
workspace presets, and an extension model built only on permissively licensed,
vendor-neutral components.

Kodelab is an original application. It is **not** a fork of VS Code, Cursor, Code–OSS,
IntelliJ or Android Studio, and it does not use the Microsoft Visual Studio Marketplace.
See [`docs/architecture.md`](docs/architecture.md) and [`docs/IP-SAFETY.md`](docs/IP-SAFETY.md).

## Status — M1 working product (verified on a Pixel Fold API 35 emulator)

| Area | State |
| --- | --- |
| Compose shell: activity rail, side panel, tab bar, status bar, terminal panel | ✅ working |
| Open folder (SAF tree picker), lazy file tree, open files into tabs | ✅ working |
| Monaco (MIT) in WebView: per-tab models, syntax highlighting, edit + save to disk | ✅ working |
| Soft-keyboard editing inside Monaco (WebView focus fix + worker proxy) | ✅ working |
| Theme cycle/picker — native chrome + Monaco + terminal follow one token set | ✅ working |
| Per-folder presets: `.kodelab/workspace.json` written/read on theme change/open | ✅ working |
| Shared terminal on a **real PTY** (JNI `forkpty` shim): shell echo, prompt, ^C/SIGINT | ✅ working; falls back to a pipe if the lib is missing |
| **Linux sandbox**: "Install Linux" downloads proot + Alpine at runtime, boots fake-root Alpine, `apk add git` works (verified: git 2.45.4) | ✅ working |
| Terminal quick keys (esc / Tab / ^C) + editor accessory bar above the keyboard | ✅ working |
| Explorer file ops: new file/folder, rename, delete (long-press menu) | ✅ working |
| Command palette: fuzzy commands + file quick-open | ✅ working |
| Multi-window: “New window” → separate task, same terminal service | ✅ working |
| ANSI/color terminal rendering (xterm.js), LSP, extensions | ⛔ next — see `requirement.txt` action items |

## Build

Requirements: Android SDK 35, NDK 26 (for the PTY shim), JDK 17 (the Android
Studio bundled JBR works). `targetSdk` is intentionally **28** so the
runtime-downloaded proot/Alpine binaries are allowed to `exec` from app storage
(the Termux approach); distribution is via F-Droid / direct APK, not Play.

```bash
# 1. (optional but recommended) vendor the editor core — Monaco + xterm.js, both MIT
scripts/build-web.sh

# 2. build the app
./gradlew :app:assembleDebug

# 3. install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Without step 1 the editor falls back to a plain textarea so the shell is still runnable.

## Layout

```
app/src/main/java/dev/kodelab/ide/
  MainActivity.kt        one instance == one window (REQ 7)
  ui/                    Compose shell + IdeViewModel + state models
  editor/                WebView host, JSON-RPC bridge, EditorController
  theme/                 Kodelab palettes + web token export
  terminal/              shared session service + ShellSession
  workspace/             WorkspacePresets + device-wide SettingsStore
app/src/main/assets/webapp/   first-party editor web app (index.html, app.js, app.css)
                              vendor/ is git-ignored, filled by scripts/build-web.sh
docs/                    architecture, IP-safety checklist, roadmap
```

## License

Kodelab's own code: [Apache-2.0](LICENSE). Third-party components and their licenses:
[`NOTICE`](NOTICE).
