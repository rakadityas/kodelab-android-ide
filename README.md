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
| ANSI colour terminal (SGR/256-colour, `\r` in-place progress bars) via a small VT emulator | ✅ working |
| Search across files: recursive SAF walk, results grouped by file, tap a hit to jump to the line | ✅ working |
| Git panel over the sandbox CLI: branch/ahead-behind, stage/unstage, commit, open a file's diff | ✅ working (needs `apk add git`) |
| Theme import: load a standard color-theme JSON → Kodelab palette (native + Monaco + terminal) | ✅ working |
| Declarative extensions (data-only): themes/snippets/grammars/LSP recipes with an SPDX license audit | ✅ working — see [`examples/extensions/`](examples/extensions/) |
| LSP language servers (client + server supervisor), Open VSX catalogue | ⛔ next — see `requirement.txt` action items |

## Build

Requirements: Android SDK 35, NDK 26 (for the PTY shim), JDK 17 (the Android
Studio bundled JBR works). `targetSdk` is intentionally **28** so the
runtime-downloaded proot/Alpine binaries are allowed to `exec` from app storage
(the Termux approach); distribution is via F-Droid / direct APK, not Play.

```bash
# 1. (recommended) vendor the editor core — Monaco + xterm.js, both MIT.
#    Without this the editor shows a "run build-web.sh" placeholder.
scripts/build-web.sh

# 2. build the debug APK
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk

# 3. run the unit tests (optional)
./gradlew :app:testDebugUnitTest
```

If Gradle can't find the SDK, create `local.properties` with
`sdk.dir=/absolute/path/to/Android/sdk` (macOS default:
`~/Library/Android/sdk`). Point `JAVA_HOME` at a JDK 17 — the one bundled with
Android Studio works: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

## Install on a local Android device

Kodelab is an ordinary debuggable APK — install it over USB with `adb`, or copy
the file to the device and sideload it. It targets **arm64** devices on Android
10+ (the Linux sandbox binaries are aarch64).

### Option A — over USB with adb (simplest)

1. **On the device:** enable developer options (Settings → About phone → tap
   *Build number* 7 times), then turn on **USB debugging** (Settings → System →
   Developer options → USB debugging).
2. **Plug in** the device and accept the "Allow USB debugging?" prompt on the
   phone. Confirm the host sees it:
   ```bash
   adb devices        # your device's serial should be listed as "device"
   ```
3. **Install:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   `-r` reinstalls over an existing copy, keeping its data. For a clean install
   use `adb uninstall dev.kodelab.ide.debug` first.
4. Launch **Kodelab** from the app drawer.

If you have more than one device/emulator attached, target one with
`adb -s <serial> install …`.

### Option B — sideload the APK file (no computer needed after copying)

1. Copy `app-debug.apk` to the device (USB transfer, Drive, email to yourself,
   `adb push`, etc.).
2. On the device, open the file with the Files app and confirm the install. The
   first time, Android asks you to allow **"Install unknown apps"** for whichever
   app you opened it from (Files / your browser) — enable it and continue.

> F-Droid / direct APK is the intended distribution channel. Kodelab is **not**
> a Play Store app: it downloads and executes a Linux userland (proot + Alpine),
> which is what makes the terminal a real dev environment, and that sits outside
> Play policy. Installing a debug build is expected.

### First run — set up the Linux terminal

The editor, file tree, tabs, themes and terminal work immediately. To get a
package manager (`git`, `node`, Claude Code, …):

1. Open the **terminal** (the `>_` icon on the left rail).
2. Tap **Install Linux** in the terminal header. Kodelab downloads proot and a
   ~4 MB Alpine root filesystem (checksum-verified) and boots a fake-root Alpine
   shell — the prompt changes from `:/ $` to `localhost:~#`.
3. Install what you need, e.g.:
   ```sh
   apk add git nodejs npm
   npm i -g @anthropic-ai/claude-code
   ```

The sandbox and its terminal sessions are **shared across every folder and
window** and survive folder switches; only a new terminal's working directory
follows the active workspace.

## Using Kodelab

- **Open a folder** — folder icon in the Explorer header, or *Open folder…* from
  the command palette (the 🔍 in the status bar). Access is scoped to the folder
  you pick (Android Storage Access Framework).
- **Edit** — tap a file to open it in a tab; syntax highlighting for ~25
  languages. The bar above the keyboard adds Tab, arrows, undo/redo and common
  code symbols. Save with the 💾 rail icon or *Save file* in the palette.
- **Files** — long-press a tree row for New file / New folder / Rename / Delete.
- **Themes** — the palette rail icon cycles Kodelab Dark → Light → follow-system
  (and any imported themes); the choice is saved per-folder in
  `.kodelab/workspace.json`. Run *Import theme…* from the command palette to load
  a standard color-theme `.json`; it's saved under `.kodelab/themes/` and applies
  to the native chrome, Monaco and the terminal.
- **Windows** — the "new window" rail icon opens a second Kodelab window (great
  on tablets, DeX and ChromeOS) sharing the same terminal.

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
