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
| Theme picker — native chrome + Monaco + terminal follow one token set | ✅ working |
| Named code schemes: Solarized Dark/Light, Nord, Dracula, Gruvbox Dark, One Dark, Paper — token colours, not just a background | ✅ working |
| Terminal colour schemes: Midnight, Ember, Phosphor, Paper, or follow the theme | ✅ working |
| Settings page: theme, font sizes, word wrap, touch-target scale, selection delay — all saved per folder | ✅ working |
| Per-folder presets: `.kodelab/workspace.json` written/read on theme change/open | ✅ working |
| Shared terminal on a **real PTY** (JNI `forkpty` shim): shell echo, prompt, ^C/SIGINT | ✅ working; falls back to a pipe if the lib is missing |
| **Linux sandbox**: "Install Linux" downloads proot + Alpine at runtime, boots fake-root Alpine, `apk add git` works (verified: git 2.45.4) | ✅ working |
| Terminal quick keys (esc / Tab / ^C) + editor accessory bar above the keyboard | ✅ working |
| Explorer file ops: new file/folder, rename, delete (long-press menu) | ✅ working |
| Command palette: fuzzy commands + file quick-open | ✅ working |
| Multi-window: “New window” → separate task, same terminal service | ✅ working |
| Reading mode (read-only, keyboard stays down) ⇄ edit mode, one rail tap | ✅ working |
| Markdown reader: render the open `.md` as a document, with in-workspace links | ✅ working |
| Full screen: hide rail, tabs and status bar; one floating button to come back | ✅ working |
| Selection actions + floating nav: find references, go to definition, copy/cut/paste, back/forward, find in file | ✅ working |
| Open the Alpine home as a workspace — clone in the terminal, edit it here | ✅ working |
| ANSI colour terminal (SGR/256-colour, `\r` in-place progress bars) via a small VT emulator | ✅ working |
| Search across files: recursive SAF walk, results grouped by file, tap a hit to jump to the line | ✅ working |
| Git panel over the sandbox CLI: branch/ahead-behind, stage/unstage, commit, open a file's diff | ✅ working (needs `apk add git`) |
| Git for folders the sandbox can't reach: `.git` read directly through SAF for branch + changed files (read-only) | ✅ working |
| Theme import: load a standard color-theme JSON → Kodelab palette (native + Monaco + terminal) | ✅ working |
| Declarative extensions (data-only): themes/snippets/grammars/LSP recipes with an SPDX license audit | ✅ working — see [`examples/extensions/`](examples/extensions/) |
| LSP client + server supervisor: framing/JSON-RPC, sandbox transport, diagnostics → Monaco markers | 🟡 wired (protocol unit-tested; a live server run + completion/hover need on-device validation) |
| Open VSX catalogue, DAP, remote/SSH | ⛔ later — see `requirement.txt` action items |

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

To build the shipped artifact instead, use `./gradlew :app:assembleRelease` —
minified, and signed if `keystore.properties` points at a keystore (the
project's own key is not in this repo, so your build gets an unsigned APK unless
you supply one).

If Gradle can't find the SDK, create `local.properties` with
`sdk.dir=/absolute/path/to/Android/sdk` (macOS default:
`~/Library/Android/sdk`). Point `JAVA_HOME` at a JDK 17 — the one bundled with
Android Studio works: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

## Install on an Android device

It targets **arm64** devices on Android 10+ (the Linux sandbox binaries are
aarch64). You do not need to build anything — grab the signed APK:

### Option A — download the APK from GitHub (no computer needed)

