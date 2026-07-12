'use strict';

const fs = require('fs');
const path = require('path');
// Use the compiler bundled by the plugin. TypeScript 7 intentionally exposes only version
// metadata from the package root and moves its compiler API to an asynchronous subpath.
const ts = require('../../src/main/resources/js/typescript.js');

const root = __dirname;
const manifest = require('./package.json');
const typeProviders = new Set(['@types/d3', '@types/express', '@types/lodash', '@types/react', '@types/ws']);
const packages = Object.keys(manifest.dependencies).filter(name => !typeProviders.has(name));
const requests = packages.concat([
  '@reduxjs/toolkit/query',
  'ajv/dist/2020',
  'date-fns/addDays',
  'lodash/fp',
  'react/jsx-runtime',
  'rxjs/operators',
  'vite/module-runner',
  'zod/v4',
]);
const options = {
  allowJs: true,
  module: ts.ModuleKind.NodeNext,
  moduleResolution: ts.ModuleResolutionKind.NodeNext,
  target: ts.ScriptTarget.ESNext,
  skipLibCheck: true,
};
const host = ts.createCompilerHost(options);
const resolved = new Map();
for (const name of requests) {
  const entries = new Set();
  for (const extension of ['mts', 'cts']) {
    const anchor = path.join(root, `__cursive_npm_audit__.${extension}`);
    const mode = extension === 'mts' ? ts.ModuleKind.ESNext : ts.ModuleKind.CommonJS;
    const result = ts.resolveModuleName(name, anchor, options, host, undefined, undefined, mode).resolvedModule;
    if (result) entries.add(result.resolvedFileName);
  }
  if (entries.size > 0) resolved.set(name, [...entries]);
}

const program = ts.createProgram([...new Set([...resolved.values()].flat())], options, host);
const checker = program.getTypeChecker();

function declarationInfo(symbol) {
  const declaration = symbol.valueDeclaration || (symbol.declarations && symbol.declarations[0]);
  if (!declaration) return null;
  return {
    file: path.relative(root, declaration.getSourceFile().fileName),
    offset: declaration.getStart(declaration.getSourceFile()),
  };
}

function describeSymbol(symbol) {
  const target = targetSymbol(symbol);
  const declaration = target.valueDeclaration || (target.declarations && target.declarations[0]);
  const type = checker.getTypeOfSymbolAtLocation(target, declaration || program.getSourceFiles()[0]);
  return {
    name: symbol.getName(),
    type: checker.typeToString(type, declaration, ts.TypeFormatFlags.NoTruncation),
    calls: type.getCallSignatures().length,
    constructs: type.getConstructSignatures().length,
    members: checker.getPropertiesOfType(type).map(member => member.getName())
      .filter(name => name !== 'default' && !name.startsWith('__@')).sort(),
    documentation: ts.displayPartsToString(target.getDocumentationComment(checker)),
    declaration: declarationInfo(target),
  };
}

function targetSymbol(symbol) {
  if (symbol.flags & ts.SymbolFlags.Alias) {
    try { return checker.getAliasedSymbol(symbol); } catch (_) { return symbol; }
  }
  return symbol;
}

function isTypeOnlyExport(symbol) {
  return !!(symbol.declarations && symbol.declarations.some(declaration =>
    declaration.isTypeOnly || (declaration.parent && declaration.parent.isTypeOnly)));
}

function belongsToExportedType(property, type) {
  if (!property.parent || !property.parent.getName || !type.symbol) return true;
  const allowed = new Set();
  function collect(current) {
    if (!current) return;
    if (current.symbol && current.symbol.getName) allowed.add(current.symbol.getName());
    if (current.getBaseTypes) (current.getBaseTypes() || []).forEach(collect);
    if (current.getConstructSignatures) current.getConstructSignatures().forEach(signature => collect(signature.getReturnType()));
  }
  collect(type);
  return allowed.has(property.parent.getName());
}

const report = {};
for (const name of requests) {
  const entries = resolved.get(name) || [];
  const byName = new Map();
  for (const entry of entries) {
    const source = program.getSourceFile(entry);
    const moduleSymbol = source && checker.getSymbolAtLocation(source);
    let moduleExports = moduleSymbol ? checker.getExportsOfModule(moduleSymbol) : [];
    const exportEquals = moduleSymbol && moduleSymbol.exports && moduleSymbol.exports.get('export=');
    if (exportEquals) {
      const target = targetSymbol(exportEquals);
      const declaration = target.valueDeclaration || (target.declarations && target.declarations[0]);
      const type = checker.getTypeOfSymbolAtLocation(target, declaration);
      moduleExports = checker.getPropertiesOfType(type).filter(property => belongsToExportedType(property, type));
      const syntheticDefault = Object.create(exportEquals);
      syntheticDefault.getName = () => 'default';
      moduleExports.push(syntheticDefault);
    }
    moduleExports
      .filter(symbol => !isTypeOnlyExport(symbol))
      .filter(symbol => (targetSymbol(symbol).flags & ts.SymbolFlags.Value) !== 0)
      .map(describeSymbol)
      .forEach(value => {
        const previous = byName.get(value.name);
        if (!previous || value.members.length + value.calls + value.constructs >
          previous.members.length + previous.calls + previous.constructs) byName.set(value.name, value);
      });
  }
  report[name] = { entries: entries.map(entry => path.relative(root, entry)), exports: [...byName.values()] };
}

process.stdout.write(JSON.stringify(report, null, 2) + '\n');
