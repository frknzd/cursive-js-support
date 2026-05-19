#!/usr/bin/env node
/**
 * Generates goog-symbols.json.gz from the google-closure-library npm package.
 *
 * Usage:
 *   npm install google-closure-library   # one-time
 *   node scripts/build-goog-index.mjs
 *
 * Output: src/main/resources/js/goog-symbols.json.gz
 *
 * Each goog namespace (e.g. "goog.string") becomes a "package" in the index with its
 * exported functions and properties as entries, matching the ParsedSymbols format:
 *   { "goog.string": { interfaces:{}, variables:{}, functions:{...} }, ... }
 *
 * Supports two Closure Library styles:
 *   1. Old-style: goog.provide('ns') + ns.fn = function(...) {}
 *   2. Modern:    goog.module('ns') + function fn(...) {} + exports = { fn, ... }
 */

import { createWriteStream, readdirSync, readFileSync } from 'fs';
import { createGzip } from 'zlib';
import { resolve, join, dirname } from 'path';
import { fileURLToPath } from 'url';
import { createRequire } from 'module';

const __dirname = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(__dirname, '..');
const outputPath = join(projectRoot, 'src/main/resources/js/goog-symbols.json.gz');

// Load the bundled typescript.js (same one used by dts-extractor-runner.js)
const tsPath = join(projectRoot, 'src/main/resources/js/typescript.js');
const require = createRequire(import.meta.url);
const ts = require(tsPath);

// Find google-closure-library
let closureLibRoot;
try {
    const pkg = require.resolve('google-closure-library/package.json');
    closureLibRoot = join(dirname(pkg), 'closure', 'goog');
} catch (e) {
    console.error('google-closure-library not found. Run: npm install google-closure-library');
    process.exit(1);
}

console.log(`Scanning ${closureLibRoot} ...`);

// ─── Helpers ────────────────────────────────────────────────────────────────

function walkJs(dir, out = []) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
            walkJs(full, out);
        } else if (entry.isFile() && entry.name.endsWith('.js') && !entry.name.endsWith('_test.js')) {
            out.push(full);
        }
    }
    return out;
}

function relPath(abs) {
    return abs.replace(projectRoot + '/', '');
}

function getLocation(node, filePath, sourceFile) {
    if (!node || !sourceFile) return null;
    try {
        return { filePath: relPath(filePath), offset: node.getStart(sourceFile, false) };
    } catch (e) {
        return { filePath: relPath(filePath), offset: 0 };
    }
}

function jsDocText(node, sourceFile) {
    if (!node || !sourceFile) return null;
    try {
        if (node.jsDoc && node.jsDoc.length > 0) {
            const parts = [];
            for (const jdoc of node.jsDoc) {
                const c = jdoc.comment;
                if (typeof c === 'string' && c.trim()) parts.push(c.trim());
                else if (Array.isArray(c)) {
                    const t = c.map(x => (x && x.text) ? x.text : '').join('').trim();
                    if (t) parts.push(t);
                }
            }
            const joined = parts.join('\n\n').trim();
            if (joined) return joined;
        }
        const tags = ts.getJSDocCommentsAndTags ? ts.getJSDocCommentsAndTags(node) : null;
        if (tags && tags.length > 0) {
            const parts = [];
            for (const tag of tags) {
                const c = tag.comment;
                if (typeof c === 'string' && c.trim()) parts.push(c.trim());
                else if (Array.isArray(c)) {
                    const t = c.map(x => (x && x.text) ? x.text : '').join('').trim();
                    if (t) parts.push(t);
                }
            }
            const joined = parts.join('\n\n').trim();
            if (joined) return joined;
        }
    } catch (e) {}
    return null;
}

