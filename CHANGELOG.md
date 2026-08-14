<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cursive-js-support Changelog

## [Unreleased]

### Fixed
- Starting a Cursive REPL no longer freezes the IDE while its long Java classpath command is
  processed for source hyperlinks. Console location detection now scans in linear time instead of
  applying a quadratic whole-line regex while holding an IntelliJ read action.

## [1.6.0]

### Fixed
- Completion no longer performs npm workspace, `node_modules`, or IntelliJ JavaScript module-graph
  discovery while holding an editor read action. Package-name discovery now serves immutable
  snapshots and coalesces missing nested-project refreshes on the project coroutine scope, allowing
  pending write actions such as Cursive's REPL reparse to proceed without multi-second UI freezes.

### Changed
- Completion prefers the published declaration index and cooperatively checks for cancellation
  while emitting large result sets. Index construction now discovers dependencies once, rebuilds
  inherited-member samples once per published snapshot, and caches repeated type-graph and
  constructor queries.
- ClojureScript namespace-alias caching now replaces the previous revision for a file rather than
  retaining one cache entry per document modification.

## [1.5.2]

### Fixed
- Constructor syntax such as `(js/X.)` is no longer warned as non-constructable. TypeScript
  construct signatures do not reliably describe every value that ClojureScript can invoke with
  constructor syntax, so the inspection no longer attempts that analysis.

## [1.5.1]

### Fixed
- Indexing no longer wedges for the rest of the session when a stage throws an `Error` rather than
  an `Exception`. `JsIndexCoordinator` caught `Exception`, so a linkage error (most plausibly
  `NoClassDefFoundError: us/bpsm/edn/parser/Parsers` from a plugin installed as the bare composed
  jar) escaped the coroutine, left `claimLoad` held, and left every interop symbol unresolved
  behind an "Unhandled exception" report. Both the coordinator and `JsNpmDependencyWatcher` now
  catch `Throwable` (re-throwing `CancellationException`) and report the exception type alongside
  its message.
- Node, Bun, and Deno symbols no longer disappear mid-session. `JsNpmDependencyWatcher` rebuilt the
  index from the browser and goog sets only and then `publish`ed it over the live one, so the first
  `package.json` / `node_modules` / `shadow-cljs.edn` change after startup took `js/process` and the
  `fs` / `path` module exports away. Both the initial load and the re-index now go through
  `JsIndexLoader.loadAllBundled`.
- `path/join`, `path/dirname`, and the rest of the export-assignment built-ins resolve.
  `@types/node` declares these modules as `const path: path.PlatformPath; export = path`, so the
  module's whole API lives on the type of one object rather than in named exports; those members
  are now registered as exports of the bundled ambient modules. Packages resolved from
  `node_modules` are unaffected and keep the exports TypeScript reports for them.
- shadow-cljs builds are found in monorepo modules, not just at the project root. A project opened
  above `<module>/shadow-cljs.edn` previously discovered no build profiles at all, which left
  relative-require resolution without source paths and every file without a runtime target.
  Each config's paths resolve against its own directory, and its build ids are qualified by the
  module so two modules can both declare `:app`. Build-config discovery also stops descending into
  `node_modules`, `target`, `out`, and `.shadow-cljs`.
- A failure while reading npm typings no longer costs the bundled index. The browser, Node, Bun,
  and Deno symbols are published even when the npm stage fails, so `js/*` interop keeps working in
  a project whose `shadow-cljs.edn`, `node_modules`, or Node executable cannot be read.

### Changed
- README now documents installing the ZIP from `build/distributions/`. It previously said to
  install the composed jar from `build/libs/`, which holds none of the plugin's third-party
  libraries — they ship in the ZIP's `lib/` directory.

## [1.5.0]

### Added
- Node.js, Bun, and Deno built-in APIs are now indexed and offered alongside the browser
  globals. `["fs" :as fs]`, `["path" :as path]`, `["node:fs/promises" :as fs-promises]`, and
  `["bun:sqlite" :as sqlite]` resolve to real exports with hover docs, completion, and
  goto-declaration. Bundled `node-symbols.json.gz`, `bun-symbols.json.gz`, and
  `deno-symbols.json.gz` ship with the plugin; `node:` / `bun:` prefixes are normalized for
  index lookups while the original spelling is preserved for display.
