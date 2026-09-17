# Kodelab architecture

Full illustrated version: the design brief artifact. This is the repo-tracked copy.

## The one constraint

The IDE runs **on the Android device**. No Node.js runtime ships with Android, so
there is no desktop extension host. The editing surface is a WebView; the services
around it are native Kotlin; the "install anything" terminal is a downloaded Linux
userspace run under `proot`, not the Android system shell.

## Four layers

```
A · Native shell        Kotlin + Jetpack Compose
    window/activity manager · workspace + preset store · SAF file provider ·
    settings (DataStore) · command palette · native input accessory bar
        │  JSON-RPC over WebView bridge (KodelabHost.post / window.__kodelab.receive)
B · Editing surface      WebView, offline asset bundle (app's own origin)
    CodeMirror 6 (MIT) · xterm.js (MIT) · tab/split UI · theme engine ·
    Lezer grammars (MIT) · @codemirror/legacy-modes (MIT)
        │  LSP over stdio · PTY byte stream · inotify file events
C · Process services     native + JNI
    PTY host (original shim over bionic forkpty) · session multiplexer ·
    LSP server supervisor · file watcher
        runs inside
D · Linux sandbox        userspace, no root
    proot (GPLv2, separate binary) · downloaded Alpine/Debian rootfs ·
    apk/apt · user-installed git, node, python, Claude Code, ...
```

## Editor (REQ 1–3)

- CodeMirror 6 in the WebView, bundled locally by `scripts/build-web.sh`, never
  a CDN. One `EditorView`; one `EditorState` per tab.
- Tabs / splits / editor groups are our own layer, driven through `EditorController`.
- **Touch:** this is why the editor is CodeMirror and not Monaco. Monaco scrolls
  by transforming its content inside a fixed box and synthesises its own fling,
  which never feels like the platform; and it keeps the text in a hidden
  textarea, so there is no native selection to grab. CodeMirror scrolls a real
  `overflow: auto` element and puts the code in a contenteditable, so momentum,
  overscroll, drag handles and the magnifier are the WebView's own. Kodelab adds
  the native input accessory bar (Tab/arrows/symbols), its own selection menu,
  and suppresses the system selection toolbar so there is only one menu.
- Syntax: Lezer grammars for the languages that have one, `@codemirror/legacy-modes`
  for the long tail — all bundled, all offline. See `web/src/editor-core.mjs`.
- Intelligence: LSP servers installed by the user into the sandbox, supervised by
  layer C, bridged into the editor over stdio. Nothing proprietary bundled.

## Theming (REQ 2)

One theme = UI tokens + TextMate scope rules + editor typography, stored as JSON in
our schema. Importer reads the widely used VS Code theme JSON *shape* (a data
format, not code); we ship our own Kodelab Light/Dark, not anyone's bundled themes.
Native palette is exported to the web layer as tokens (`EditorPalette.toWebTokens`)
so the editor and the terminal match the chrome. A `CodeScheme` adds the code
area's own colours as seven token *roles*; which syntax tags each role paints is
decided web-side, in `web/src/editor-core.mjs`.

## Terminal (REQ 5) — shared, not per-window

1. First launch downloads a small rootfs tarball from the distro's own mirror.
2. Unmodified `proot` binary enters it (userspace `chroot` + binds via `ptrace`).
3. Package manager works: `apk add git nodejs`, `npm i -g @anthropic-ai/claude-code`.
4. JNI PTY shim allocates a pty via bionic `forkpty`, runs the login shell under
   proot, streams bytes to xterm.js.

There is **one** sandbox and **one** set of sessions per device. Every window and
workspace attaches to the same session multiplexer, so long builds and logged-in
sessions survive folder switches. Only a new terminal's initial working directory
follows the active workspace. The session host runs in a foreground service
(`TerminalSessionService`) so Android doesn't freeze it.

M0 uses `ShellSession` (ProcessBuilder → `/system/bin/sh`) as a placeholder — no
PTY, no sandbox yet.

## Extensions (REQ 4) — staged

- **Phase A (v1):** declarative add-ons — grammars, themes, icon themes, snippets,
  LSP-server recipes. Pure data, per-file license audit, no third-party executable code.
- **Phase B (v1.x):** first-party Kodelab plugin API — sandboxed worker, registers
  commands / palette entries / status-bar items / decorations / simple panels.
  Signed, permission-scoped. Not a reimplementation of `vscode.d.ts`.
- **Phase C (later, only if maintainable):** Open VSX (Eclipse, vendor-neutral)
  catalogue behind a compatibility shim. Not in the first releases.
- **Never:** the Microsoft Marketplace or its APIs.

## Multi-window (REQ 7)

Multi-instance `MainActivity` (`documentLaunchMode`, `resizeableActivity`). Each
window binds one workspace and its presets; the terminal host, sandbox, LSP
supervisor and global settings are process-wide singletons. Great on tablets / DeX
/ ChromeOS; degrades to task-switching on phones. Ships v1.x.

## Presets (REQ 8)

`<folder>/.kodelab/workspace.json` — theme, typography, layout, language overrides,
LSP map, excludes. Committable and shareable. Resolution order (later wins):
built-in defaults → device-wide user settings → `workspace.json` → local
`state.json` (ephemeral, git-ignored). Switching folders re-applies the resolved
config. The terminal is the deliberate exception (see above).
