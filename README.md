# Cursive JS Support

A companion plugin for [Cursive](https://cursive-ide.com/) that brings JavaScript interop awareness to ClojureScript files. It provides completion, documentation, and go-to-declaration for `js/`, `(.method receiver)`, `(.-prop receiver)`, and shadow-cljs `(:require ["pkg" :as alias :refer […]])` forms — including Node.js, Bun, and Deno built-ins (`["fs" :as fs]`, `["node:fs/promises" :as fs-promises]`, `["bun:sqlite" :as sqlite]`) and relative JavaScript requires (`["./helper.js" :as helper]`). Completion is driven by the TypeScript 7.0.2 DOM and standard-library declarations (including the current ECMAScript 2026/`esnext` surface), bundled Node/Bun/Deno type indexes, and any `.d.ts` files found in your project's `node_modules` (including shadow-cljs `:npm-deps` packages and workspace-style `packages/*`).

Version 1.0 also adds project-scoped indexing, shadow-cljs/`cljs.main`/Figwheel build discovery,
parameter info, confidence-aware inspections and quick fixes, modern npm subpath resolution,
and source-map debugging through IntelliJ's JavaScript debugger. `js/*` globals are filtered
per file by the covering build's runtime target (browser vs Node vs Bun vs Deno), with
environment badges in hover and completion, so `js/process` only appears in Node-targeted
files and `js/document` only in browser-targeted files. Use the **ClojureScript JS**
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

- IntelliJ IDEA 2026.1 (build 261) or 2026.2 (build 262) — shipped as a separate artifact per line
- The Cursive plugin installed in the IDE
- IntelliJ's JavaScript and JavaScript Debugger plugins (IntelliJ IDEA Ultimate)
- JDK 21 (for building)

## Build

```
./gradlew buildPlugin -PplatformVersion=2026.1
./gradlew buildPlugin -PplatformVersion=2026.2
```

The version-specific, upload-ready JetBrains Marketplace ZIPs land in `build/distributions/`.

## Install

In the IDE: `Settings → Plugins → ⚙ → Install Plugin From Disk…` and select the **ZIP** from
`build/distributions/`. Restart when prompted.

Install the ZIP, never the jar from `build/libs/`. The composed jar holds only the plugin's own
classes and resources; the third-party libraries it links against (`edn-java`, Jackson) live
beside it in the ZIP's `lib/` directory. A jar installed on its own loads far enough to look
healthy and then dies with `NoClassDefFoundError: us/bpsm/edn/parser/Parsers` the moment it reads
a `shadow-cljs.edn`, leaving every interop symbol unresolved.

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

The bundled symbol indexes are committed to `src/main/resources/js/` (`browser-symbols.json.gz`, `node-symbols.json.gz`, `bun-symbols.json.gz`, `deno-symbols.json.gz`). To regenerate them from the TypeScript `.d.ts` sources (requires Node.js on `PATH`):

```
./gradlew generateBrowserSymbolsIndex generateNodeSymbolsIndex generateBunSymbolsIndex generateDenoSymbolsIndex
```

## Release

Releases go to the JetBrains Marketplace from `.github/workflows/release.yml`, which builds, tests,
and verifies both IDE lines and uploads each one. It triggers on `v*` tags, and
[bump-my-version](https://github.com/callowayproject/bump-my-version) (`brew install
bump-my-version`) produces those tags from `.bumpversion.toml`.

Write user-visible changes under `## [Unreleased]` in `CHANGELOG.md` as you go. To cut a release,
from a clean working tree:

```
bump-my-version bump patch     # or minor / major
git push --follow-tags
```

The bump updates `pluginVersion` in `gradle.properties` — the only place the version lives — opens a
`## [<new version>]` section in `CHANGELOG.md` holding whatever accumulated under `[Unreleased]`,
commits both, and tags `v<new version>`. Nothing leaves your machine until the push, and the push is
what starts the release. Add `--dry-run -v` to see the changes first.

The published artifacts append the IDE line (`1.3.1-261`, `1.3.1-262`) so both can coexist under one
plugin ID, and the workflow refuses to publish a version whose changelog section is empty.

Pre-releases are never produced automatically — cut one explicitly:

```
bump-my-version bump --new-version 1.4.0-beta.1
```

A qualifier routes the upload to the matching channel (`beta` here); a plain version goes to the
default stable channel. Marketplace users only see non-default channels if they add the channel's
repository URL to their IDE.

Running the workflow manually from the Actions tab builds and verifies without uploading; tick
**publish** to upload from a manual run. The npm interop corpus test self-skips on CI (its
`node_modules` isn't committed), so run `./gradlew test` locally with the corpus installed if you
touched npm resolution.

### One-time setup

Four repository secrets, under Settings → Secrets and variables → Actions:

| Secret | Where it comes from |
| --- | --- |
| `PUBLISH_TOKEN` | Marketplace profile → My Tokens. Shown once — copy it immediately. |
| `PRIVATE_KEY` | Contents of `private.pem` below, including the `-----BEGIN…` lines. |
| `PRIVATE_KEY_PASSWORD` | The passphrase entered while generating the key. |
| `CERTIFICATE_CHAIN` | Contents of `chain.crt` below. |

The Marketplace requires signed uploads. Generate the key pair once and keep both files out of the
repository:

```
openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
```
