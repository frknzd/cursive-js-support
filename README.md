# Cursive JS Support

A companion plugin for [Cursive](https://cursive-ide.com/) that brings JavaScript interop awareness to ClojureScript files. It provides completion, documentation, and go-to-declaration for `js/`, `(.method receiver)`, `(.-prop receiver)`, and shadow-cljs `(:require ["pkg" :as alias :refer […]])` forms. Completion is driven by a bundled snapshot of TypeScript's `lib.dom.d.ts` plus any `.d.ts` files found in your project's `node_modules` (including shadow-cljs `:npm-deps` packages and workspace-style `packages/*`).

Version 1.0 also adds project-scoped indexing, shadow-cljs/`cljs.main`/Figwheel build discovery,
parameter info, confidence-aware inspections and quick fixes, modern npm subpath resolution,
and source-map debugging through IntelliJ's JavaScript debugger. Use the **ClojureScript JS**
tool window to inspect detected builds and indexing health.

## Using it

- Completion opens while typing `js/`, `(.method`, `(.-property`, npm aliases, require specs,
  and interop steps in `->`, `doto`, and `..` forms.
- Use Parameter Info (`⌘P` on macOS), Quick Documentation, and Go to Declaration (`⌘B`)
  directly on interop calls.
- Confident type, export, syntax, callability, arity, and deprecation problems appear as normal
  editor inspections. Invoke intention actions (`⌥Enter`) for safe corrections or an explicit
  `^js/Type` hint when one interface uniquely owns an otherwise ambiguous member.
- Add the **Browser** or **Node** factory under **ClojureScript JavaScript Debug**, choose a detected build profile, and
  place ordinary breakpoints in `.cljs` or `.cljc`. Source-map roots, launch commands, endpoints,
  and remote/local path mappings can be overridden in the run configuration.

## Requirements

- IntelliJ IDEA 2026.1 (build 261) or compatible
- The Cursive plugin installed in the IDE
- IntelliJ's JavaScript and JavaScript Debugger plugins (IntelliJ IDEA Ultimate)
- JDK 21 (for building)

## Build

```
./gradlew composedJar
```

The artifact lands in `build/libs/` (the composed jar bundles all plugin runtime dependencies — that's the file you install).

## Install

In the IDE: `Settings → Plugins → ⚙ → Install Plugin From Disk…` and select the composed jar from `build/libs/`. Restart when prompted.

## Develop

Run tests:

```
./gradlew test
```

Launch a sandbox IDE with the plugin loaded:

```
./gradlew runIde
```

See [TESTING.md](TESTING.md) for editing examples and copyable browser/Node debugging fixtures.

The bundled browser symbol index is committed to `src/main/resources/js/browser-symbols.json.gz`. To regenerate it from the TypeScript `.d.ts` files (requires Node.js on `PATH`):

```
./gradlew generateBrowserSymbolsIndex
```
