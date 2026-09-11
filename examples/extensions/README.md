# Kodelab declarative extensions (Phase A)

Kodelab extensions are **data only** — a JSON manifest that *contributes* things.
No extension code runs, so there's no sandbox-escape or supply-chain surface: an
extension can add a theme, snippets, a language/grammar registration, or a
language-server "recipe", and nothing else.

There is **no Microsoft Marketplace** integration. You install an extension by
copying its folder into a workspace:

```
<your project>/.kodelab/extensions/<extension-id>/kodelab-extension.json
```

Kodelab discovers it the next time you open that folder, shown in the
**Extensions** side panel.

## License audit (why some show as "flagged")

Every extension — and every file it contributes — is checked against an SPDX
allowlist. Only **permissively licensed** add-ons (MIT, Apache-2.0, BSD, ISC,
OFL, …) activate automatically. Anything copyleft (GPL/LGPL/AGPL/MPL), unknown,
or with no declared `license` is **flagged** in the panel with the reason and
left inactive — it is never silently loaded. This keeps the app IP-safe.

## Manifest

See [`kodelab.sample-pack/kodelab-extension.json`](kodelab.sample-pack/kodelab-extension.json).

```jsonc
{
  "id": "publisher.name",        // required, unique
  "name": "Human Name",
  "version": "1.0.0",
  "publisher": "you",
  "license": "MIT",              // SPDX expression; "MIT OR Apache-2.0" ok
  "description": "…",
  "contributes": {
    "themes":   [ { "label": "…", "file": "theme.json", "license": "MIT" } ],
    "snippets": [ { "name": "…", "prefix": "main", "languages": ["kotlin"],
                    "body": ["fun main() {", "    $1", "}$0"] } ],
    "grammars": [ { "languageId": "kotlin", "extensions": [".kt"], "license": "MIT" } ],
    "languageServers": [ { "languageId": "python",
                           "install": ["pip install pyright"],
                           "command": ["pyright-langserver", "--stdio"],
                           "license": "MIT" } ]
  }
}
```

- **themes** — a standard color-theme JSON file; resolved and added to the theme
  picker. Works today.
- **snippets** — appear in the command palette ("Snippet: …") and insert with
  Monaco tab stops (`$1`, `$0`). Works today.
- **grammars** — language/extension registration. Parsed and listed now; full
  TextMate tokenization arrives with the grammar engine.
- **languageServers** — how to install (`install`, run once in the terminal) and
  launch (`command`) a server in the Linux sandbox. The LSP client starts it via
  *Start language server* in the command palette; diagnostics render as Monaco
  markers. Completion/hover and live-edit sync are in progress.
