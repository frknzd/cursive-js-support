# Completion audit: mzg2

Date: 2026-08-21

## Scope and method

This audit sampled `/Users/frkn/Workspace/mzg2` read-only and exercised the plugin's document
context detector, bundled TypeScript browser index, and npm declaration extractor headlessly.
The corpus contains 286 `.cljs`/`.cljc` files. A lexical inventory found:

- 355 `js/...` uses across 71 distinct chains;
- 522 dot-method calls across 117 distinct method names;
- 316 dot-property accesses across 85 distinct property names;
- 72 explicit `^js`/`^js.Type` hints;
- 11 npm require bindings in six files: four `:as`, three `:default`, and four `:refer`.

The repository now includes `MergedCompletionFixtureTest`, an IntelliJ code-insight fixture that
opens physical `.cljs` project files and invokes basic completion. It runs Cursive's real
`ClojureCompletionContributor` together with this plugin and verifies typed globals, nested
members, static members, unknown-receiver filtering, and duplicate rows.

Cursive 2026.2 still references Java PSI and JCEF protocol-key classes removed from IU 262. The
test runtime supplies narrow compatibility types for those namespace-registration branches and
loads IntelliJ's Java test framework; no production classes are replaced. With that bridge,
Cursive initializes and all merged interop scenarios execute successfully. In the SDK-less light
project it contributes zero rows for sampled local definitions, lexical bindings, core functions,
and special forms. This is now a reproducible baseline: adding a ClojureScript SDK/library model
is the remaining fixture gap for measuring Cursive's own result ordering and presentation.

The fixture now also seeds physical `cljs.core` and project namespace source files and disables
single-item auto-insertion, so a one-row popup cannot be mistaken for an empty result. Cursive
still returns no rows for those sources: its completion implementation asks the editor symbol's
reference for variants, and ordinary source files alone do not populate the library/analyzer
model used for `cljs.core`, namespace, and synthetic `<global>` variants.

There is also a cold/warm-index distinction. An isolated test run returned no Cursive rows; in the
full suite, the local-definition and lexical-binding probes each returned the primary lookup
string `local` for a source symbol named `local-value`. Core, special-form, qualified-core, and
project-namespace probes stayed empty. Tests therefore accept the cold empty state and the warm
`local` state while rejecting other changes. Presentation and all lookup aliases must be captured
before treating this as a duplicate or insertion bug—the primary lookup string alone is truncated.

## Cursive contribution and overlap analysis

Cursive's real-project popup combines three conceptually different sources: lexical/project
symbols, namespaces and `cljs.core`, and ClojureScript analyzer entries from the synthetic
`<global>` namespace. Only the last category overlaps this plugin's TypeScript global index.

| Context | Cursive source | This plugin source | Expected overlap | Quality risk |
|---|---|---|---|---|
| `loc` in a function | lexical resolution | none | none | Cursive is appropriately narrow |
| `cljs.core/ma` | `cljs.core` vars | none | none | core results disappear if the SDK/library model is absent |
| `(:require [ai.ibis...` | namespace index | npm packages only inside string/package slots | none | namespace completion may be very broad in a monorepo |
| `js/doc` | analyzer `<global>` | DOM TypeScript globals | `document` | exact duplicate likely |
| `js/con` | analyzer `<global>` | DOM/ECMAScript globals | `console` | exact duplicate likely |
| `js/setT` | analyzer `<global>` | DOM plus Node/Bun globals when enabled | `setTimeout` | duplicate plus environment ambiguity |
| `js/pro` | analyzer `<global>` | runtime-specific globals | `process` in Node builds | Cursive may be narrower when analyzer data matches the build |
| `js/document.cre` | generally only a global root from Cursive | typed `Document` members | little/no overlap | this plugin is usefully narrower and richer |
| `(.sto unknown)` | generic Cursive interop/reference variants | method-only fallback sample | possible name overlap | both can be too generic without receiver evidence |

The safest merge policy is therefore provenance-aware deduplication by lookup string only for
`js/` globals. Prefer this plugin's row when it has a TypeScript type, signature, documentation,
or navigation target; retain Cursive's row when it is the only analyzer-confirmed symbol for the
active build. Never deduplicate ordinary Clojure symbols or namespaces against JS rows.

