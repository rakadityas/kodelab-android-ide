#!/usr/bin/env bash
# Vendors the editor web dependencies LOCALLY into the APK assets.
# Everything pulled here is MIT-licensed and is served from the app's own
# origin at runtime (https://appassets.androidplatform.net) — never a CDN.
#
# CodeMirror ships as ES modules with no prebuilt drop-in file, so unlike a
# copy-the-dist vendoring step this one bundles: web/src/editor-core.mjs picks
# the surface the editor needs and esbuild flattens it, its languages and its
# grammars into one classic script the WebView can load with a <script> tag.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB="$ROOT/web"
ASSETS="$ROOT/app/src/main/assets/webapp"
VENDOR="$ASSETS/vendor"

XTERM_VERSION="${XTERM_VERSION:-5.5.0}"      # MIT — github.com/xtermjs/xterm.js

command -v npm >/dev/null || { echo "npm required"; exit 1; }

mkdir -p "$VENDOR"
cd "$WEB"

# CodeMirror and esbuild are pinned in web/package.json, so this is reproducible
# and `npm audit` has something to look at.
npm install

npm install --no-save \
  "@xterm/xterm@${XTERM_VERSION}" \
  "@xterm/addon-fit@latest"

rm -rf "$VENDOR/codemirror" "$VENDOR/xterm"
mkdir -p "$VENDOR/codemirror" "$VENDOR/xterm"

# --target=chrome70: Android 9 (targetSdk 28) ships an old WebView, and a phone
# that never took a Play update for it is exactly the phone Kodelab is for.
npx esbuild src/editor-core.mjs \
  --bundle \
  --format=iife \
  --minify \
  --target=chrome70 \
  --legal-comments=none \
  --outfile="$VENDOR/codemirror/codemirror.js"

# One LICENSE covering the @codemirror/* and @lezer/* packages — they are all
# MIT under the same copyright line.
cp node_modules/@codemirror/state/LICENSE "$VENDOR/codemirror/LICENSE"

cp node_modules/@xterm/xterm/lib/* "$VENDOR/xterm/"
cp node_modules/@xterm/xterm/css/xterm.css "$VENDOR/xterm/"
cp node_modules/@xterm/xterm/LICENSE "$VENDOR/xterm/LICENSE"

cm_version="$(node -p "require('./node_modules/@codemirror/view/package.json').version")"
echo "Vendored:"
echo "  CodeMirror  view $cm_version  (MIT)  -> $VENDOR/codemirror/codemirror.js"
echo "  xterm       $XTERM_VERSION    (MIT)  -> $VENDOR/xterm"
echo "Now: ./gradlew :app:assembleDebug"
