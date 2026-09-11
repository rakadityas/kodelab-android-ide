#!/usr/bin/env bash
# Vendors the editor web dependencies LOCALLY into the APK assets.
# Everything pulled here is MIT-licensed and is served from the app's own
# origin at runtime (https://appassets.androidplatform.net) — never a CDN.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB="$ROOT/web"
ASSETS="$ROOT/app/src/main/assets/webapp"
VENDOR="$ASSETS/vendor"

MONACO_VERSION="${MONACO_VERSION:-0.52.2}"   # MIT — github.com/microsoft/monaco-editor
XTERM_VERSION="${XTERM_VERSION:-5.5.0}"      # MIT — github.com/xtermjs/xterm.js

command -v npm >/dev/null || { echo "npm required"; exit 1; }

mkdir -p "$WEB" "$VENDOR"
cd "$WEB"
[ -f package.json ] || npm init -y >/dev/null

npm install --no-save \
  "monaco-editor@${MONACO_VERSION}" \
  "@xterm/xterm@${XTERM_VERSION}" \
  "@xterm/addon-fit@latest"

rm -rf "$VENDOR/monaco" "$VENDOR/xterm"
mkdir -p "$VENDOR/monaco" "$VENDOR/xterm"

cp -R node_modules/monaco-editor/min/vs "$VENDOR/monaco/vs"
cp node_modules/monaco-editor/LICENSE "$VENDOR/monaco/LICENSE"

cp node_modules/@xterm/xterm/lib/* "$VENDOR/xterm/"
cp node_modules/@xterm/xterm/css/xterm.css "$VENDOR/xterm/"
cp node_modules/@xterm/xterm/LICENSE "$VENDOR/xterm/LICENSE"

echo "Vendored:"
echo "  Monaco  $MONACO_VERSION  (MIT)  -> $VENDOR/monaco"
echo "  xterm   $XTERM_VERSION   (MIT)  -> $VENDOR/xterm"
echo "Now: ./gradlew :app:assembleDebug"