### Too generic scenarios

- An empty or one-character `js/` prefix can combine hundreds of browser globals with Node, Bun,
  or Deno declarations. Filter environments by the active build profile before ranking.
- Unknown-receiver dot completion samples members globally. Even method-only filtering leaves
  unrelated APIs with the same prefix; require a longer prefix or demote fallback rows.
- Namespace completion across mzg2 can span the large `.clj-kondo` inventory. Rank current module,
  already-required sibling namespaces, and same source root before transitive libraries.

### Too narrow scenarios

- Cursive's `<global>` data reflects analyzer/build output and can omit valid DOM members or a
  runtime not compiled recently. The TypeScript index should fill those gaps.
- This plugin's typed chain is narrow by design but loses precision after an unknown return type;
  it should fall back one chain segment at a time rather than immediately use every member.
- `cljs.core` and namespace results must remain visible in mixed popups. Stopping completion after
  producing JS rows would incorrectly erase them.

## Implemented improvements

- Typed and semantically resolved members now receive the highest explicit relevance priority.
- ECMAScript globals rank above browser globals, while Node/Bun/Deno-only globals rank below both
  when an unknown/mixed project makes several environments visible.
- Unknown-receiver fallback waits for a two-character prefix, is capped at 50 names instead of
  220, and receives the lowest relevance priority.
- The contributor continues without `stopHere()`. IntelliJ's merged lookup collapses identical
  lookup strings in the tested `js/document.body.cla` case, while Cursive core and namespace rows
  remain eligible to participate.
- The merged fixture now protects the one-character unknown-receiver case, the useful `sto`
  fallback, method/property separation, and absence of duplicate typed-chain rows.
- Completion now uses the shared PSI expression-flow model before its incomplete-document
  fallback. This covers `..`, `->`, `->>`, `some->`, `doto`, and conditional thread positions
  according to each macro's receiver semantics instead of assuming every step has the same slot.
- Qualified constructor expressions such as `(js/Date.)` are normalized at the central type
  resolver, so prefix dot forms such as `(.getT (js/Date.))`, nested calls, documentation, and
  navigation all see the constructed instance type.
- Macro and expression-derived members receive typed-member priority, placing them above generic
  Cursive/core rows without stopping Cursive completion or hiding unrelated namespace symbols.
- Typed member popups now have an explicit authority boundary. When expression inference resolves
  a receiver and the TypeScript index contains its member set, the interop contributor calls
  `stopHere()` after emitting those members. This removes Cursive's unrelated global/prototype
  rows (the screenshot examples included `get`, `g`, `__iterator__`, `addRange`,
  `alignmentBaseline`, and `allMapValues`) instead of merely pushing them lower.
- The authority decision is type- and index-based, not punctuation-based. Cursive remains active
  for unresolved receivers, lexical symbols, namespaces, `cljs.core`, require forms, and any
  context where this plugin cannot prove that its result set is complete.

## Tested scenarios

| Scenario | Context detected | Suggestions from our index | Assessment |
|---|---|---|---|
| `js/doc` | JS global | `document` | Excellent precision |
| `js/document.cre` | `Document` member | 15 valid `create*` members, including `createElement`, `createRange`, and `createTreeWalker` | Complete but alphabetical ranking is weak |
| `js/document.body.cla` | `HTMLElement` member | `classList`, `className` | Excellent precision |
| `js/window.add` | `Window` member | `addEventListener` | Excellent precision |
| `js/Promise.res` | `PromiseConstructor` member | `resolve` | Excellent precision |
| `(.sto unknown-receiver)` | unknown dot-method | `stop`, `stopImmediatePropagation`, `stopPropagation` after method filtering | Useful fallback, but receiver-independent |
| `Fuse/` for `fuse.js :as Fuse` | npm alias export | `FuseIndex`, `default` | Technically correct exports, but not aligned with the project's actual `(Fuse. ...)` usage |

The detector chose the expected context and replacement prefix for all tested forms.

## Real npm typings

The installed declarations under `ai.ibis.mzg2.app/node_modules` were parsed using the same Node
extractor as production indexing.

- `fuse.js`: exports `default` and `FuseIndex`; the default constructor exposes static members
  `config`, `createIndex`, `parseIndex`, `parseQuery`, `use`, and `version`.
