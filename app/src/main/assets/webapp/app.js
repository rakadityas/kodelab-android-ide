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

  // ---- context menu ------------------------------------------------------
  //
  // One menu, opened two ways: by highlighting text, and by a long press
  // anywhere in the editor (which is the only way to reach Paste when nothing
  // is selected). It used to take two taps — a chip that opened the menu —
  // which was a step with nothing in it.
  //
  // Clipboard work goes through native rather than navigator.clipboard: a
  // WebView grants reads grudgingly, and the phone's clipboard is the one the
  // user actually copied into.

  var selChip = null, selMenu = null, selShowTimer = null, longPressTimer = null;

  function hideSelectionUi() {
    // Guarded: this runs on every scroll event, and an unconditional DOM write
    // per frame is felt on a phone.
    if (selChip && !selChip.hidden) selChip.hidden = true;
    if (selMenu && !selMenu.hidden) selMenu.hidden = true;
  }

  function placeAt(el, top, left) {
    var host = document.getElementById("monaco");
    var maxLeft = Math.max(4, (host ? host.clientWidth : 320) - el.offsetWidth - 8);
    var maxTop = Math.max(4, (host ? host.clientHeight : 480) - el.offsetHeight - 8);
    el.style.left = Math.min(Math.max(4, left), maxLeft) + "px";
    el.style.top = Math.min(Math.max(4, top), maxTop) + "px";
  }

  function hasSelection() {
    var sel = editor && editor.getSelection();
    return !!(sel && !sel.isEmpty());
  }

  /** Show the menu at a point, with only the items that apply right now. */
  function showMenu(top, left) {
    if (!selMenu) return;
    var sel = hasSelection();
    Array.prototype.forEach.call(selMenu.querySelectorAll("button"), function (b) {
      var needs = (b.getAttribute("data-needs") || "").split(" ").filter(Boolean);
      var ok = needs.every(function (n) {
        return n === "selection" ? sel : n === "edit" ? !readOnly : true;
      });
      b.hidden = !ok;
    });
    selMenu.hidden = false;
    selMenu.style.visibility = "hidden";
    requestAnimationFrame(function () {
      placeAt(selMenu, top, left);
      selMenu.style.visibility = "visible";
    });
  }

  function selectionText() {
    var sel = editor.getSelection(), model = editor.getModel();
    return (sel && model) ? model.getValueInRange(sel) : "";
  }

  function runAction(act) {
    if (act === "copy") {
      toNative("editor.copy", { text: selectionText() });
    } else if (act === "cut") {
      var sel = editor.getSelection();
      toNative("editor.copy", { text: selectionText() });
      if (sel) editor.executeEdits("kodelab", [{ range: sel, text: "" }]);
    } else if (act === "paste") {
      // Native reads the phone's clipboard and sends the text back as
      // input.type, which lands at the cursor (replacing any selection).
      toNative("editor.requestPaste", {});
    } else if (act === "selectAll") {
      var m = editor.getModel();
      if (m) editor.setSelection(m.getFullModelRange());
      editor.focus();
    } else if (act !== "dismiss") {
      sendSymbol(editor, "editor." + act);
    }
  }

  function wireSelectionActions() {
    selChip = document.getElementById("sel-chip");
    selMenu = document.getElementById("sel-menu");
    if (!selChip || !selMenu) return;
    var host = document.getElementById("monaco");

    // Tapping the chip is what opens the menu — the selection stays visible
    // until you ask for something to do with it.
    selChip.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
      selChip.hidden = true;
      showMenu(selChip.offsetTop + selChip.offsetHeight + 6, selChip.offsetLeft);
    });

    selMenu.addEventListener("click", function (e) {
      var act = e.target && e.target.getAttribute("data-act");
      if (!act) return;
      e.preventDefault();
      e.stopPropagation();
      runAction(act);
      hideSelectionUi();
    });

    // Highlighting text shows the chip, once the drag has settled so it
    // doesn't jump around under the finger.
    editor.onDidChangeCursorSelection(function (e) {
      if (selShowTimer) { clearTimeout(selShowTimer); selShowTimer = null; }
      var sel = e.selection;
      selMenu.hidden = true;
      if (!sel || sel.isEmpty()) { selChip.hidden = true; return; }
      selShowTimer = setTimeout(function () {
        var pt = editor.getScrolledVisiblePosition({
          lineNumber: sel.endLineNumber, column: sel.endColumn,
        });
        if (!pt) return;
        selChip.hidden = false;
        selChip.style.visibility = "hidden";
        requestAnimationFrame(function () {
          placeAt(selChip, pt.top + pt.height + 6, pt.left);
          selChip.style.visibility = "visible";
        });
      }, selectionDelayMs);
    });

    // A long press is the way to the menu with nothing selected — Paste.
    if (host) {
      var startX = 0, startY = 0;
      host.addEventListener("touchstart", function (e) {
        if (e.touches.length !== 1) return;
        startX = e.touches[0].clientX;
        startY = e.touches[0].clientY;
        var x = startX, y = startY;
        clearTimeout(longPressTimer);
        longPressTimer = setTimeout(function () {
          var rect = host.getBoundingClientRect();
          selChip.hidden = true;
          showMenu(y - rect.top + 8, x - rect.left);
        }, 500);
      }, { passive: true });
      var cancel = function (e) {
        if (e && e.touches && e.touches.length === 1) {
          var dx = e.touches[0].clientX - startX, dy = e.touches[0].clientY - startY;
          if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;  // still a press
        }
        clearTimeout(longPressTimer);
      };
      host.addEventListener("touchmove", cancel, { passive: true });
      host.addEventListener("touchend", cancel, { passive: true });
      host.addEventListener("touchcancel", cancel, { passive: true });
    }

    editor.onDidScrollChange(hideSelectionUi);
  }

  /**
   * Take the runaway out of Monaco's touch fling.
   *
   * Monaco turns the last four touch points into a release velocity —
   * `distance / (lastTimestamp - firstTimestamp)` — and those four points have
   * no age limit. Two ways that goes wrong on a phone:
   *
   *  - Date.now() resolves to a millisecond, so a small quick movement can put
   *    all four points inside one. The divisor is 0, the velocity is Infinity,
   *    and the inertia loop decays by a fixed friction per frame, so Infinity
   *    never comes down — the view flies off.
   *  - No touchmove fires while a finger is held still, so the buffer freezes
   *    holding whatever came before the pause. Stop after a fast swipe, creep a
   *    few pixels, lift: the velocity is still mostly that old fast swipe, and
   *    the view flings in the direction you had already stopped moving.
   *
   * So the velocity is measured here instead, from touch samples inside a
   * short window, and a gesture whose finger had already stopped doesn't fling
   * at all. A real flick still flings, through Monaco's own inertia.
   */
  var VELOCITY_WINDOW_MS = 100;  // only movement this recent counts
  var STOPPED_MS = 60;           // no movement for this long before lifting = stopped
  var MIN_SAMPLE_MS = 8;         // shorter than this can't be measured meaningfully
  var MIN_FLING_SPEED = 0.3;     // px/ms — below this it was a drag, not a flick
  var MAX_FLING_SPEED = 10;      // px/ms — beyond human; a measurement artefact

  var touchSamples = [];

  function now() {
    return (window.performance && performance.now) ? performance.now() : Date.now();
  }

  function recordTouch(e) {
    var t = (e.touches && e.touches.length) ? e.touches[0]
          : (e.changedTouches && e.changedTouches[0]);
    if (!t) return;
    var at = now();
    touchSamples.push({ x: t.pageX, y: t.pageY, t: at });
    while (touchSamples.length > 1 && at - touchSamples[0].t > VELOCITY_WINDOW_MS) {
      touchSamples.shift();
    }
  }

  /** Velocity of the last window of movement, or null if there wasn't any. */
  function releaseVelocity() {
    if (touchSamples.length < 2) return null;
    var last = touchSamples[touchSamples.length - 1];
    var first = touchSamples[0];
    // Deliberately not recording touchend: this is the check that a finger
    // which had come to rest before lifting sends the view nowhere.
    if (now() - last.t > STOPPED_MS) return null;
    var dt = last.t - first.t;
    if (dt < MIN_SAMPLE_MS) return null;
    return { x: (last.x - first.x) / dt, y: (last.y - first.y) / dt };
  }

  function tameTouchInertia() {
    document.addEventListener("touchstart", function (e) {
      touchSamples.length = 0;      // a new gesture inherits nothing
      recordTouch(e);
    }, true);
    document.addEventListener("touchmove", recordTouch, true);

    if (!window.require) return;
    window.require(["vs/base/browser/touch"], function (touch) {
      var G = touch && touch.Gesture;
      if (!G || !G.prototype || typeof G.prototype.inertia !== "function") return;
      var original = G.prototype.inertia;
      // (window, targets, timestamp, speedX, dirX, lastX, speedY, dirY, lastY)
      G.prototype.inertia = function (win, targets, ts, speedX, dirX, lastX, speedY, dirY, lastY) {
        var v = releaseVelocity();
        if (!v || !isFinite(v.x) || !isFinite(v.y)) return;
        var fastest = Math.max(Math.abs(v.x), Math.abs(v.y));
        if (fastest < MIN_FLING_SPEED || fastest > MAX_FLING_SPEED) return;
        return original.call(
          this, win, targets, ts,
          Math.abs(v.x), v.x > 0 ? 1 : -1, lastX,
          Math.abs(v.y), v.y > 0 ? 1 : -1, lastY,
        );
      };
    }, function () { /* module not in this build — leave the fling alone */ });
  }

  /**
   * Keep the browser from scrolling the page under Monaco.
   *
   * Monaco parks a hidden textarea at the cursor and focuses it; Chrome then
   * scrolls the nearest scrollable ancestor to bring that textarea into view.
   * `overflow: hidden` does not make an element unscrollable — only unscrollable
   * *by the user* — so those ancestors would jump by a few pixels mid-drag,
   * which read as the editor scrolling itself back up. Pin them at zero and let
   * Monaco's own scrollable element do all the scrolling.
   */
  function pinContainerScroll() {
    var pinned = [document.documentElement, document.body,
                  document.getElementById("root"), document.getElementById("monaco")];
    pinned.forEach(function (el) {
      if (!el) return;
      el.addEventListener("scroll", function () {
        if (el.scrollTop !== 0) el.scrollTop = 0;
        if (el.scrollLeft !== 0) el.scrollLeft = 0;
      }, { passive: true });
    });
  }

  /** Tell native where the cursor is, so it can record navigation history. */
  function reportPosition(ed) {
    var pending = null;
    function post() {
      pending = null;
      var pos = ed.getPosition();
      if (!pos || !activeTabId) return;
      toNative("editor.position", {
        tabId: activeTabId, line: pos.lineNumber, column: pos.column,
      });
    }
    ed.onDidChangeCursorPosition(function () {
      if (pending) return;         // one report per idle moment, not per keypress
      pending = setTimeout(post, 250);
    });
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
        case "buffer.scrollTo":
          if (editor) {
            var to = p.where === "top" ? 0 : editor.getScrollHeight();
            editor.setScrollPosition({ scrollTop: to });
          }
          break;
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
          // Room to scroll past the last line: without it the end of the file
          // is a hard stop, which on a short keyboard-shrunk viewport is most
          // of the scrolling you do and feels like the editor fighting back.
          scrollBeyondLastLine: true,
          padding: { top: 8 },
          // compact gutter: keep the numbers, drop the width they don't need
          lineNumbersMinChars: 2,
          lineDecorationsWidth: 2,
          glyphMargin: false,
          folding: false,
          // touch-friendlier defaults for M0; the native accessory bar is next
          mouseWheelZoom: false,
          // Scrolling belongs to Monaco alone: it must not hand an overscroll
          // to the page (which then bounces back), and an animated catch-up
          // after a flick reads as the view moving on its own.
          smoothScrolling: false,
          // Per-cursor-move re-decoration of every matching word costs a frame
          // on each keystroke and buys little on a phone screen.
          occurrencesHighlight: "off",
          selectionHighlight: false,
          cursorSmoothCaretAnimation: "off",
          scrollbar: {
            alwaysConsumeMouseWheel: true,
            verticalScrollbarSize: 10,
            horizontalScrollbarSize: 10,
          },
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
        tameTouchInertia();
        pinContainerScroll();
        reportPosition(editor);
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