- Relative JavaScript requires such as `["./capture_util.js" :as capture-util-impl]` now resolve.
  `RelativeModuleResolver` mirrors shadow-cljs: namespace-relative resolution against the build's
  `:source-paths` first, falling back to physical resolution relative to the requiring `.cljs`
  file. Completion lists matching `.js` / `.mjs` / `.cjs` files under the source roots, and
  goto-declaration jumps into the target file or its named exports via IntelliJ's JavaScript
  plugin (`JSResolveUtil.getExportedElements`).
- Environment-aware completion and hover. Each `.cljs` file is mapped to the runtime targets of
  the build profiles whose `:source-paths` cover it (`CljsProjectModel.runtimeTargetsForFile`),
  and `js/*` globals are filtered accordingly — `js/process` only appears in Node-targeted files,
  `js/document` only in browser-targeted files. Files covered by no profile (or by a mix) see
  every environment. Bun builds implicitly see Node built-ins.
- Environment badges in hover headers and completion tail text (Browser / Node.js / Bun / Deno /
  ECMAScript) so backend and browser APIs are visually distinguishable.
- `:source-paths` is now parsed from `shadow-cljs.edn`, `cljs.main` (`dev.cljs.edn`), and
  `figwheel-main` build configs to drive per-file runtime-target detection.

## [1.3.2]

## [1.3.1]

### Fixed
- Constructability is now decided by the global's companion type rather than by the extractor's
  `TYPE$` naming convention. Core ECMAScript declares its constructors as named companion
  interfaces (`declare var Error: ErrorConstructor`), so `(js/Error. "boom")` — along with `Date`,
  `Map`, `Set`, `Promise`, `Proxy`, `Array`, `Object`, `RegExp`, the typed arrays, and the rest of
  the 45 affected globals — was falsely reported as "not constructable". Globals whose companion
  declares no `new` signature (`js/NodeFilter`, `js/Symbol`, `js/BigInt`, `js/Iterator`) are
  correctly no longer treated as constructors.
- Quick Documentation and Parameter Info on `(js/Error. …)` now show the real `new` overloads,
  which previously resolved to nothing for every global constructor.

## [1.3.0]

### Added
- Recognize `js/globalThis`: completion, hover, goto-declaration, chain type inference
  (`js/globalThis.document.body`), and inspection suppression now treat it like `js/window`.
  TypeScript declares no `globalThis` variable (it is a keyword), so the browser index gains it
  synthetically, mirroring the `window` declaration it is typed against.

## [1.2.1]

### Changed
- Published the expanded Marketplace description covering the plugin's current completion,
  navigation, inspection, indexing, build-discovery, and source-map debugging features.

## [1.1.0]

### Changed
- Updated the bundled TypeScript standard-library declarations to TypeScript 7.0.2, including the
  latest `lib.esnext.*` surface for ECMAScript 2026, and regenerated the browser symbol index.
- Regenerated the Google Closure symbol index and bundled sources after confirming
  `google-closure-library` `20230802.0.0` remains the latest published release.
- Rewrote the Marketplace description to cover completion, navigation, inspections, npm and Closure
  indexing, build discovery, and source-map debugging.

## [1.0.2]

### Added
- IntelliJ IDEA 2026.2 (build 262) support via a target-platform matrix. Each supported IDE line
  ships as its own artifact against its version-locked Cursive dependency (Cursive `2026.2-262` for
  262, `2026.1-261` for 261); build a line with `./gradlew build -PplatformVersion=2026.1|2026.2`.

### Changed
- Bumped the Kotlin build toolchain to 2.4.10 to read IntelliJ 2026.2's (Kotlin 2.4) module metadata.

### Fixed
- Replaced the removed `com.intellij.modules.javascript` module dependency with a dependency on the
  `JavaScript` plugin, fixing an "requires com.intellij.modules.javascript to be installed" error on
  install under 2026.2.

## [1.0.0]

- Added condition-aware multi-entry npm resolution that merges `import`, `require`, `browser`,
  `node`, `react-server`, and custom condition surfaces, with deterministic JavaScript-source
  analysis when a package has no declarations.
- Replaced syntax-only npm export fallback with TypeScript module-checker semantics for barrel
  re-exports, declaration merging, callable namespaces, classes, constructors, ESM/CommonJS,
  `.d.cts`/`.d.mts`, public subpaths, and type-only export filtering.