- `minisearch`: the default export and constructor metadata were recovered. The project imports
  it with `:as MS`, so constructor completion is more important than slash-export completion.
- `react-markdown`: `default` and `uriTransformer` were recovered; the default value exposes
  `propTypes`.

This confirms that package discovery and declaration extraction work on the real dependency
tree. It also exposes a UX mismatch: namespace-export completion is emphasized for `:as`, while
the mzg2 code commonly treats the alias itself as a constructor.

## What works well

1. Fully typed `js/global.member` chains are the strongest part of completion. Results are narrow,
   inherited DOM members are present, and static constructor members resolve correctly.
2. Prefix replacement is accurate for globals, chained members, dot interop, and npm aliases.
3. TypeScript declaration extraction handles the actual mzg2 package versions, not only fixtures.
4. Method/property separation keeps `(.foo ...)` and `(.-foo ...)` fallback lists substantially
   cleaner than a combined member list.
5. mzg2's explicit `^js/Date` hints should give high-quality Date completion where present.

## Main improvement opportunities

### P0: give the headless project a ClojureScript SDK/library model

The real Cursive contributor now initializes and runs beside this plugin. Its Clojure-only probes
return no rows because the light project has no ClojureScript SDK or indexed core libraries. Add
that model, then record lookup string, presentation, contributor origin, order, and completion
latency. Keep the current zero-row probes as regression coverage for the transition.

### P1: rank by receiver confidence and project usage

Results are currently emitted in index/map order and filtered by a plain prefix matcher. Add
explicit relevance weights:

1. exact typed receiver;
2. semantic JavaScript PSI receiver;
3. explicit `^js.Type` hint;
4. inferred callback parameter;
5. unknown-receiver sample.

Within a tier, boost members already used in the project. In mzg2 this would naturally put
`stopPropagation`, `preventDefault`, `getBoundingClientRect`, `addEventListener`, `scrollTop`, and
`current` near the top without hard-coding DOM names.

### P1: improve callback and percent-argument inference

The corpus repeatedly uses forms such as `#(.stopPropagation %)`, `(fn [e] (.preventDefault e))`,
and `:on-click` callbacks. When `%` or `e` remains untyped, completion falls back to the global
member sample. Infer event types from:

- DOM/Reagent handler keys such as `:on-click`, `:on-key-down`, and `:on-mouse-up`;
- `addEventListener` event-name literals;
- callback signatures already available from TypeScript declarations.

This would turn common `.-target`, `.-key`, and `.preventDefault` completion into typed `Event`,
`MouseEvent`, or `KeyboardEvent` suggestions.

### P1: model constructor aliases directly

For `:as` bindings whose package has a constructable default export, completing `Fuse` or `Fuse.`
should offer a constructor-oriented item with its parameter signature. The current `Fuse/` export
list is valid but secondary to the syntax used by mzg2. The same applies to MiniSearch.

### P2: preserve Cursive's Clojure suggestions while removing duplicate interop rows

The plugin runs first but does not stop completion, so Cursive may add overlapping interop lookup
strings. Once the integration fixture can observe origin and ordering, filter only exact duplicate
interop rows; do not call `stopHere()` broadly, because ordinary Clojure symbols must remain in the
same popup.

### P2: improve unknown-receiver fallback presentation

Unknown receivers currently select one sampled declaring interface per member name. Display an
`unknown receiver`/sampled marker and rank broadly applicable types (`Event`, `Element`, `Array`,
`Promise`) above niche APIs. Avoid presenting the sampled declaring type as if it were inferred.

### P2: suggest useful type hints

Where resolution fails but one member strongly identifies a small set of types, offer a secondary
intention or completion tail such as `Add ^js/Event hint`. The existing mzg2 code already uses type
hints for PDF.js and Date objects, showing that this workflow fits the codebase.

## Recommended next implementation slice

Extend the merged-popup fixture with three mzg2-derived cases:

1. `js/document.cre` for typed-chain ordering;
2. `#(.stopPropagation %)` for callback inference and fallback quality;
3. `(Fuse. ...)` for npm default-constructor completion.

Those tests establish measurable behavior across the three most important completion paths before
changing ranking or inference.
