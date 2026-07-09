<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Cursive-js-support Changelog

## [Unreleased]

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
- Remove the `until-build` upper bound so the plugin stays available on all current and future IDE builds (including 2026.2 / 262).
- Regenerated the bundled browser symbol index with generic type arguments, interface type parameters, union aliases, and tag-prefixed JSDoc (`@deprecated` detection is now reliable).