- Added a pinned 20-package compatibility corpus covering 3,231 runtime exports and the completion,
  hover, parameter information, member, documentation, and navigation data derived from them.
- Fixed false "has no export" diagnostics for packages such as `diff` whose complete named API is
  declared in TypeScript while IntelliJ exposes only a partial CommonJS runtime export list.
- Assigned unique inspection identities to the Clojure and ClojureScript registrations.
- Consolidated npm semantics behind one project service, published immutable index snapshots,
  extracted the reusable type graph and Clojure PSI helpers, and unified browser/Node debug
  override and process lifecycle code. Added a fail-fast Detekt gate and data-driven browser
  navigation coverage while reducing total production and test source lines.
- Made IntelliJ's JavaScript/TypeScript module resolver and type evaluator authoritative for npm
  exports across completion, parameter info, inference, navigation, and inspections. Structured
  semantic results now retain overloads, construct signatures, and evaluated return members
  instead of collapsing complex TypeScript types through the fallback declaration parser.
- Corrected `:rename` local-binding semantics and preserved default-export identity when a module
  contains a single class or object export.
### Added
- Project-scoped, atomically published JavaScript indexes with a status tool window and safe reindexing.
- Shared build-profile discovery for shadow-cljs, `cljs.main`, and Figwheel Main.
- Structured IntelliJ JavaScript type results, deeper control-flow/destructuring/call inference, and parameter info.
- Confidence-aware inspections and quick fixes for globals, members, imports, arity, callability, syntax, and deprecations.
- Node-style npm subpath exports, conditional exports, `typesVersions`, workspace, pnpm, and nested-install support.
- Source Map v3 parsing, mapped console links, CLJS/CLJC JavaScript breakpoints, and a ClojureScript JavaScript Debug configuration.
- Browser, Node, and Figwheel testing fixtures plus an end-to-end usage guide.

### Changed
- IntelliJ JavaScript and JavaScript Debugger are now mandatory dependencies; version 1.0 targets IntelliJ IDEA Ultimate 2026.1.

## [0.6.2]
### Fixed
- Use the current editor-aware completion-confidence API instead of the deprecated `CompletionConfidence.shouldSkipAutopopup(PsiElement, PsiFile, Int)` overload.
- Limit this stable build to IntelliJ Platform 261, matching its mandatory stable Cursive dependency and preventing invalid compatibility checks against 262.

## [0.5.4]
### Added
- Chain-macro support beyond `..`: `->`, `->>`, `some->`, `some->>`, `doto`, `cond->`, `cond->>` now get type-aware completion, goto-declaration, hover, and false-positive suppression — including list steps like `(-> el (.setAttribute "x" "1"))` (`->>` correctly treats the threaded value as the *last* argument).
- List steps inside `..` forms (`(.. doc (createElement "div") -style)`) now resolve; previously any parenthesized step broke the chain everywhere.
- Generics in the type index: `js/fetch` flows to `Promise<Response>`, `querySelectorAll` chains continue through `NodeListOf<T>` with type-parameter substitution, and array types (`Element[]`) resolve through `Array` instead of dead-ending.
- Union/intersection type aliases (e.g. `BodyInit`) now expand to concrete interfaces for member resolution.
- Deeper type inference: `defn` return types (via the last body expression), calls to such fns, `do`/`let`/`when`-style body tails, and more binding heads (`if-some`, `loop`, `doseq`); recursion is cycle-guarded.
- Goto declaration: a confidently-typed receiver jumps straight to the declaration; ambiguous members show alternatives labelled `name in Interface (i/N)` ranked by inheritance distance.
- Much richer hover for functions: all overload signatures (primary bolded, capped at 8), an idiomatic CLJS call line (e.g. `(.createRange doc)`), a deprecation banner, `@param`/`@returns` descriptions merged into the tables, `@example` code blocks, `@throws`/`@see` rows, constructor (`(Foo. …)`) docs, and signatures for npm/goog function exports.
- Optional IntelliJ JavaScript type-evaluation bridge for npm exports (completion members, hover signatures, navigation to real JS declarations) behind a new settings toggle; degrades gracefully to the bundled index when the JS plugin is absent.

### Changed
- Regenerated the bundled browser symbol index with generic type arguments, interface type parameters, union aliases, and tag-prefixed JSDoc (`@deprecated` detection is now reliable).
