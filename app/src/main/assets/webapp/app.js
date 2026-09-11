/*
 * Kodelab editor host — the web side of the JSON-RPC bridge.
 *
 * Native -> web:  window.__kodelab.receive({method, params})
 * web -> native:  KodelabHost.post(JSON.stringify({method, params}))
 *
 * Third-party code used here (vendored locally by scripts/build-web.sh, never
 * from a CDN): Monaco Editor (MIT). This file is first-party.
 *
 * One Monaco instance; one model per tab. Tab switching swaps models so undo
 * stacks, folding and scroll position stay per-file.
 */
(function () {
  "use strict";

  var editor = null;
  var monacoReady = false;
  var buffers = {}; // tabId -> { model, savedVersionId, dirty, viewState }
  var activeTabId = null;
  var readOnly = false;
  var selectionDelayMs = 350; // how long after a selection the chip appears
  var pendingSettings = null;
  var pendingThemeTokens = null;
  var queued = []; // messages that arrived before Monaco booted

  function toNative(method, params) {
    try {
      if (window.KodelabHost && window.KodelabHost.post) {
        window.KodelabHost.post(JSON.stringify({ method: method, params: params || {} }));
      }
    } catch (e) {}
  }

  function stripAlpha(hex) {
    // native sends #RRGGBBAA; CSS + Monaco want #RRGGBB here
    return (hex && hex.length === 9) ? hex.slice(0, 7) : hex;
  }

  function applyTheme(tokens) {
    if (!tokens) return;
    pendingThemeTokens = tokens;
    var r = document.documentElement.style;
    ["surface", "panel", "textPrimary", "textMuted", "accent", "border"].forEach(function (k) {
      if (tokens[k]) r.setProperty("--" + k, stripAlpha(tokens[k]));
    });
    if (monacoReady && window.monaco) {
      window.monaco.editor.defineTheme("kodelab", {
        base: tokens.base || "vs-dark",
        inherit: true,
        rules: [],
        colors: {
          "editor.background": stripAlpha(tokens.surface),
          "editor.foreground": stripAlpha(tokens.textPrimary),
          "editorLineNumber.foreground": stripAlpha(tokens.textMuted),
          "editorCursor.foreground": stripAlpha(tokens.accent),
          "editor.lineHighlightBorder": stripAlpha(tokens.border),
        },
      });
      window.monaco.editor.setTheme("kodelab");
    }
  }

  function applySettings(p) {
    pendingSettings = p;
    readOnly = p.readOnly === true;
    if (typeof p.selectionDelayMs === "number") selectionDelayMs = p.selectionDelayMs;
    if (!monacoReady || !editor) return;
    editor.updateOptions({
      fontSize: p.fontSize || 14,
      fontFamily: (p.fontFamily ? p.fontFamily + ", " : "") + "ui-monospace, Menlo, Consolas, monospace",
      lineHeight: Math.round((p.fontSize || 14) * (p.lineHeight || 1.6)),
      fontLigatures: p.ligatures !== false,
      wordWrap: p.wordWrap ? "on" : "off",
      readOnly: readOnly,
      domReadOnly: readOnly,
    });
    if (readOnly && editor.hasTextFocus && editor.hasTextFocus()) {
      var host = document.getElementById("monaco");
      if (host && document.activeElement) document.activeElement.blur();
    }
    if (activeTabId && buffers[activeTabId]) {
      buffers[activeTabId].model.updateOptions({
        tabSize: p.tabWidth || 4,
        insertSpaces: p.insertSpaces !== false,
      });
    }
  }

  function setDirty(tabId, dirty) {
    var b = buffers[tabId];
    if (!b || b.dirty === dirty) return;
    b.dirty = dirty;
    toNative("editor.dirtyChanged", { tabId: tabId, dirty: dirty });
  }

  function openBuffer(tabId, text, languageId) {
    if (!monacoReady) return; // replayed by native after editor.ready
    var b = buffers[tabId];
    if (b) {
      // reload from disk (e.g. WebView recreation replay)
      b.model.setValue(text);
      b.savedVersionId = b.model.getAlternativeVersionId();
      setDirty(tabId, false);
      return;
    }
    var model = window.monaco.editor.createModel(text, languageId || "plaintext");
    b = buffers[tabId] = {
      model: model,
      savedVersionId: model.getAlternativeVersionId(),
      dirty: false,
      viewState: null,
    };
    model.onDidChangeContent(function () {
      setDirty(tabId, model.getAlternativeVersionId() !== b.savedVersionId);
    });
    if (!activeTabId) showBuffer(tabId);
  }

  function showBuffer(tabId) {
    var b = buffers[tabId];
    if (!b || !editor) return;
    if (activeTabId && buffers[activeTabId]) {
      buffers[activeTabId].viewState = editor.saveViewState();
    }
    activeTabId = tabId;
    editor.setModel(b.model);
    if (b.viewState) editor.restoreViewState(b.viewState);
    if (pendingSettings) {
      b.model.updateOptions({
        tabSize: pendingSettings.tabWidth || 4,
        insertSpaces: pendingSettings.insertSpaces !== false,
      });
    }
    if (!readOnly) editor.focus();
  }

  /** The selection, or failing that the word under the cursor, sent to native. */
  function sendSymbol(ed, method) {
    var model = ed.getModel();
    if (!model) return;
    var sel = ed.getSelection();
    var text = sel && !sel.isEmpty() ? model.getValueInRange(sel) : "";
    if (!text) {
      var pos = ed.getPosition();
      var word = pos && model.getWordAtPosition(pos);
      text = word ? word.word : "";
    }
    text = (text || "").trim();
    if (!text) return;
    toNative(method, { text: text, tabId: activeTabId });
  }

  // ---- selection actions -------------------------------------------------
  //
  // Highlighting text shows one small button. Tapping it opens the menu;
  // anything else — a tap elsewhere, a new selection, Dismiss — closes both.
  // Nothing here can end up stuck open with no way back.

  var selChip = null, selMenu = null, selHideTimer = null;

  function hideSelectionUi() {
    if (selChip) selChip.hidden = true;
    if (selMenu) selMenu.hidden = true;
  }

  function placeAt(el, top, left) {
    var host = document.getElementById("monaco");
    var maxLeft = Math.max(4, (host ? host.clientWidth : 320) - el.offsetWidth - 8);
    var maxTop = Math.max(4, (host ? host.clientHeight : 480) - el.offsetHeight - 8);
    el.style.left = Math.min(Math.max(4, left), maxLeft) + "px";
    el.style.top = Math.min(Math.max(4, top), maxTop) + "px";
  }

  function wireSelectionActions() {
    selChip = document.getElementById("sel-chip");
    selMenu = document.getElementById("sel-menu");
    if (!selChip || !selMenu) return;

    selChip.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
      selMenu.hidden = false;
      selMenu.style.visibility = "hidden";
      // measure once laid out, then place under the chip
      requestAnimationFrame(function () {
        placeAt(selMenu, selChip.offsetTop + selChip.offsetHeight + 6, selChip.offsetLeft);
        selMenu.style.visibility = "visible";
      });
    });

    selMenu.addEventListener("click", function (e) {
      var act = e.target && e.target.getAttribute("data-act");
      if (!act) return;
      e.preventDefault();
      e.stopPropagation();
      if (act === "copy") {
        var sel = editor.getSelection();
        var model = editor.getModel();
        if (sel && model) {
          var t = model.getValueInRange(sel);
          if (navigator.clipboard) navigator.clipboard.writeText(t).catch(function () {});
        }
      } else if (act !== "dismiss") {
        sendSymbol(editor, "editor." + act);
      }
      hideSelectionUi();
    });

    editor.onDidChangeCursorSelection(function (e) {
      if (selHideTimer) { clearTimeout(selHideTimer); selHideTimer = null; }
      var sel = e.selection;
      selMenu.hidden = true;
      if (!sel || sel.isEmpty()) { selChip.hidden = true; return; }
      // let the drag finish before the chip appears, so it doesn't jump around
      selHideTimer = setTimeout(function () {
        var pos = { lineNumber: sel.endLineNumber, column: sel.endColumn };
        var pt = editor.getScrolledVisiblePosition(pos);
        if (!pt) return;
        selChip.hidden = false;
        selChip.style.visibility = "hidden";
        requestAnimationFrame(function () {
          placeAt(selChip, pt.top + pt.height + 6, pt.left);
          selChip.style.visibility = "visible";
        });
      }, selectionDelayMs);
    });

    editor.onDidScrollChange(hideSelectionUi);
  }

  function revealLine(tabId, line) {
    if (!buffers[tabId]) return;
    if (activeTabId !== tabId) showBuffer(tabId);
    if (!editor) return;
    var ln = Math.max(1, line | 0);
    editor.revealLineInCenter(ln);
    editor.setPosition({ lineNumber: ln, column: 1 });
    if (!readOnly) editor.focus();
  }

  function closeBuffer(tabId) {
    var b = buffers[tabId];
    if (!b) return;
    if (activeTabId === tabId) { activeTabId = null; if (editor) editor.setModel(null); }
    b.model.dispose();
    delete buffers[tabId];
  }

  var api = {
    receive: function (msg) {
      if (!msg || !msg.method) return;
      if (!monacoReady && msg.method.indexOf("buffer.") === 0) {
        queued.push(msg);
        return;
      }
      var p = msg.params || {};
      switch (msg.method) {
        case "theme.apply":     applyTheme(p.tokens); break;
        case "settings.apply":  applySettings(p); break;
        case "buffer.open":     openBuffer(p.tabId, p.text || "", p.languageId); break;
        case "buffer.show":     showBuffer(p.tabId); break;
        case "buffer.reveal":   revealLine(p.tabId, p.line || 1); break;
        case "lsp.diagnostics": {
          var lb = buffers[p.tabId];
          if (lb && window.monaco) {
            window.monaco.editor.setModelMarkers(lb.model, "kodelab-lsp", p.markers || []);
          }
          break;
        }
        case "buffer.close":    closeBuffer(p.tabId); break;
        case "buffer.requestSave": {
          var b = buffers[p.tabId];
          toNative("buffer.save", { tabId: p.tabId, text: b ? b.model.getValue() : "" });
          break;
        }
        case "input.exec":
          // accessory bar: run a Monaco command ("tab", "undo", "cursorLeft", ...)
          if (editor) { editor.focus(); editor.trigger("kodelab", p.command, p.args || null); }
          break;
        case "input.type":
          if (editor) { editor.focus(); editor.trigger("keyboard", "type", { text: p.text || "" }); }
          break;
        case "editor.requestSymbol":
          if (editor) sendSymbol(editor, p.reply || "editor.findReferences");
          break;
        case "input.snippet":
          if (editor) {
            editor.focus();
            var snip = p.snippet || "";
            var ctrl = editor.getContribution && editor.getContribution("snippetController2");
            if (ctrl && ctrl.insert) ctrl.insert(snip);
            else editor.trigger("keyboard", "type", { text: snip.replace(/\$\{?\d+:?/g, "").replace(/\}/g, "") });
          }
          break;
        case "buffer.markSaved": {
          var s = buffers[p.tabId];
          if (s) { s.savedVersionId = s.model.getAlternativeVersionId(); setDirty(p.tabId, false); }
          break;
        }
      }
    },
  };
  window.__kodelab = api;

  function bootMonaco() {
    var loader = document.createElement("script");
    loader.src = "./vendor/monaco/vs/loader.js";
    loader.onerror = function () {
      // no vendored Monaco — surface it, stay on the fallback message
      document.getElementById("fallback").hidden = false;
      toNative("editor.ready", { core: "fallback" });
    };
    loader.onload = function () {
      // Workers resolve against the origin root, not the page — hand them an
      // absolute baseUrl via the data:-URL proxy from the monaco-editor FAQ.
      var vsBase = new URL("./vendor/monaco/", window.location.href).href;
      window.MonacoEnvironment = {
        getWorkerUrl: function () {
          var boot = "self.MonacoEnvironment={baseUrl:'" + vsBase + "'};" +
            "importScripts('" + vsBase + "vs/base/worker/workerMain.js');";
          return "data:text/javascript;charset=utf-8," + encodeURIComponent(boot);
        },
      };
      window.require.config({ paths: { vs: "./vendor/monaco/vs" } });
      window.require(["vs/editor/editor.main"], function () {
        document.getElementById("fallback").hidden = true;
        var host = document.getElementById("monaco");
        host.hidden = false;
        editor = window.monaco.editor.create(host, {
          model: null,
          automaticLayout: true,
          minimap: { enabled: false },
          fontFamily: "ui-monospace, Menlo, Consolas, monospace",
          fontLigatures: true,
          scrollBeyondLastLine: false,
          padding: { top: 8 },
          // compact gutter: keep the numbers, drop the width they don't need
          lineNumbersMinChars: 2,
          lineDecorationsWidth: 2,
          glyphMargin: false,
          folding: false,
          // touch-friendlier defaults for M0; the native accessory bar is next
          mouseWheelZoom: false,
          quickSuggestions: true,
          renderWhitespace: "none",
          // Monaco's long-press menu could be opened but not dismissed on
          // touch; the selection chip below replaces it.
          contextmenu: false,
        });
        // "Find references" for the selected text (or the word under the
        // cursor). Native runs it as a workspace search, so it needs no
        // language server — long-press in the editor to reach it.
        wireSelectionActions();
        monacoReady = true;
        if (pendingThemeTokens) applyTheme(pendingThemeTokens);
        if (pendingSettings) applySettings(pendingSettings);
        var q = queued; queued = [];
        q.forEach(api.receive);
        toNative("editor.ready", { core: "monaco" });
      });
    };
    document.head.appendChild(loader);
  }

  bootMonaco();
})();