function jsDocParams(node, sourceFile) {
    const params = [];
    try {
        const tags = ts.getAllJSDocTags
            ? ts.getAllJSDocTags(node, tag => tag.kind === ts.SyntaxKind.JSDocParameterTag)
            : [];
        for (const tag of tags) {
            const name = tag.name && tag.name.getText ? tag.name.getText(sourceFile) : (tag.name && tag.name.text) || '?';
            const type = tag.typeExpression && tag.typeExpression.type
                ? tag.typeExpression.type.getText ? tag.typeExpression.type.getText(sourceFile) : String(tag.typeExpression.type.kind)
                : 'any';
            const optional = !!tag.isBracketed;
            const rest = type.startsWith('...');
            params.push({ name, type: rest ? type.slice(3) : type, optional, rest });
        }
    } catch (e) {}
    return params;
}

function jsDocReturn(node, sourceFile) {
    try {
        const tags = ts.getAllJSDocTags
            ? ts.getAllJSDocTags(node, tag =>
                tag.kind === ts.SyntaxKind.JSDocReturnTag ||
                (tag.kind === ts.SyntaxKind.JSDocTag && tag.tagName && tag.tagName.text === 'return'))
            : [];
        for (const tag of tags) {
            if (tag.typeExpression && tag.typeExpression.type) {
                return tag.typeExpression.type.getText
                    ? tag.typeExpression.type.getText(sourceFile)
                    : 'any';
            }
        }
    } catch (e) {}
    return 'any';
}

function jsDocType(node, sourceFile) {
    try {
        const tags = ts.getAllJSDocTags
            ? ts.getAllJSDocTags(node, tag => tag.kind === ts.SyntaxKind.JSDocTypeTag)
            : [];
        for (const tag of tags) {
            if (tag.typeExpression && tag.typeExpression.type) {
                return tag.typeExpression.type.getText
                    ? tag.typeExpression.type.getText(sourceFile)
                    : 'any';
            }
        }
    } catch (e) {}
    return null;
}

// ─── Main extraction ─────────────────────────────────────────────────────────

const index = {}; // namespace -> ParsedSymbols

function ensureNamespace(ns) {
    if (!Object.prototype.hasOwnProperty.call(index, ns)) {
        index[ns] = {
            interfaces: Object.create(null),
            variables: Object.create(null),
            functions: Object.create(null),
        };
    }
    return index[ns];
}

function ensureArray(obj, key) {
    if (!Object.prototype.hasOwnProperty.call(obj, key) || !Array.isArray(obj[key])) {
        obj[key] = [];
    }
    return obj[key];
}

function dottedName(n) {
    if (!n) return null;
    if (n.kind === ts.SyntaxKind.Identifier) return n.text;
    if (n.kind === ts.SyntaxKind.PropertyAccessExpression) {
        const obj = dottedName(n.expression);
        if (!obj) return null;
        return obj + '.' + n.name.text;
    }
    return null;
}

// ─── Old-style: goog.provide + goog.ns.fn = function() {} ────────────────────

function extractGoogProvide(node) {
    if (node.kind !== ts.SyntaxKind.ExpressionStatement) return null;
    const expr = node.expression;
    if (!expr || expr.kind !== ts.SyntaxKind.CallExpression) return null;
    const calleeName = dottedName(expr.expression);
    if (calleeName !== 'goog.provide' && calleeName !== 'goog.module' && calleeName !== 'goog.declareModuleId') return null;
    const args = expr.arguments;
    if (!args || args.length === 0) return null;
    const first = args[0];
    if (first.kind !== ts.SyntaxKind.StringLiteral) return null;
    return first.text;
}

