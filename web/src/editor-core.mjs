/*
 * Kodelab editor core — the bundled half of the editor.
 *
 * CodeMirror 6 ships as ES modules, so unlike Monaco there is no prebuilt file
 * to copy: scripts/build-web.sh bundles this entry with esbuild into
 * assets/webapp/vendor/codemirror/codemirror.js. Everything imported here is
 * MIT (see NOTICE) and served from the app's own origin, never a CDN.
 *
 * This file exists to choose a surface, not to hold logic: it re-exports the
 * pieces app.js needs on `window.KodelabCM`, plus two helpers that have to live
 * on this side because they touch bundled types — the language registry and the
 * highlight style built from a Kodelab CodeScheme.
 */
import {EditorState, Compartment, EditorSelection} from "@codemirror/state";
import {
  EditorView, keymap, lineNumbers, highlightActiveLine,
  highlightActiveLineGutter, highlightSpecialChars,
} from "@codemirror/view";
import {
  defaultKeymap, history, historyKeymap, undo, redo, insertTab, selectAll,
  cursorCharLeft, cursorCharRight, cursorLineUp, cursorLineDown,
  cursorLineBoundaryBackward, cursorLineBoundaryForward, indentWithTab,
} from "@codemirror/commands";
import {search, searchKeymap, openSearchPanel, closeSearchPanel} from "@codemirror/search";
import {
  autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap, snippet,
} from "@codemirror/autocomplete";
import {setDiagnostics} from "@codemirror/lint";
import {
  syntaxHighlighting, HighlightStyle, indentUnit, bracketMatching,
  StreamLanguage,
} from "@codemirror/language";
import {tags as t} from "@lezer/highlight";

// ---- languages ---------------------------------------------------------
//
// Ids are Kodelab's own (editor/Languages.kt) and every one of them resolves
// here, so a file type the native side claims to know is never silently
// unhighlighted. Parsers marked "stream" are the CodeMirror 5 modes kept alive
// in @codemirror/legacy-modes: coarser than a Lezer grammar, but they cover the
// long tail that has no Lezer parser yet.

import {javascript} from "@codemirror/lang-javascript";
import {json} from "@codemirror/lang-json";
import {java} from "@codemirror/lang-java";
import {python} from "@codemirror/lang-python";
import {rust} from "@codemirror/lang-rust";
import {cpp} from "@codemirror/lang-cpp";
import {php} from "@codemirror/lang-php";
import {html} from "@codemirror/lang-html";
import {css} from "@codemirror/lang-css";
import {less} from "@codemirror/lang-less";
import {sass} from "@codemirror/lang-sass";
import {xml} from "@codemirror/lang-xml";
import {markdown} from "@codemirror/lang-markdown";
import {sql} from "@codemirror/lang-sql";
import {go} from "@codemirror/lang-go";
import {yaml} from "@codemirror/lang-yaml";

import {c as cMode, kotlin, csharp, scala, dart, objectiveC} from "@codemirror/legacy-modes/mode/clike";
import {swift} from "@codemirror/legacy-modes/mode/swift";
import {ruby} from "@codemirror/legacy-modes/mode/ruby";
import {shell} from "@codemirror/legacy-modes/mode/shell";
import {powerShell} from "@codemirror/legacy-modes/mode/powershell";
import {lua} from "@codemirror/legacy-modes/mode/lua";
import {perl} from "@codemirror/legacy-modes/mode/perl";
import {r} from "@codemirror/legacy-modes/mode/r";
import {groovy} from "@codemirror/legacy-modes/mode/groovy";
import {toml} from "@codemirror/legacy-modes/mode/toml";
import {properties} from "@codemirror/legacy-modes/mode/properties";
import {dockerFile} from "@codemirror/legacy-modes/mode/dockerfile";
import {cmake} from "@codemirror/legacy-modes/mode/cmake";
import {protobuf} from "@codemirror/legacy-modes/mode/protobuf";
import {diff} from "@codemirror/legacy-modes/mode/diff";
import {simpleMode} from "@codemirror/legacy-modes/mode/simple-mode";

