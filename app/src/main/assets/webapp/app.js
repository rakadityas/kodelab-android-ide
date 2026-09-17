/*
 * Kodelab editor host — the web side of the JSON-RPC bridge.
 *
 * Native -> web:  window.__kodelab.receive({method, params})
 * web -> native:  KodelabHost.post(JSON.stringify({method, params}))
 *
 * The editor is CodeMirror 6 (MIT), bundled locally by scripts/build-web.sh
 * into vendor/codemirror and reached through `window.KodelabCM` — see
 * web/src/editor-core.mjs for what that exposes. This file is first-party.
 *
 * One EditorView; one EditorState per tab. Switching tabs swaps states, so undo
 * history and selection stay per-file; the scroll offset lives on the scroller
 * rather than in the state, so it is saved and restored alongside.
 *
 * Why CodeMirror rather than Monaco: Monaco scrolls by transforming its own
 * content inside a fixed box and synthesises the fling itself, which on a phone
 * reads as a dead, weightless drag no amount of tuning fixes. CodeMirror scrolls
 * a real overflow:auto element, so the WebView's own momentum, overscroll and
 * fling curve apply — the same ones every other app on the device uses.
 */
(function () {
  "use strict";

  var CM = null;
  var view = null;
  var host = null;
  var ready = false;
  var buffers = {}; // tabId -> { state, savedDoc, dirty, scrollTop, languageId, diagnostics }
  var activeTabId = null;
  var readOnly = false;
  var selectionDelayMs = 350; // how long after a selection the chip appears
  var settings = null;
  var themeTokens = null;
  var codeScheme = null;
  var queued = []; // messages that arrived before the editor booted

  // Compartments let one live state be re-dressed without rebuilding it, and
  // one dormant state be brought up to date the moment it is shown.
  var cLanguage = null, cAppearance = null, cBehaviour = null;

  function toNative(method, params) {
    try {
      if (window.KodelabHost && window.KodelabHost.post) {
        window.KodelabHost.post(JSON.stringify({ method: method, params: params || {} }));
      }
    } catch (e) {}
  }

  function stripAlpha(hex) {
    // native sends #RRGGBBAA; CSS and CodeMirror want #RRGGBB here
    return (hex && hex.length === 9) ? hex.slice(0, 7) : hex;
  }

  // ---- theme -------------------------------------------------------------

  /**
   * The colours the editor paints with, from the code scheme when there is one
   * and from the app palette when there isn't. A scheme describes the code area
   * only, so the chrome tokens (border, overlay) always come from the palette —
   * which native keeps in step, since picking a scheme re-derives the palette
   * from it.
   */
  function editorColors() {
    var t = themeTokens || {};
    var s = codeScheme;
    var chrome = {
      border: stripAlpha(t.border) || "#2b373d",
      overlay: stripAlpha(t.overlay) || stripAlpha(t.panel) || "#1b2429",
      muted: stripAlpha(t.textMuted) || "#9aacb2",
    };
    if (s) {
      return {
        dark: s.dark !== false,
        background: s.background, foreground: s.foreground,
        lineNumbers: s.lineNumbers, cursor: s.cursor,
        selection: s.selection, currentLine: s.currentLine,
        border: chrome.border, overlay: chrome.overlay, muted: chrome.muted,
        roles: {
          comment: s.comment, keyword: s.keyword, string: s.string,
          number: s.number, type: s.type,
          "function": s["function"], operator: s.operator,
        },
      };
    }
    return {
      dark: t.base !== "light",
      background: stripAlpha(t.surface) || "#11171a",
      foreground: stripAlpha(t.textPrimary) || "#e7edee",
      lineNumbers: chrome.muted,
      cursor: stripAlpha(t.accent) || "#3fb6c4",
      selection: stripAlpha(t.accentMuted) || chrome.border,
      currentLine: stripAlpha(t.panel) || "#171f24",
      border: chrome.border, overlay: chrome.overlay, muted: chrome.muted,
      roles: {
        comment: chrome.muted,
        keyword: stripAlpha(t.accent) || "#3fb6c4",
        string: stripAlpha(t.good) || "#7fb069",
        number: stripAlpha(t.warn) || "#d1a14a",
        type: stripAlpha(t.accentMuted) || "#7fb0b7",
        "function": stripAlpha(t.accent) || "#3fb6c4",
        operator: chrome.muted,
      },
    };
  }

  function fontStack() {
    var s = settings || {};
    return (s.fontFamily ? '"' + s.fontFamily + '", ' : "") +
      'ui-monospace, Menlo, Consolas, monospace';
  }

  function editorTheme(c) {
    var s = settings || {};
    var lineHeight = String(s.lineHeight || 1.6);
    var font = fontStack();
    return CM.EditorView.theme({
      "&": {
        height: "100%",
        color: c.foreground,
        backgroundColor: c.background,
        fontSize: (s.fontSize || 14) + "px",
      },
      "&.cm-focused": { outline: "none" },
      ".cm-scroller": { fontFamily: font, lineHeight: lineHeight },
      ".cm-content": {
        fontFamily: font,
        fontVariantLigatures: s.ligatures === false ? "none" : "contextual",
        caretColor: c.cursor,
        paddingTop: "8px",
        // Room to scroll past the last line: without it the end of the file is
        // a hard stop, which on a short keyboard-shrunk viewport is most of the
        // scrolling you do and feels like the editor fighting back.
        paddingBottom: "50vh",
      },
      // The caret and the selection are the browser's own — see boot() — so
      // they are coloured through CSS rather than a drawn overlay.
      ".cm-line::selection, .cm-line ::selection": { backgroundColor: c.selection },
      ".cm-cursor, .cm-dropCursor": { borderLeftColor: c.cursor },
      ".cm-selectionBackground": { backgroundColor: c.selection },
      ".cm-gutters": {
        backgroundColor: c.background,
        color: c.lineNumbers,
        border: "none",
      },
      ".cm-lineNumbers .cm-gutterElement": { padding: "0 6px 0 5px", minWidth: "20px" },
      ".cm-activeLine": { backgroundColor: c.currentLine },
      ".cm-activeLineGutter": { backgroundColor: c.currentLine, color: c.foreground },
      ".cm-matchingBracket, &.cm-focused .cm-matchingBracket": {
        backgroundColor: c.selection, outline: "1px solid " + c.border,
      },
      // Find panel, completion popup and diagnostics — CodeMirror's own UI,
      // which otherwise stays on its stock light colours.
      ".cm-panels": { backgroundColor: c.overlay, color: c.foreground },
      ".cm-panels.cm-panels-top": { borderBottom: "1px solid " + c.border },
      ".cm-panel.cm-search": { padding: "6px 8px", fontFamily: "system-ui, sans-serif" },
      ".cm-panel.cm-search input, .cm-panel.cm-search button, .cm-panel.cm-search label": {
        fontSize: "13px", color: c.foreground,
      },
      ".cm-panel.cm-search input": {
        backgroundColor: c.background, border: "1px solid " + c.border,
        borderRadius: "4px", padding: "6px 8px",
      },
      ".cm-panel.cm-search button": {
        backgroundColor: c.background, backgroundImage: "none",
        border: "1px solid " + c.border, borderRadius: "4px", padding: "6px 10px",
      },
      ".cm-button": { backgroundImage: "none" },
      ".cm-tooltip": {
        backgroundColor: c.overlay, color: c.foreground,
        border: "1px solid " + c.border, borderRadius: "6px",
      },
      ".cm-tooltip.cm-tooltip-autocomplete > ul": { fontFamily: font, maxHeight: "12em" },
      ".cm-tooltip.cm-tooltip-autocomplete > ul > li": { padding: "4px 8px" },
      ".cm-tooltip-autocomplete ul li[aria-selected]": {
        backgroundColor: c.selection, color: c.foreground,
      },
      ".cm-completionMatchedText": { color: c.cursor, textDecoration: "none" },
      ".cm-searchMatch": { backgroundColor: c.selection, outline: "1px solid " + c.border },
      ".cm-searchMatch.cm-searchMatch-selected": { backgroundColor: c.cursor, color: c.background },
    }, { dark: c.dark });
  }

  function appearance() {
    var c = editorColors();
    return [editorTheme(c), CM.syntaxHighlighting(CM.highlightStyle(c.roles))];
  }

  /**
   * `tokens` dress the page; `code`, when present, is the editor's own scheme —
   * background, cursor, selection and the syntax roles that make a theme a
   * theme rather than a change of paper. Without it the editor spreads the app
   * palette's accents across the same roles.
   */
  function applyTheme(tokens, code) {
    if (!tokens) return;
    themeTokens = tokens;
    if (code !== undefined) codeScheme = code;
    var r = document.documentElement.style;
    ["surface", "panel", "overlay", "textPrimary", "textMuted", "accent", "border"]
      .forEach(function (k) {
        if (tokens[k]) r.setProperty("--" + k, stripAlpha(tokens[k]));
      });
    // The page behind the editor has to match the code area, or the padding
    // around it stays the old colour.
    if (codeScheme) r.setProperty("--surface", codeScheme.background);
    if (ready && view) {
      view.dispatch({ effects: cAppearance.reconfigure(appearance()) });
    }
  }

  // ---- settings ----------------------------------------------------------

  function behaviour() {
    var s = settings || {};
    var width = s.tabWidth || 4;
    var unit = s.insertSpaces === false ? "\t" : new Array(width + 1).join(" ");
    var ext = [
      CM.EditorState.tabSize.of(width),
      CM.indentUnit.of(unit),
      CM.EditorState.readOnly.of(readOnly),
      // editable:false takes the content out of contenteditable, which is what
      // keeps the soft keyboard shut in reading mode.
      CM.EditorView.editable.of(!readOnly),
    ];
    if (s.wordWrap) ext.push(CM.EditorView.lineWrapping);
    return ext;
  }

  function applySettings(p) {
    settings = p;
    readOnly = p.readOnly === true;
    if (typeof p.selectionDelayMs === "number") selectionDelayMs = p.selectionDelayMs;
    if (!ready || !view) return;
    reconfigure();
    if (readOnly && view.hasFocus && document.activeElement) document.activeElement.blur();
  }

  /** Bring the live state's theme and settings up to date in one transaction. */
  function reconfigure() {
    if (!view) return;
    view.dispatch({
      effects: [
        cAppearance.reconfigure(appearance()),
        cBehaviour.reconfigure(behaviour()),
      ],
    });
  }

  // ---- buffers -----------------------------------------------------------

  function setDirty(tabId, dirty) {
    var b = buffers[tabId];
    if (!b || b.dirty === dirty) return;
    b.dirty = dirty;
    toNative("editor.dirtyChanged", { tabId: tabId, dirty: dirty });
  }

  /**
   * Dirty is "does this differ from what is on disk", not "has it been edited":
   * undoing back to the saved text has to clear the marker. Comparing documents
   * gives that for free, and the length check in front of it means the common
   * case — a file that has actually changed size — costs one integer compare.
   */
  function refreshDirty(tabId) {
    var b = buffers[tabId];
    if (!b) return;
    var doc = b.state.doc;
    setDirty(tabId, doc.length !== b.savedDoc.length || !doc.eq(b.savedDoc));
  }

  function makeState(text, languageId) {
    var lang = CM.languageFor(languageId);
    return CM.EditorState.create({
      doc: text,
      extensions: [
        cLanguage.of(lang ? [lang] : []),
        cAppearance.of(appearance()),
        cBehaviour.of(behaviour()),
        baseExtensions(),
      ],
    });
  }

  function openBuffer(tabId, text, languageId) {
    if (!ready) return; // replayed by native after editor.ready
    var b = buffers[tabId];
    if (b) {
      // reload from disk (e.g. WebView recreation replay)
      if (activeTabId === tabId && view) {
        view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } });
        b.state = view.state;
      } else {
        b.state = makeState(text, b.languageId);
      }
      b.savedDoc = b.state.doc;
      setDirty(tabId, false);
      return;
    }
    var id = languageId || "plaintext";
    var state = makeState(text, id);
    buffers[tabId] = {
      state: state,
      savedDoc: state.doc,
      dirty: false,
      scrollTop: 0,
      languageId: id,
      diagnostics: null,
    };
    if (!activeTabId) showBuffer(tabId);
  }

  function showBuffer(tabId) {
    var b = buffers[tabId];
    if (!b || !view) return;
    if (activeTabId && activeTabId !== tabId && buffers[activeTabId]) {
      buffers[activeTabId].state = view.state;
      buffers[activeTabId].scrollTop = view.scrollDOM.scrollTop;
    }
    activeTabId = tabId;
    hideSelectionUi();
    view.setState(b.state);
    reconfigure();          // settings may have moved while this tab was away
    applyDiagnostics(tabId);
    // Focus first: taking focus makes the browser scroll the caret into view,
    // which would undo the restore if it came second.
    if (!readOnly) view.focus();
    restoreScroll(b.scrollTop || 0);
  }

  /**
   * Put the scroller back where this tab left it. CodeMirror finishes laying
   * out a swapped-in document in a measure pass after the call returns, and
   * that pass can re-anchor the scroller, so the offset is written again once
   * the frame has settled.
   */
  function restoreScroll(top) {
    if (!view) return;
    view.scrollDOM.scrollTop = top;
    requestAnimationFrame(function () {
      if (view && Math.abs(view.scrollDOM.scrollTop - top) > 1) {
        view.scrollDOM.scrollTop = top;
      }
    });
  }

  function revealLine(tabId, line) {
    if (!buffers[tabId]) return;
    if (activeTabId !== tabId) showBuffer(tabId);
    if (!view) return;
    var doc = view.state.doc;
    var ln = Math.min(Math.max(1, line | 0), doc.lines);
    var at = doc.line(ln).from;
    view.dispatch({
      selection: { anchor: at },
      effects: CM.EditorView.scrollIntoView(at, { y: "center" }),
    });
    if (!readOnly) view.focus();
  }

  function closeBuffer(tabId) {
    if (!buffers[tabId]) return;
    delete buffers[tabId];
    if (activeTabId === tabId) {
      activeTabId = null;
      hideSelectionUi();
      if (view) view.setState(makeState("", "plaintext"));
    }
  }

  // ---- diagnostics -------------------------------------------------------

  // Native sends LSP-shaped markers in 1-based line/column; CodeMirror wants
  // document offsets.
  var SEVERITY = { 8: "error", 4: "warning", 2: "info", 1: "hint" };

  function offsetOf(doc, line, column) {
    var ln = Math.min(Math.max(1, line | 0), doc.lines);
    var l = doc.line(ln);
    return Math.min(l.from + Math.max(0, (column | 0) - 1), l.to);
  }

  function toCmDiagnostics(state, markers) {
    var doc = state.doc;
    return (markers || []).map(function (m) {
      var from = offsetOf(doc, m.startLineNumber, m.startColumn);
      var to = offsetOf(doc, m.endLineNumber, m.endColumn);
      return {
        from: from,
        to: Math.max(from, to),
        severity: SEVERITY[m.severity] || "info",
        message: m.message || "",
        source: m.source || "lsp",
      };
    });
  }

  /** Diagnostics live on the state, so a tab that isn't shown keeps them until it is. */
  function applyDiagnostics(tabId) {
    if (activeTabId !== tabId || !view) return;
    var b = buffers[tabId];
    if (!b || !b.diagnostics) return;
    view.dispatch(CM.setDiagnostics(view.state, toCmDiagnostics(view.state, b.diagnostics)));
  }

  // ---- text in, text out -------------------------------------------------

  function selectionText() {
    if (view) {
      var sel = view.state.selection.main;
      if (!sel.empty) return view.state.sliceDoc(sel.from, sel.to);
    }
    if (!domSelectionRect()) return "";
    return String(document.getSelection());
  }

  /** The selection, or failing that the word under the cursor, sent to native. */
  function sendSymbol(method) {
    if (!view) return;
    var text = selectionText();
    if (!text) {
      var head = view.state.selection.main.head;
      var word = view.state.wordAt(head);
      if (word) text = view.state.sliceDoc(word.from, word.to);
    }
    text = (text || "").trim();
    if (!text) return;
    toNative(method, { text: text, tabId: activeTabId });
  }

  function typeText(text) {
    if (!view || readOnly || !text) return;
    view.focus();
    view.dispatch(
      view.state.changeByRange(function (range) {
        return {
          changes: { from: range.from, to: range.to, insert: text },
          range: CM.EditorSelection.cursor(range.from + text.length),
        };
      }),
      { scrollIntoView: true, userEvent: "input.type" },
    );
  }

  /**
   * Snippet bodies are authored in the VS Code dialect that extensions already
   * ship. CodeMirror reads most of that dialect as-is — `${1:name}` is a
   * numbered stop with a placeholder, `${0}` is where the cursor lands last,
   * and repeats of a number are one field, so filling it fills every copy. The
   * one form it doesn't take is the braceless `$1`, so that is all this adds,
   * carrying the placeholder text across to the repeats the way VS Code shows
   * them.
   */
  function toCmSnippet(body) {
    var labels = {};
    var s = String(body);
    var named = /\$\{(\d+):([^{}]*)\}/g, m;
    while ((m = named.exec(s))) labels[m[1]] = m[2];
    return s.replace(/\$(\d+)/g, function (_, n) {
      return labels[n] ? "${" + n + ":" + labels[n] + "}" : "${" + n + "}";
    });
  }

  function insertSnippet(body) {
    if (!view || readOnly || !body) return;
    view.focus();
    var sel = view.state.selection.main;
    CM.snippet(toCmSnippet(body))(view, null, sel.from, sel.to);
  }

  /**
   * Accessory-bar keys. Native sends names rather than CodeMirror functions, so
   * the bar stays a native concern and this stays the only place that knows how
   * a key becomes an edit.
   */
  function editorCommands() {
    return {
      tab: CM.insertTab,
      undo: CM.historyCommands.undo,
      redo: CM.historyCommands.redo,
      cursorLeft: CM.cursorCharLeft,
      cursorRight: CM.cursorCharRight,
      cursorUp: CM.cursorLineUp,
      cursorDown: CM.cursorLineDown,
      // Boundary rather than line start/end, so with wrapping on these land
      // where the eye expects rather than at the ends of the logical line.
      cursorHome: CM.cursorLineBoundaryBackward,
      cursorEnd: CM.cursorLineBoundaryForward,
      selectAll: CM.selectAll,
      find: function (v) { CM.openSearchPanel(v); return true; },
    };
  }

  var COMMANDS = null;

  function execCommand(name) {
    if (!view || !COMMANDS) return;
    var fn = COMMANDS[name];
    if (!fn) return;
    // Opening the find panel moves the code down under it, which would leave
    // the chip pointing at the wrong line.
    hideSelectionUi();
    view.focus();
    fn(view);
  }

  // ---- context menu ------------------------------------------------------
  //
  // A chip that appears once a selection settles and opens a menu when tapped.
  // It no longer opens on long press: since the code lives in a contenteditable
  // the platform owns that gesture, and a menu of ours arriving at the same
  // 500ms as Android's own word-select and toolbar was two popups for one
  // press. What the chip carries that the system toolbar cannot is "Find
  // references" and "Go to definition".
  //
  // Clipboard work goes through native rather than navigator.clipboard: a
  // WebView grants reads grudgingly, and the phone's clipboard is the one the
  // user actually copied into.

  var selChip = null, selMenu = null, selShowTimer = null;

  function hideSelectionUi() {
    // Guarded: this runs on every scroll event, and an unconditional DOM write
    // per frame is felt on a phone.
    if (selChip && !selChip.hidden) selChip.hidden = true;
    if (selMenu && !selMenu.hidden) selMenu.hidden = true;
  }

  function placeAt(el, top, left) {
    var maxLeft = Math.max(4, (host ? host.clientWidth : 320) - el.offsetWidth - 8);
    var maxTop = Math.max(4, (host ? host.clientHeight : 480) - el.offsetHeight - 8);
    el.style.left = Math.min(Math.max(4, left), maxLeft) + "px";
    el.style.top = Math.min(Math.max(4, top), maxTop) + "px";
  }

  /**
   * The browser's selection, when it is inside the editor and not collapsed.
   *
   * CodeMirror's state is the authority for editing, but it is not always in
   * step with a selection the *platform* made. Its DOM observer ignores a
   * selection change in an editable view unless the contenteditable is the
   * focused element, and it syncs on its own schedule besides — so a word the
   * user just double-tapped can be selected on screen a beat before the state
   * knows. Everything the chip needs (is there a selection, what does it say,
   * where is it) therefore falls back to the DOM.
   */
  function domSelectionRect() {
    var sel = document.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
    var range = sel.getRangeAt(0);
    if (!host || !host.contains(range.commonAncestorContainer)) return null;
    var rects = range.getClientRects();
    return rects.length ? rects[rects.length - 1] : range.getBoundingClientRect();
  }

  function hasSelection() {
    if (view && !view.state.selection.main.empty) return true;
    return !!domSelectionRect();
  }

  /** Show the menu at a point, with only the items that apply right now. */
  function showMenu(top, left) {
    if (!selMenu) return;
    if (selShowTimer) { clearTimeout(selShowTimer); selShowTimer = null; }
    var sel = hasSelection();
    Array.prototype.forEach.call(selMenu.querySelectorAll("button"), function (b) {
      var needs = (b.getAttribute("data-needs") || "").split(" ").filter(Boolean);
      b.hidden = !needs.every(function (n) {
        return n === "selection" ? sel : n === "edit" ? !readOnly : true;
      });
    });
    if (selChip) selChip.hidden = true;
    selMenu.hidden = false;
    selMenu.style.visibility = "hidden";
    requestAnimationFrame(function () {
      placeAt(selMenu, top, left);
      selMenu.style.visibility = "visible";
    });
  }

  function runAction(act) {
    if (!view) return;
    if (act === "copy") {
      toNative("editor.copy", { text: selectionText() });
    } else if (act === "cut") {
      var sel = view.state.selection.main;
      toNative("editor.copy", { text: selectionText() });
      if (!sel.empty && !readOnly) {
        view.dispatch({
          changes: { from: sel.from, to: sel.to, insert: "" },
          userEvent: "delete.cut",
        });
      }
    } else if (act === "paste") {
      // Native reads the phone's clipboard and sends the text back as
      // input.type, which lands at the cursor (replacing any selection).
      toNative("editor.requestPaste", {});
    } else if (act === "selectAll") {
      CM.selectAll(view);
      view.focus();
    } else if (act !== "dismiss") {
      sendSymbol("editor." + act);
    }
  }

  /**
   * Show the chip once a selection has settled, so it doesn't jump around under
   * the finger mid-drag.
   *
   * It is anchored to the end of the selection, which is exactly where Android
   * hangs the right-hand drag handle: the handle's top edge starts at the line's
   * bottom and it reaches roughly 24dp below that, so a chip tucked in just
   * under the text lands underneath it and loses every touch to the handle.
   * Hence the clearance — enough to sit below the handle, not so much that the
   * chip floats away from what it acts on.
   */
  var HANDLE_CLEARANCE_PX = 46;
  function scheduleSelectionChip() {
    if (selShowTimer) { clearTimeout(selShowTimer); selShowTimer = null; }
    if (!selChip) return;
    if (!hasSelection()) { selChip.hidden = true; return; }
    selShowTimer = setTimeout(function () {
      selShowTimer = null;
      if (!hasSelection()) return;
      var sel = view.state.selection.main;
      var pt = sel.empty ? domSelectionRect() : view.coordsAtPos(sel.to);
      if (!pt) return;
      var rect = host.getBoundingClientRect();
      selChip.hidden = false;
      selChip.style.visibility = "hidden";
      requestAnimationFrame(function () {
        placeAt(selChip, pt.bottom - rect.top + HANDLE_CLEARANCE_PX, pt.left - rect.left);
        selChip.style.visibility = "visible";
      });
    }, selectionDelayMs);
  }

  function wireSelectionActions() {
    selChip = document.getElementById("sel-chip");
    selMenu = document.getElementById("sel-menu");
    if (!selChip || !selMenu) return;

    // Tapping the chip is what opens the menu — the selection stays visible
    // until you ask for something to do with it.
    selChip.addEventListener("click", function (e) {
      e.preventDefault();
      e.stopPropagation();
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

    // Selecting text is the platform's gesture now, and the platform does not
    // route it through a CodeMirror transaction in every case, so the chip is
    // scheduled from the DOM event as well as from the update listener.
    document.addEventListener("selectionchange", function () {
      // A tick late on purpose: CodeMirror's own selectionchange handler runs
      // first and may reconcile the selection into its state.
      setTimeout(scheduleSelectionChip, 0);
    }, { passive: true });
  }

  // ---- position reporting ------------------------------------------------

  var positionTimer = null;

  /** Tell native where the cursor is, so it can record navigation history. */
  function schedulePosition() {
    if (positionTimer) return;   // one report per idle moment, not per keypress
    positionTimer = setTimeout(function () {
      positionTimer = null;
      if (!view || !activeTabId) return;
      var head = view.state.selection.main.head;
      var line = view.state.doc.lineAt(head);
      toNative("editor.position", {
        tabId: activeTabId,
        line: line.number,
        column: head - line.from + 1,
      });
    }, 250);
  }

  // ---- the view -----------------------------------------------------------

  function onUpdate(u) {
    if (u.docChanged || u.selectionSet) {
      hideSelectionUi();
      scheduleSelectionChip();
      schedulePosition();
    }
    if (!activeTabId) return;
    var b = buffers[activeTabId];
    if (!b) return;
    b.state = u.state;
    if (u.docChanged) refreshDirty(activeTabId);
  }

  function baseExtensions() {
    return [
      CM.lineNumbers(),
      CM.highlightSpecialChars(),
      CM.history(),
      CM.bracketMatching(),
      CM.closeBrackets(),
      CM.autocompletion({ closeOnBlur: true }),
      CM.search({ top: true }),
      CM.highlightActiveLine(),
      CM.highlightActiveLineGutter(),
      // Android's autocorrect rewrites identifiers into English words, and its
      // capitalisation rules have opinions about the start of every line.
      CM.EditorView.contentAttributes.of({
        autocorrect: "off", autocapitalize: "off", spellcheck: "false",
      }),
      CM.keymap.of([].concat(
        CM.closeBracketsKeymap,
        CM.defaultKeymap,
        CM.searchKeymap,
        CM.historyKeymap,
        CM.completionKeymap,
        [CM.indentWithTab],
      )),
      CM.EditorView.updateListener.of(onUpdate),
    ];
  }

  // ---- bridge -------------------------------------------------------------

  var api = {
    receive: function (msg) {
      if (!msg || !msg.method) return;
      if (!ready && msg.method.indexOf("buffer.") === 0) {
        queued.push(msg);
        return;
      }
      var p = msg.params || {};
      switch (msg.method) {
        case "theme.apply":     applyTheme(p.tokens, p.code || null); break;
        case "settings.apply":  applySettings(p); break;
        case "buffer.open":     openBuffer(p.tabId, p.text || "", p.languageId); break;
        case "buffer.show":     showBuffer(p.tabId); break;
        case "buffer.reveal":   revealLine(p.tabId, p.line || 1); break;
        case "buffer.close":    closeBuffer(p.tabId); break;
        case "lsp.diagnostics": {
          var lb = buffers[p.tabId];
          if (lb) { lb.diagnostics = p.markers || []; applyDiagnostics(p.tabId); }
          break;
        }
        case "buffer.requestSave": {
          var sb = buffers[p.tabId];
          toNative("buffer.save", { tabId: p.tabId, text: sb ? sb.state.doc.toString() : "" });
          break;
        }
        case "buffer.markSaved": {
          var mb = buffers[p.tabId];
          if (mb) { mb.savedDoc = mb.state.doc; setDirty(p.tabId, false); }
          break;
        }
        case "buffer.scrollTo":
          if (view) {
            var el = view.scrollDOM;
            el.scrollTop = p.where === "top" ? 0 : el.scrollHeight;
          }
          break;
        case "input.exec":  execCommand(p.command); break;
        case "input.type":  typeText(p.text || ""); break;
        case "input.snippet": insertSnippet(p.snippet || ""); break;
        case "editor.requestSymbol":
          sendSymbol(p.reply || "editor.findReferences");
          break;
      }
    },
  };
  window.__kodelab = api;

  function boot() {
    CM = window.KodelabCM;
    if (!CM) {
      // no vendored bundle — surface it, stay on the fallback message
      document.getElementById("fallback").hidden = false;
      toNative("editor.ready", { core: "fallback" });
      return;
    }
    document.getElementById("fallback").hidden = true;
    host = document.getElementById("editor");
    host.hidden = false;

    cLanguage = new CM.Compartment();
    cAppearance = new CM.Compartment();
    cBehaviour = new CM.Compartment();
    COMMANDS = editorCommands();

    view = new CM.EditorView({
      state: makeState("", "plaintext"),
      parent: host,
      // Deliberately no drawSelection(): the caret, the selection, the drag
      // handles and the magnifier are the platform's own, which is what makes
      // selecting text on a touch screen feel like every other app — and is the
      // thing Monaco's hidden textarea could never give us. Nothing native may
      // suppress the selection toolbar that comes with them; see EditorWebView.kt.
    });

    // The selection UI is anchored in page coordinates, so scrolling invalidates
    // it. Cheaper to hide it than to follow the scroll.
    view.scrollDOM.addEventListener("scroll", hideSelectionUi, { passive: true });

    wireSelectionActions();
    ready = true;
    if (themeTokens) applyTheme(themeTokens, codeScheme);
    if (settings) applySettings(settings);
    var q = queued; queued = [];
    q.forEach(api.receive);
    toNative("editor.ready", { core: "codemirror" });
  }

  boot();
})();