function processOldStyleAssignment(stmt, filePath, sourceFile) {
    if (stmt.kind !== ts.SyntaxKind.ExpressionStatement) return;
    const expr = stmt.expression;
    if (!expr || expr.kind !== ts.SyntaxKind.BinaryExpression) return;
    if (expr.operatorToken.kind !== ts.SyntaxKind.EqualsToken) return;

    const left = expr.left;
    const right = expr.right;
    if (!left || !right) return;
    if (left.kind !== ts.SyntaxKind.PropertyAccessExpression) return;

    const fullName = dottedName(left);
    if (!fullName || !fullName.startsWith('goog.')) return;
    const lastDot = fullName.lastIndexOf('.');
    if (lastDot <= 0) return;
    const namespace = fullName.substring(0, lastDot);
    const symbolName = fullName.substring(lastDot + 1);

    const location = getLocation(stmt, filePath, sourceFile);
    const doc = jsDocText(stmt, sourceFile);

    // Object literal: goog.events.EventType = { CLICK: 'click', ... }
    // If fullName is itself a declared namespace, populate it (not its parent)
    if (right.kind === ts.SyntaxKind.ObjectLiteralExpression) {
        const targetNs = Object.prototype.hasOwnProperty.call(index, fullName) ? fullName : namespace;
        const entry = ensureNamespace(targetNs);
        for (const prop of right.properties || []) {
            if (!prop.name || !prop.name.text) continue;
            const propName = prop.name.text;
            const propLocation = getLocation(prop, filePath, sourceFile);
            const propDoc = jsDocText(prop, sourceFile);
            const val = prop.initializer || prop;
            const isFn = val && (
                val.kind === ts.SyntaxKind.FunctionExpression ||
                val.kind === ts.SyntaxKind.ArrowFunction
            );
            if (isFn) {
                const params = jsDocParams(prop, sourceFile);
                const returns = jsDocReturn(prop, sourceFile);
                ensureArray(entry.functions, propName).push({ kind: 'function', params, returns, doc: propDoc, location: propLocation });
            } else {
                entry.variables[propName] = { type: jsDocType(prop, sourceFile) || 'any', doc: propDoc, location: propLocation };
            }
        }
        return;
    }

    const entry = ensureNamespace(namespace);
    const isFn = right.kind === ts.SyntaxKind.FunctionExpression ||
        right.kind === ts.SyntaxKind.ArrowFunction ||
        right.kind === ts.SyntaxKind.FunctionDeclaration;

    if (isFn) {
        const params = jsDocParams(stmt, sourceFile);
        const returns = jsDocReturn(stmt, sourceFile);
        ensureArray(entry.functions, symbolName).push({
            kind: 'function',
            params: params.length > 0 ? params : (right.parameters || []).map(p => ({
                name: p.name && p.name.text ? p.name.text : '?',
                type: 'any',
                optional: !!p.questionToken,
                rest: !!(p.dotDotDotToken),
            })),
            returns,
            doc,
            location,
        });
    } else {
        entry.variables[symbolName] = { type: jsDocType(stmt, sourceFile) || 'any', doc, location };
    }
}

// ─── Modern-style: goog.module + exports = { fn1, fn2 } ──────────────────────

