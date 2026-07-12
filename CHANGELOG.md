<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cursive-js-support Changelog

## [Unreleased]

## [1.0.0]

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