/*
 * Three file types Kodelab maps that neither Lezer nor the legacy modes cover.
 * They are deliberately shallow — comments, strings, keywords and the shape of
 * an assignment — which is the part of highlighting you actually read on a
 * phone. Token names must be bare @lezer/highlight tags.
 */
const makefileMode = simpleMode({
  start: [
    {regex: /#.*/, token: "comment"},
    {regex: /\$[({][\w.\-+/]*[)}]/, token: "variableName"},
    {regex: /^\.?[\w%.\-+/$(){} ]+(?=:(?!=))/, token: "typeName"},
    {regex: /\b(ifeq|ifneq|ifdef|ifndef|else|endif|include|-include|define|endef|export|unexport|override|vpath)\b/, token: "keyword"},
    {regex: /"(?:[^\\"]|\\.)*"?/, token: "string"},
    {regex: /'(?:[^\\']|\\.)*'?/, token: "string"},
    {regex: /^[\w.\-]+(?=\s*[:+?]?=)/, token: "propertyName"},
  ],
});

const hclMode = simpleMode({
  start: [
    {regex: /#.*|\/\/.*/, token: "comment"},
    {regex: /\/\*/, token: "comment", next: "comment"},
    {regex: /"(?:[^\\"]|\\.)*"?/, token: "string"},
    {regex: /<<-?(\w+)/, token: "string", next: "heredoc"},
    {regex: /\b(resource|provider|variable|output|module|data|terraform|locals|backend|dynamic|for|in|if|else|endif|true|false|null)\b/, token: "keyword"},
    {regex: /\b\d+(?:\.\d+)?\b/, token: "number"},
    {regex: /[\w.\-]+(?=\s*=[^=])/, token: "propertyName"},
    {regex: /\$\{|\}/, token: "operator"},
  ],
  comment: [
    {regex: /.*?\*\//, token: "comment", next: "start"},
    {regex: /.*/, token: "comment"},
  ],
  heredoc: [
    {regex: /^\s*\w+\s*$/, token: "string", next: "start"},
    {regex: /.*/, token: "string"},
  ],
});

const graphqlMode = simpleMode({
  start: [
    {regex: /#.*/, token: "comment"},
    {regex: /"""/, token: "string", next: "blockString"},
    {regex: /"(?:[^\\"]|\\.)*"?/, token: "string"},
    {regex: /\b(query|mutation|subscription|fragment|on|type|input|enum|interface|union|scalar|schema|directive|extend|implements|repeatable|true|false|null)\b/, token: "keyword"},
    {regex: /\$\w+/, token: "variableName"},
    {regex: /@\w+/, token: "meta"},
    {regex: /\b[A-Z]\w*\b/, token: "typeName"},
    {regex: /\b\d+(?:\.\d+)?\b/, token: "number"},
    {regex: /\w+(?=\s*:)/, token: "propertyName"},
  ],
  blockString: [
    {regex: /.*?"""/, token: "string", next: "start"},
    {regex: /.*/, token: "string"},
  ],
});

const stream = (mode) => StreamLanguage.define(mode);

const LANGUAGES = {
  javascript: () => javascript({jsx: true}),
  typescript: () => javascript({jsx: true, typescript: true}),
  json: () => json(),
  java: () => java(),
  python: () => python(),
  rust: () => rust(),
  c: () => stream(cMode),
  cpp: () => cpp(),
  php: () => php(),
  html: () => html(),
  css: () => css(),
  scss: () => sass({indented: false}),
  less: () => less(),
  xml: () => xml(),
  markdown: () => markdown(),
  sql: () => sql(),
  go: () => go(),
  yaml: () => yaml(),
  kotlin: () => stream(kotlin),
  csharp: () => stream(csharp),
  scala: () => stream(scala),
  dart: () => stream(dart),
  "objective-c": () => stream(objectiveC),
  swift: () => stream(swift),
  ruby: () => stream(ruby),
  shell: () => stream(shell),
  powershell: () => stream(powerShell),
  lua: () => stream(lua),
  perl: () => stream(perl),
  r: () => stream(r),
  groovy: () => stream(groovy),
  toml: () => stream(toml),
  ini: () => stream(properties),
  dockerfile: () => stream(dockerFile),
  cmake: () => stream(cmake),
  protobuf: () => stream(protobuf),
  diff: () => stream(diff),
  makefile: () => stream(makefileMode),
  hcl: () => stream(hclMode),
  graphql: () => stream(graphqlMode),
};

const languageCache = {};

/** The language extension for a Kodelab language id, or null for plaintext. */
function languageFor(id) {
  const make = LANGUAGES[id];
  if (!make) return null;
  if (!languageCache[id]) languageCache[id] = make();
  return languageCache[id];
}

// ---- syntax colours ----------------------------------------------------

/**
 * Turn a Kodelab CodeScheme's seven token roles into a CodeMirror highlight
 * style. A Lezer grammar emits far more tags than that, so each role gathers
 * the tags that read as the same thing — `keyword` takes the modifiers and the
 * literals, `type` takes class names, annotations and markup attributes — and
 * anything left over inherits the editor foreground.
 */
function highlightStyle(role) {
  return HighlightStyle.define([
    {tag: [t.comment, t.lineComment, t.blockComment, t.docComment],
     color: role.comment, fontStyle: "italic"},
    {tag: [t.keyword, t.controlKeyword, t.definitionKeyword, t.moduleKeyword,
           t.operatorKeyword, t.modifier, t.self, t.atom, t.bool, t.null],
     color: role.keyword},
    {tag: [t.string, t.special(t.string), t.character, t.regexp, t.attributeValue,
           t.docString, t.url],
     color: role.string},
    {tag: [t.number, t.integer, t.float, t.constant(t.variableName), t.literal],
     color: role.number},
    {tag: [t.typeName, t.className, t.namespace, t.annotation, t.attributeName,
           t.tagName, t.standard(t.tagName), t.angleBracket],
     color: role.type},
    {tag: [t.function(t.variableName), t.function(t.propertyName),
           t.definition(t.function(t.variableName)), t.macroName, t.labelName,
           t.special(t.variableName)],
     color: role.function},
    {tag: [t.operator, t.derefOperator, t.punctuation, t.separator, t.bracket,
           t.squareBracket, t.paren, t.brace, t.escape, t.meta, t.processingInstruction],
     color: role.operator},
    // Markup — Kodelab opens README files often enough that markdown deserves
    // more than one flat colour.
    {tag: t.heading, color: role.function, fontWeight: "700"},
    {tag: t.strong, fontWeight: "700"},
    {tag: t.emphasis, fontStyle: "italic"},
    {tag: t.link, color: role.function, textDecoration: "underline"},
    {tag: t.monospace, color: role.string},
    {tag: t.strikethrough, textDecoration: "line-through"},
    {tag: t.invalid, color: role.keyword, textDecoration: "underline wavy"},
  ]);
}

window.KodelabCM = {
  EditorState, EditorView, EditorSelection, Compartment,
  keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
  highlightSpecialChars,
  defaultKeymap, history, historyKeymap, historyCommands: {undo, redo},
  insertTab, selectAll, indentWithTab,
  cursorCharLeft, cursorCharRight, cursorLineUp, cursorLineDown,
  cursorLineBoundaryBackward, cursorLineBoundaryForward,
  search, searchKeymap, openSearchPanel, closeSearchPanel,
  autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap, snippet,
  setDiagnostics,
  syntaxHighlighting, indentUnit, bracketMatching,
  languageFor, highlightStyle,
};
