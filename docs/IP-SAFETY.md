# IP-safety checklist

Standing rules for everyone working on Kodelab. CI enforces the automatable ones.

- [ ] Original name, logo, icon set, and copy. No "Visual Studio", "VS Code",
      "Code–OSS", "Cursor", "IntelliJ", "Android Studio" or their marks anywhere in
      the UI, store listing, or metadata.
- [ ] Monaco, xterm.js, vscode-textmate, vscode-oniguruma consumed as published npm
      packages under MIT — unmodified, or forked openly with attribution. LICENSE
      files shipped in an in-app "Open source notices" screen.
- [ ] Every TextMate / Tree-sitter grammar audited before bundling. `docs/GRAMMARS.md`
      records source repo + SPDX license per grammar. Only MIT / BSD / Apache-2.0 /
      Unlicense / CC0 grammars ship in the app; others are user-downloaded with their
      license shown.
- [ ] GPL components (`proot`, BusyBox if ever used) shipped as **unmodified
      standalone binaries** executed as separate processes. Corresponding source +
      build scripts published; written offer in the About screen. Kodelab's own code
      never links them.
- [ ] Linux rootfs images downloaded from the distributions' own servers at runtime,
      never redistributed in the APK.
- [ ] PTY shim, session multiplexer, proot bootstrap, terminal glue written from
      POSIX / Android / VT specs and man pages — not adapted from Termux or other GPL
      projects. Each file notes its primary sources.
- [ ] No use of the Microsoft Marketplace, its APIs, its gallery data, or its bundled
      extensions — in any form, including proxies.
- [ ] Fonts are SIL OFL or Apache-2.0 and redistributable; OFL reserved-font-name
      rules respected.
- [ ] Theme *format* compatibility only (a JSON schema is not copyrightable as code);
      never copy the *contents* of proprietary bundled themes.
- [ ] No decompiling; no copying UI asset files, keybinding tables, or command IDs
      verbatim from other IDEs. Re-derive from behavior and public docs.
- [ ] `THIRD_PARTY_NOTICES` + SBOM generated in CI and shipped with every build.

## Why "separate process" keeps the GPL out of our code

`proot` is invoked via `exec`, communicates only over pipes/argv/exit codes, and is
shipped as its own file. That is mere aggregation under the GPL FAQ — it does not
make Kodelab a derivative work. We still honor GPLv2 for `proot` itself: unmodified
binary, published corresponding source, written offer in-app.
