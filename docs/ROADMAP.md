# Roadmap

## M0 — spike (current)
- [x] Compose shell: activity rail, side panel, tab bar, status bar, terminal panel
- [x] Editor-in-WebView loader + JSON-RPC bridge + theme token sync
- [x] Theme system (Kodelab Light / Dark / system)
- [x] `WorkspacePresets` model + resolution order + `SettingsStore`
- [x] Shared `TerminalSessionService` (foreground) + `ShellSession` placeholder
- [x] Touch-editing spike → **CodeMirror 6 wins**: a real scroller gives platform
      momentum, and contenteditable gives native selection handles. Monaco is gone.
- [ ] proot + Alpine bootstrap + JNI PTY shim + xterm.js: real shell

## M1 — MVP
- [ ] SAF folder open, file tree, create/rename/delete, inotify watch
- [ ] Tabs: drag reorder, split view, preview tabs, pinned tabs
- [ ] Command palette + fuzzy file open
- [ ] TextMate highlighting for ~25 languages
- [ ] Reading controls (font, line height, letter spacing, ligatures) persisted per workspace
- [ ] Terminal: input accessory bar, package-install flow, one-tap recipes (git, node, Claude Code)
- [ ] `.kodelab/workspace.json` read/write + live re-apply on folder switch

## M2 — v1
- [ ] LSP integration + server supervisor + install recipes
- [ ] Theme import (VS Code JSON shape), theme picker, per-workspace themes
- [ ] Search & replace across files; diff view; Git UI over the CLI
- [ ] Settings UI; "Open source notices" screen; SBOM in CI

## M3 — v1.x
- [ ] Multi-window (multi-instance activities) + per-window workspace + shared services
- [ ] Tree-sitter (selection-by-scope, folding)
- [ ] Kodelab plugin API (Phase B): sandboxed workers + signing + permissions
- [ ] Debug Adapter Protocol exploration

## M4 — later
- [ ] Open VSX compatibility-shim feasibility study; ship only if maintainable
- [ ] Remote / SSH workspaces; settings + preset sync

## Top risks
- ~~Monaco touch UX~~ — settled: the editor is CodeMirror 6.
- Background process survival — OEM battery managers; test Samsung / Xiaomi / Pixel.
- proot performance — heavy toolchains slow under ptrace; prefer Alpine.
- WebView fragmentation — gate on a minimum Chromium WebView version.
- Play Store policy — keep the sandbox clearly user-initiated; F-Droid as fallback channel.
- Grammar license drift — automated SPDX checks in CI.