function processModernStyle(namespace, statements, filePath, sourceFile) {
    // Index all top-level function declarations and variable/const/let declarations by name
    const topLevelFns = new Map();   // name -> FunctionDeclaration node
    const topLevelVars = new Map();  // name -> VariableDeclaration node

    for (const stmt of statements) {
        if (stmt.kind === ts.SyntaxKind.FunctionDeclaration && stmt.name && stmt.name.text) {
            topLevelFns.set(stmt.name.text, stmt);
        } else if (
            stmt.kind === ts.SyntaxKind.VariableStatement &&
            stmt.declarationList && stmt.declarationList.declarations
        ) {
            for (const decl of stmt.declarationList.declarations) {
                if (decl.name && decl.name.text) {
                    topLevelVars.set(decl.name.text, decl);
                }
            }
        }
    }

    const entry = ensureNamespace(namespace);

    function registerExportedName(exportedName, localName, overrideNode) {
        // Try function first
        const fnNode = topLevelFns.get(localName);
        if (fnNode) {
            const location = getLocation(overrideNode || fnNode, filePath, sourceFile);
            const doc = jsDocText(overrideNode || fnNode, sourceFile);
            const params = jsDocParams(overrideNode || fnNode, sourceFile);
            const returns = jsDocReturn(overrideNode || fnNode, sourceFile);
            ensureArray(entry.functions, exportedName).push({
                kind: 'function',
                params: params.length > 0 ? params : (fnNode.parameters || []).map(p => ({
                    name: p.name && p.name.text ? p.name.text : '?',
                    type: 'any',
                    optional: !!p.questionToken,
                    rest: !!(p.dotDotDotToken),
                })),
                returns,
                doc,
                location,
            });
            return;
        }

        // Try variable
        const varNode = topLevelVars.get(localName);
        if (varNode) {
            const location = getLocation(overrideNode || varNode, filePath, sourceFile);
            const doc = jsDocText(overrideNode || varNode, sourceFile);
            entry.variables[exportedName] = { type: jsDocType(overrideNode || varNode, sourceFile) || 'any', doc, location };
            return;
        }

        // Fallback: register as variable with unknown type (name was exported but no top-level decl found)
        const location = getLocation(overrideNode, filePath, sourceFile);
        entry.variables[exportedName] = { type: 'any', doc: null, location };
    }

    for (const stmt of statements) {
        if (stmt.kind !== ts.SyntaxKind.ExpressionStatement) continue;
        const expr = stmt.expression;
        if (!expr) continue;

        // Pattern: exports = { fn1, fn2, alias: fn3, ... }
        if (
            expr.kind === ts.SyntaxKind.BinaryExpression &&
            expr.operatorToken.kind === ts.SyntaxKind.EqualsToken
        ) {
            const left = expr.left;
            const right = expr.right;
            if (!left || !right) continue;

            // exports = { ... }
            if (
                left.kind === ts.SyntaxKind.Identifier &&
                left.text === 'exports' &&
                right.kind === ts.SyntaxKind.ObjectLiteralExpression
            ) {
                for (const prop of right.properties || []) {
                    if (!prop.name || !prop.name.text) continue;
                    const exportedName = prop.name.text;

                    // Shorthand: { format } => localName = exportedName
                    if (prop.kind === ts.SyntaxKind.ShorthandPropertyAssignment) {
                        registerExportedName(exportedName, exportedName, prop);
                        continue;
                    }

                    // Named: { format: formatFn } or { format: function(){} }
                    if (prop.kind === ts.SyntaxKind.PropertyAssignment && prop.initializer) {
                        const init = prop.initializer;
                        if (
                            init.kind === ts.SyntaxKind.FunctionExpression ||
                            init.kind === ts.SyntaxKind.ArrowFunction
                        ) {
                            // Inline function in exports literal
                            const location = getLocation(prop, filePath, sourceFile);
                            const doc = jsDocText(prop, sourceFile);
                            const params = jsDocParams(prop, sourceFile);
                            const returns = jsDocReturn(prop, sourceFile);
                            ensureArray(entry.functions, exportedName).push({
                                kind: 'function',
                                params: params.length > 0 ? params : (init.parameters || []).map(p => ({
                                    name: p.name && p.name.text ? p.name.text : '?',
                                    type: 'any',
                                    optional: !!p.questionToken,
                                    rest: !!(p.dotDotDotToken),
                                })),
                                returns,
                                doc,
                                location,
                            });
                        } else if (init.kind === ts.SyntaxKind.Identifier) {
                            registerExportedName(exportedName, init.text, prop);
                        } else {
                            // e.g. a constant expression
                            const location = getLocation(prop, filePath, sourceFile);
                            const doc = jsDocText(prop, sourceFile);
                            entry.variables[exportedName] = { type: 'any', doc, location };
                        }
                        continue;
                    }
                }
                continue;
            }

            // exports.fn = fn  or  exports.fn = function() {}
            if (
                left.kind === ts.SyntaxKind.PropertyAccessExpression &&
                left.expression && left.expression.kind === ts.SyntaxKind.Identifier &&
                left.expression.text === 'exports' &&
                left.name && left.name.text
            ) {
                const exportedName = left.name.text;
                const right2 = expr.right;
                if (right2.kind === ts.SyntaxKind.Identifier) {
                    registerExportedName(exportedName, right2.text, stmt);
                } else if (
                    right2.kind === ts.SyntaxKind.FunctionExpression ||
                    right2.kind === ts.SyntaxKind.ArrowFunction
                ) {
                    const location = getLocation(stmt, filePath, sourceFile);
                    const doc = jsDocText(stmt, sourceFile);
                    const params = jsDocParams(stmt, sourceFile);
                    const returns = jsDocReturn(stmt, sourceFile);
                    ensureArray(entry.functions, exportedName).push({
                        kind: 'function',
                        params: params.length > 0 ? params : (right2.parameters || []).map(p => ({
                            name: p.name && p.name.text ? p.name.text : '?',
                            type: 'any',
                            optional: !!p.questionToken,
                            rest: !!(p.dotDotDotToken),
                        })),
                        returns,
                        doc,
                        location,
                    });
                } else {
                    const location = getLocation(stmt, filePath, sourceFile);
                    const doc = jsDocText(stmt, sourceFile);
                    entry.variables[exportedName] = { type: jsDocType(stmt, sourceFile) || 'any', doc, location };
                }
            }
        }
    }
}