1. Open **[the latest release](https://github.com/rakadityas/kodelab-android-ide/releases/latest)**
   on the phone and download `app-release.apk` under **Assets**.
2. Tap the downloaded file. The first time, Android asks you to allow
   **"Install unknown apps"** for whichever app you opened it from (Chrome /
   Files) — enable it and continue.
3. Launch **Kodelab** from the app drawer.

The release APK is signed with the project's own key, not Google's. Android
will say the app is from an unknown developer; that is expected for a direct
APK. Because the signing key is per-project, you cannot upgrade a release build
over a debug build (or the reverse) — uninstall the other one first.

### Option B — over USB with adb (if you built it yourself)

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

### Option C — sideload a file you already have

1. Copy the APK to the device (USB transfer, Drive, email to yourself,
   `adb push`, etc.).
2. On the device, open the file with the Files app and confirm the install,
   allowing **"Install unknown apps"** as above.

> F-Droid / direct APK is the intended distribution channel. Kodelab is **not**
> a Play Store app: it downloads and executes a Linux userland (proot + Alpine),
> which is what makes the terminal a real dev environment, and that sits outside
> Play policy.

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

The left rail is the whole navigation: Explorer, Search, Git and Extensions at
the top; reader, reading/edit mode, full screen, save, terminal, new window and
Settings below. Tapping the active view again collapses the side panel, which is
how you get the screen back on a phone.

- **Open a folder** — folder icon in the Explorer header, or *Open folder…* from
  the command palette (the 🔍 in the status bar). Access is scoped to the folder
  you pick (Android Storage Access Framework). The same menu has **Open from
  terminal (Alpine home)**: clone a repo in the sandbox and edit it here — that
  path is also the one git, the terminal and language servers can reach.
- **Read vs. edit** — Kodelab opens in **reading mode**: the editor is read-only
  and nothing grabs focus, so the keyboard stays down while you scroll. The
  eye/pencil rail icon switches to edit mode. For a `.md` file a book icon
  appears — it renders the document instead of its source.
- **Edit** — tap a file to open it in a tab; syntax highlighting for ~25
  languages. The bar above the keyboard adds Tab, arrows, undo/redo and common
  code symbols. Save with the 💾 rail icon or *Save file* in the palette.
- **Move around** — the floating cluster in the editor's corner is back, forward
  and find-in-file: the three moves a phone has no key for. Highlight a symbol
  and an **Actions** button appears with find references, go to definition, copy
  and cut; long-press with nothing selected for Paste.
- **Full screen** — the expand rail icon hides the rail, tabs and status bar so
  the file gets the whole screen; a floating button brings them back.
- **Files** — long-press a tree row for New file / New folder / Rename / Delete.
- **Settings** (gear, bottom of the rail) — theme, editor and terminal font
  sizes, word wrap, terminal colours, how fast the selection button appears, and
  touch-target size (Compact / Comfortable / Large). Everything here is saved
  per-folder in `.kodelab/workspace.json`, so a team can commit it.
- **Themes** — the picker lists Kodelab Dark, Kodelab Light, follow-system and
  the named code schemes (Solarized Dark/Light, Nord, Dracula, Gruvbox Dark, One
  Dark, Paper); each row previews the chrome, page and token colours it will
  apply. *Import theme…* loads a standard color-theme `.json` into
  `.kodelab/themes/` and applies it to the native chrome, Monaco and the
  terminal.
- **Windows** — the "new window" rail icon opens a second Kodelab window (great
  on tablets, DeX and ChromeOS) sharing the same terminal.

## Layout

```
app/src/main/java/dev/kodelab/ide/
  MainActivity.kt        one instance == one window (REQ 7)
  ui/                    Compose shell + IdeViewModel + state models
  editor/                WebView host, JSON-RPC bridge, EditorController
  theme/                 Kodelab palettes, named CodeSchemes, web token export
  terminal/              shared session service, ShellSession, PTY + VT emulator,
                         proot/Alpine sandbox installer
  git/                   sandbox git CLI + read-only .git reader over SAF
  lsp/                   LSP client, framing, server supervisor
  ext/                   declarative extensions + SPDX license audit
  workspace/             WorkspacePresets + device-wide SettingsStore
app/src/main/assets/webapp/   first-party editor web app (index.html, app.js, app.css)
                              vendor/ is git-ignored, filled by scripts/build-web.sh
docs/                    architecture, IP-safety checklist, roadmap
```

## Author

Kodelab is an original product by **Raka Aditya Soenarno**
([@rakadityas](https://github.com/rakadityas)) — its concept, requirements and
design are his.

## License

Kodelab's own code: [Apache-2.0](LICENSE). Third-party components and their licenses:
[`NOTICE`](NOTICE).

You are welcome to study this project, build on it, and take inspiration from it.
Apache-2.0 asks one thing in return: keep the attribution. Section 4 requires the
[`NOTICE`](NOTICE) file to travel with any copy or derivative work, so please
credit Raka Aditya Soenarno as the original author of Kodelab.