// ─── Per-file processing ──────────────────────────────────────────────────────

function processFile(filePath) {
    let source;
    try {
        source = readFileSync(filePath, 'utf8');
    } catch (e) {
        return;
    }

    let sourceFile;
    try {
        sourceFile = ts.createSourceFile(filePath, source, ts.ScriptTarget.Latest, true);
    } catch (e) {
        return;
    }

    const statements = sourceFile.statements;

    // Detect declared namespaces and module style
    let isGoogModule = false;
    const declaredNamespaces = [];

    for (const stmt of statements) {
        const ns = extractGoogProvide(stmt);
        if (ns) {
            declaredNamespaces.push(ns);
            ensureNamespace(ns);
            if (
                stmt.expression && stmt.expression.kind === ts.SyntaxKind.CallExpression &&
                dottedName(stmt.expression.expression) === 'goog.module'
            ) {
                isGoogModule = true;
            }
        }
    }

    if (isGoogModule) {
        // Modern style: collect function/var declarations and map exports
        for (const ns of declaredNamespaces) {
            processModernStyle(ns, statements, filePath, sourceFile);
        }
    } else {
        // Old style: direct goog.ns.fn = function() {} assignments
        for (const stmt of statements) {
            processOldStyleAssignment(stmt, filePath, sourceFile);
        }
    }
}

// ─── Scan all .js files ──────────────────────────────────────────────────────

const files = walkJs(closureLibRoot);
console.log(`Found ${files.length} .js files`);

let processed = 0;
for (const f of files) {
    processFile(f);
    processed++;
    if (processed % 200 === 0) process.stdout.write(`  ${processed}/${files.length}\r`);
}
console.log(`\nProcessed ${processed} files`);

// Remove empty namespaces (no functions and no variables)
for (const ns of Object.keys(index)) {
    const entry = index[ns];
    const fnCount = Object.keys(entry.functions).length;
    const varCount = Object.keys(entry.variables).length;
    if (fnCount === 0 && varCount === 0) {
        delete index[ns];
    }
}

const namespaceCount = Object.keys(index).length;
const totalSymbols = Object.values(index).reduce((n, e) =>
    n + Object.keys(e.functions).length + Object.keys(e.variables).length, 0);
console.log(`Namespaces: ${namespaceCount}, symbols: ${totalSymbols}`);

// Spot-check goog.object
if (index['goog.object']) {
    const fns = Object.keys(index['goog.object'].functions);
    const vars = Object.keys(index['goog.object'].variables);
    console.log(`goog.object: ${fns.length} functions [${fns.slice(0, 8).join(', ')}...], ${vars.length} vars`);
} else {
    console.log('goog.object: NOT FOUND in index');
}

// ─── Write output ────────────────────────────────────────────────────────────

// Convert Object.create(null) maps to plain objects for JSON serialization
function toPlainObject(obj) {
    if (obj === null || typeof obj !== 'object') return obj;
    if (Array.isArray(obj)) return obj.map(toPlainObject);
    const plain = {};
    for (const key of Object.keys(obj)) {
        plain[key] = toPlainObject(obj[key]);
    }
    return plain;
}

const json = JSON.stringify(toPlainObject(index));
const gzip = createGzip({ level: 9 });
const output = createWriteStream(outputPath);

output.on('finish', () => {
    console.log(`Written: ${outputPath}`);
});

gzip.pipe(output);
gzip.write(json);
gzip.end();
