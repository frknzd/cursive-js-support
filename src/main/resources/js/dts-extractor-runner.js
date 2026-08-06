'use strict';

const ts = require('./typescript.js');
const readline = require('readline').createInterface({ input: process.stdin, crlfDelay: Infinity });

function extractSymbols(filesJson) {
    var payload = JSON.parse(filesJson);
    var files = payload.files || payload;
    var roots = payload.roots || Object.keys(files);
    var environment = payload.environment || 'common';
    var rootSet = Object.create(null);
    roots.forEach(function (root) { rootSet[root] = true; });
    var result = { interfaces: Object.create(null), variables: Object.create(null), functions: Object.create(null), aliases: Object.create(null), moduleExports: null, modules: Object.create(null), environment: environment, namePrefix: '', nameRemap: null };
    for (var filename in files) {
        if (!Object.prototype.hasOwnProperty.call(files, filename)) continue;
        var sourceFile = ts.createSourceFile(filename, files[filename], ts.ScriptTarget.Latest, true);
        var syntaxResult = rootSet[filename] ? result : {
            interfaces: result.interfaces,
            variables: Object.create(null),
            functions: Object.create(null),
            aliases: result.aliases
        };
        visitStatements(sourceFile.statements, syntaxResult, filename, sourceFile);
    }

    // For external modules, use the TypeScript checker to compute the public value surface.
    // A syntax walk cannot follow `export *`, alias chains, declaration merging, or `export =`.
    // Keeping this in the deterministic fallback makes its export semantics match JS/TS tooling.
    extractCheckedModuleExports(files, roots, result);
    
    // Post-process: If we have a 'default' export that is 'any' or just an identifier,
    // and we also have many top-level functions/variables, libraries often intend those
    // to be the members of the default export (UMD style).
    if (result.variables['default'] && (result.variables['default'].type === 'any' || !result.interfaces[result.variables['default'].type])) {
        var topLevelCount = Object.keys(result.functions).length + Object.keys(result.variables).length;
        if (topLevelCount > 5) {
            var syntheticName = 'MODULE$Members';
            if (!result.interfaces[syntheticName]) {
                result.interfaces[syntheticName] = { location: null, extends: [], members: Object.create(null), environment: result.environment || 'common' };
                for (var f in result.functions) result.interfaces[syntheticName].members[f] = result.functions[f];
                for (var v in result.variables) {
                    if (v === 'default') continue;
                    result.interfaces[syntheticName].members[v] = [{ kind: 'property', type: result.variables[v].type, doc: result.variables[v].doc, location: result.variables[v].location }];
                }
                result.variables['default'].type = syntheticName;
            }
        }
    }

    return JSON.stringify(result);
}

function extractCheckedModuleExports(files, roots, result) {
    if (roots.length === 0) return;
    var options = {
        allowJs: true,
        checkJs: true,
        module: ts.ModuleKind.NodeNext,
        moduleResolution: ts.ModuleResolutionKind.NodeNext,
        target: ts.ScriptTarget.Latest,
        skipLibCheck: true
    };
    var host = ts.createCompilerHost(options);
    var originalFileExists = host.fileExists.bind(host);
    var originalReadFile = host.readFile.bind(host);
    host.fileExists = function (name) {
        return Object.prototype.hasOwnProperty.call(files, name) || originalFileExists(name);
    };
    host.readFile = function (name) {
        return Object.prototype.hasOwnProperty.call(files, name) ? files[name] : originalReadFile(name);
    };
    host.getSourceFile = function (name, languageVersion) {
        var text = host.readFile(name);
        return text === undefined ? undefined : ts.createSourceFile(name, text, languageVersion, true);
    };

    var program = ts.createProgram(roots, options, host);
    var checker = program.getTypeChecker();
    var seen = Object.create(null);
    roots.forEach(function (root) {
        var source = program.getSourceFile(root);
        if (!source) return;
        var moduleSymbol = checker.getSymbolAtLocation(source);
        if (!moduleSymbol) return;
        if (result.moduleExports === null) result.moduleExports = [];

        var exportEquals = moduleSymbol.exports && moduleSymbol.exports.get('export=');
        var exportEqualsTarget = exportEquals && checkedTarget(exportEquals, checker);
        var exportEqualsDeclaration = exportEqualsTarget &&
            (exportEqualsTarget.valueDeclaration || (exportEqualsTarget.declarations && exportEqualsTarget.declarations[0]));
        if (exportEquals) {
            // The checker includes type-support declarations from a merged namespace in
            // getExportsOfModule (for example Lodash's private uniqueSymbol). Runtime named
            // access is exactly the property surface of the exported CommonJS value.
            addCheckedExport('default', exportEquals, checker, result, seen);
            var exportEqualsType = checker.getTypeOfSymbolAtLocation(exportEqualsTarget, exportEqualsDeclaration);
            checker.getPropertiesOfType(exportEqualsType).filter(function (property) {
                return belongsToExportedType(property, exportEqualsType, checker);
            }).forEach(function (property) {
                addCheckedExport(property.getName(), property, checker, result, seen, exportEqualsDeclaration);
            });
        } else {
            checker.getExportsOfModule(moduleSymbol).forEach(function (symbol) {
                addCheckedExport(symbol.getName(), symbol, checker, result, seen, exportEqualsDeclaration);
            });
        }
    });
    // Materialize structural aliases (mapped/conditional/indexed/type-literal shapes) so their
    // instantiated members remain available to the deterministic fallback index.
    Object.keys(files).forEach(function (filename) {
        var source = program.getSourceFile(filename);
        if (!source) return;
        source.statements.forEach(function (node) {
            if (node.kind !== ts.SyntaxKind.TypeAliasDeclaration || !node.name) return;
            var type = checker.getTypeAtLocation(node);
            var properties = checker.getPropertiesOfType(type);
            var calls = type.getCallSignatures ? type.getCallSignatures() : [];
            var constructs = type.getConstructSignatures ? type.getConstructSignatures() : [];
            if (properties.length > 0 || calls.length > 0 || constructs.length > 0) {
                addCheckedInterface(node.name.text, type, checker, result, node, 0);
            }
        });
    });
}

function belongsToExportedType(property, type, checker) {
    if (!property.parent || !property.parent.getName || !type.symbol) return true;
    var allowed = Object.create(null);
    function collect(current) {
        if (!current) return;
        if (current.symbol && current.symbol.getName) allowed[current.symbol.getName()] = true;
        if (current.getBaseTypes) (current.getBaseTypes() || []).forEach(collect);
        if (current.getConstructSignatures) current.getConstructSignatures().forEach(function (signature) {
            collect(signature.getReturnType());
        });
    }
    collect(type);
    return !!allowed[property.parent.getName()];
}

function checkedTarget(symbol, checker) {
    if (symbol.flags & ts.SymbolFlags.Alias) {
        try { return checker.getAliasedSymbol(symbol); } catch (e) { return symbol; }
    }
    return symbol;
}

function addCheckedExport(exportName, symbol, checker, result, seen, fallbackDeclaration) {
    if (isTypeOnlyExport(symbol)) return;
    var target = checkedTarget(symbol, checker);
    if (!(target.flags & ts.SymbolFlags.Value)) return;
    var declaration = target.valueDeclaration || (target.declarations && target.declarations[0]) || fallbackDeclaration;
    if (!declaration) return;
    var type = checker.getTypeOfSymbolAtLocation(target, declaration);
    // Declaration packages sometimes use an exported unique-symbol sentinel solely as a computed
    // type key. It is not a property of the runtime module value (notably @types/lodash).
    if (exportName === 'uniqueSymbol' && checker.typeToString(type, declaration) === 'unique symbol') return;
    if (!seen[exportName]) {
        seen[exportName] = true;
        result.moduleExports.push(exportName);
    }
    var calls = type.getCallSignatures ? type.getCallSignatures() : [];
    var constructs = type.getConstructSignatures ? type.getConstructSignatures() : [];
    var properties = checker.getPropertiesOfType(type);
    var display = checker.typeToString(type, declaration, ts.TypeFormatFlags.NoTruncation);
    var existing = result.variables[exportName];
    var existingNominal = existing &&
        (existing.type.indexOf('TYPE$') === 0 || existing.type.indexOf('NAMESPACE$') === 0);
    var synthetic = 'EXPORT$' + exportName.replace(/[^A-Za-z0-9_$]/g, '_');
    if (properties.length > 0 || constructs.length > 0) {
        var interfaceName = existingNominal ? existing.type : synthetic;
        addCheckedInterface(interfaceName, type, checker, result, declaration, 0);
        display = interfaceName;
    }

    var documentation = checkedDocumentation(target, checker);
    result.variables[exportName] = {
        type: existingNominal ? existing.type : (display || 'unknown'),
        doc: documentation || (existing && existing.doc) || null,
        location: checkedLocation(declaration),
        environment: result.environment || 'common'
    };
    if (calls.length > 0) {
        var checkedCalls = calls.map(function (signature) {
            return checkedSignature(signature, checker, declaration);
        });
        result.functions[exportName] = (result.functions[exportName] || []).concat(checkedCalls);
    }
}

function isTypeOnlyExport(symbol) {
    return !!(symbol.declarations && symbol.declarations.some(function (declaration) {
        return !!(declaration.isTypeOnly || (declaration.parent && declaration.parent.isTypeOnly));
    }));
}

function addCheckedInterface(name, type, checker, result, declaration, depth) {
    var iface = result.interfaces[name];
    if (!iface) {
        iface = { location: checkedLocation(declaration), extends: [], typeParams: [], members: Object.create(null), environment: result.environment || 'common' };
        result.interfaces[name] = iface;
    }

    var constructs = type.getConstructSignatures ? type.getConstructSignatures() : [];
    if (constructs.length > 0) {
        iface.members['new'] = constructs.map(function (signature) {
            return checkedSignature(signature, checker, declaration);
        });
    }
    var calls = type.getCallSignatures ? type.getCallSignatures() : [];
    if (calls.length > 0) {
        iface.members['$call'] = calls.map(function (signature) {
            return checkedSignature(signature, checker, declaration);
        });
    }
    checker.getPropertiesOfType(type).filter(function (property) {
        return property.getName().indexOf('__@') !== 0;
    }).forEach(function (property) {
        var memberDeclaration = property.valueDeclaration || (property.declarations && property.declarations[0]) || declaration;
        var memberType = checker.getTypeOfSymbolAtLocation(property, memberDeclaration);
        var signatures = memberType.getCallSignatures ? memberType.getCallSignatures() : [];
        if (signatures.length > 0) {
            iface.members[property.getName()] = signatures.map(function (signature) {
                return checkedSignature(signature, checker, memberDeclaration, property);
            });
            return;
        }
        var memberDisplay = checker.typeToString(memberType, memberDeclaration, ts.TypeFormatFlags.NoTruncation);
        var nestedProperties = checker.getPropertiesOfType(memberType);
        if (depth < 2 && nestedProperties.length > 0 && memberDisplay.length > 180) {
            var nestedName = name + '$' + property.getName().replace(/[^A-Za-z0-9_$]/g, '_');
            addCheckedInterface(nestedName, memberType, checker, result, memberDeclaration, depth + 1);
            memberDisplay = nestedName;
        }
        iface.members[property.getName()] = [{
            kind: 'property',
            params: [],
            returns: 'any',
            type: memberDisplay || 'unknown',
            optional: !!(property.flags & ts.SymbolFlags.Optional),
            doc: checkedDocumentation(property, checker),
            location: checkedLocation(memberDeclaration)
        }];
    });
}

function checkedSignature(signature, checker, fallbackDeclaration, owner) {
    var declaration = signature.getDeclaration ? signature.getDeclaration() : fallbackDeclaration;
    var params = signature.getParameters().map(function (parameter) {
        var parameterDeclaration = parameter.valueDeclaration || (parameter.declarations && parameter.declarations[0]) || declaration;
        var parameterType = checker.getTypeOfSymbolAtLocation(parameter, parameterDeclaration);
        return {
            name: parameter.getName(),
            type: checker.typeToString(parameterType, parameterDeclaration, ts.TypeFormatFlags.NoTruncation),
            optional: !!(parameter.flags & ts.SymbolFlags.Optional),
            rest: !!(parameterDeclaration && parameterDeclaration.dotDotDotToken)
        };
    });
    return {
        kind: 'method',
        params: params,
        returns: checker.typeToString(signature.getReturnType(), declaration, ts.TypeFormatFlags.NoTruncation),
        type: 'Function',
        optional: false,
        doc: checkedDocumentation(owner || signature, checker),
        location: checkedLocation(declaration || fallbackDeclaration)
    };
}

function checkedDocumentation(value, checker) {
    try {
        var parts = value.getDocumentationComment ? value.getDocumentationComment(checker) : [];
        return ts.displayPartsToString(parts) || null;
    } catch (e) {
        return null;
    }
}

function checkedLocation(declaration) {
    if (!declaration || !declaration.getSourceFile) return null;
    var source = declaration.getSourceFile();
    var target = declaration.name || declaration;
    return { filePath: source.fileName, offset: target.getStart(source) };
}

function jsDocText(node, sourceFile) {
    if (!node || !sourceFile) return null;
    try {
        var parts = [];
        function commentText(c) {
            if (typeof c === 'string') return c.trim();
            if (c && Array.isArray(c)) {
                return c.map(function (x) { return (x && x.text) ? x.text : ''; }).join('').trim();
            }
            return '';
        }
        function entityText(n) {
            if (!n) return '';
            if (n.text) return n.text;
            if (n.right && n.right.text) return n.right.text;
            return '';
        }
        // Tags keep their tag name (`@param name desc`, `@deprecated reason`, `@example …`) so
        // the Kotlin side can render structured sections and detect deprecation reliably.
        function pushTag(t) {
            if (!t) return;
            var tagName = t.tagName && t.tagName.text ? t.tagName.text : null;
            var text = commentText(t.comment);
            if (!tagName) { if (text) parts.push(text); return; }
            var line = '@' + tagName;
            if (tagName === 'param' && t.name) {
                var pn = entityText(t.name);
                if (pn) line += ' ' + pn;
            }
            if (text) line += ' ' + text;
            parts.push(line);
        }
        function pushEntry(d) {
            if (!d) return;
            if (d.tagName) { pushTag(d); return; }
            var text = commentText(d.comment);
            if (text) parts.push(text);
            if (d.tags && d.tags.length) {
                for (var i = 0; i < d.tags.length; i++) pushTag(d.tags[i]);
            }
        }
        var entries = typeof ts.getJSDocCommentsAndTags === 'function'
            ? ts.getJSDocCommentsAndTags(node)
            : null;
        if (entries && entries.length > 0) {
            for (var j = 0; j < entries.length; j++) pushEntry(entries[j]);
        }
        if (parts.length === 0 && node.jsDoc && node.jsDoc.length) {
            for (var k = 0; k < node.jsDoc.length; k++) pushEntry(node.jsDoc[k]);
        }
        // getJSDocCommentsAndTags can return both a JSDoc block and its individual tags.
        var seen = Object.create(null);
        var unique = [];
        for (var u = 0; u < parts.length; u++) {
            if (!seen[parts[u]]) { seen[parts[u]] = true; unique.push(parts[u]); }
        }
        var joined = unique.join('\n\n').trim();
        return joined || null;
    } catch (e) {
        return null;
    }
}

function visitStatements(statements, result, filename, sourceFile) {
    for (var i = 0; i < statements.length; i++) {
        var node = statements[i];
        switch (node.kind) {
            case ts.SyntaxKind.InterfaceDeclaration:
            case ts.SyntaxKind.ClassDeclaration:
                mergeInterface(node, result, filename, sourceFile);
                break;
            case ts.SyntaxKind.VariableStatement:
                collectVariables(node, result, filename, sourceFile);
                break;
            case ts.SyntaxKind.FunctionDeclaration:
                collectFunction(node, result, filename, sourceFile);
                break;
            case ts.SyntaxKind.ExportDefaultDeclaration:
                collectExportDefaultDeclaration(node, result, filename, sourceFile);
                break;
            case ts.SyntaxKind.ExportAssignment:
                collectExportAssignment(node, result, filename, sourceFile);
                break;
            case ts.SyntaxKind.ModuleDeclaration:
                collectModuleDeclaration(node, result, filename, sourceFile);
                break;
            case ts.SyntaxKind.ExportDeclaration:
                collectExportDeclaration(node, result, filename, sourceFile);
                break;
            case ts.SyntaxKind.TypeAliasDeclaration:
                collectTypeAlias(node, result, filename, sourceFile);
                break;
            case 271: // ts.SyntaxKind.NamespaceExportDeclaration
                if (node.name && node.name.text) {
                    result.variables[node.name.text] = {
                        type: 'default',
                        location: getLocation(node.name, filename, sourceFile),
                        environment: result.environment || 'common'
                    };
                }
                break;
        }
    }
}

function collectExportDefaultDeclaration(node, result, filename, sourceFile) {
    var decl = node.declaration;
    var env = result.environment || 'common';
    if (decl) {
        var name = decl.name ? decl.name.text : null;
        if (name) {
            var type = name;
            if (result.variables[name] && result.variables[name].type.startsWith('TYPE$')) {
                type = result.variables[name].type;
            }
            result.variables['default'] = {
                type: type,
                doc: jsDocText(node, sourceFile),
                location: getLocation(decl.name, filename, sourceFile),
                environment: env
            };
        } else {
            result.variables['default'] = {
                type: 'any',
                doc: jsDocText(node, sourceFile),
                location: getLocation(node, filename, sourceFile),
                environment: env
            };
        }
        return;
    }
    var expr = node.expression;
    if (!expr) return;
    result.variables['default'] = {
        type: typeName(expr, result),
        doc: jsDocText(node, sourceFile),
        location: getLocation(expr, filename, sourceFile),
        environment: env
    };
}

function collectExportAssignment(node, result, filename, sourceFile) {
    var env = result.environment || 'common';
    if (node.isExportEquals) {
        var type = typeName(node.expression, result);
        result.variables['default'] = {
            type: type,
            doc: jsDocText(node, sourceFile),
            location: getLocation(node.expression, filename, sourceFile),
            environment: env
        };
    } else {
        collectExportDefaultExpression(node, result, filename, sourceFile);
    }
}

function collectExportDefaultExpression(node, result, filename, sourceFile) {
    var expr = node.expression;
    if (!expr) return;
    result.variables['default'] = {
        type: typeName(expr, result),
        doc: jsDocText(node, sourceFile),
        location: getLocation(expr, filename, sourceFile),
        environment: result.environment || 'common'
    };
}

function collectModuleDeclaration(node, result, filename, sourceFile) {
    if (node.name && node.name.text) {
        var name = node.name.text;
        // Ambient external module: declare module "fs" { ... }
        // Collected into a scoped sub-result keyed under result.modules so the Kotlin
        // loader can register it as a "package" (loadNpmPackage). Interface names are
        // prefixed with MODULE$<module>$ to avoid colliding with the browser index.
        if (node.name.kind === ts.SyntaxKind.StringLiteral) {
            if (node.body && node.body.statements) {
                var safeName = name.replace(/[^A-Za-z0-9_$]/g, '_');
                var prefix = 'MODULE$' + safeName + '$';
                var scoped = {
                    interfaces: Object.create(null),
                    variables: Object.create(null),
                    functions: Object.create(null),
                    aliases: result.aliases,
                    moduleExports: null,
                    modules: Object.create(null),
                    environment: result.environment,
                    namePrefix: prefix,
                    nameRemap: Object.create(null)
                };
                visitStatements(node.body.statements, scoped, filename, sourceFile);
                if (!result.modules) result.modules = Object.create(null);
                result.modules[name] = scoped;
            }
            return;
        }

        var ifaceName = 'NAMESPACE$' + name;
        // `declare global { … }` augments the global scope rather than introducing a namespace
        // member interface: hoist its declarations straight into the enclosing result so that
        // globals like `process` (Node) are addressable as `js/process`.
        if (name === 'global') {
            if (node.body && node.body.statements) {
                visitStatements(node.body.statements, result, filename, sourceFile);
            }
            return;
        }
        if (!Object.prototype.hasOwnProperty.call(result.interfaces, ifaceName)) {
            result.interfaces[ifaceName] = { location: getLocation(node, filename, sourceFile), extends: [], members: Object.create(null), environment: result.environment };
        }
        var subResult = { interfaces: result.interfaces, variables: Object.create(null), functions: Object.create(null), aliases: result.aliases, environment: result.environment, namePrefix: '', nameRemap: null };
        if (node.body && node.body.statements) {
            visitStatements(node.body.statements, subResult, filename, sourceFile);
        }
        var iface = result.interfaces[ifaceName];
        for (var v in subResult.variables) {
            var val = subResult.variables[v];
            iface.members[v] = [{ kind: 'property', type: val.type, doc: val.doc, location: val.location }];
        }
        for (var f in subResult.functions) {
            iface.members[f] = subResult.functions[f];
        }
        result.variables[name] = { type: ifaceName, doc: jsDocText(node, sourceFile), location: getLocation(node, filename, sourceFile), environment: result.environment };
    }
}

function collectExportDeclaration(node, result, filename, sourceFile) {
    if (node.exportClause && node.exportClause.elements) {
        var env = result.environment || 'common';
        node.exportClause.elements.forEach(function (e) {
            var exportedName = e.name.text;
            var localName = e.propertyName ? e.propertyName.text : exportedName;
            var type = localName;
            if (result.variables[localName] && result.variables[localName].type.startsWith('TYPE$')) {
                type = result.variables[localName].type;
            }
            result.variables[exportedName] = {
                type: type,
                location: getLocation(e.name, filename, sourceFile),
                environment: env
            };
        });
    }
}

function mergeInterface(node, result, filename, sourceFile) {
    if (!node.name || !node.name.text) return;
    var originalName = node.name.text;
    var isClass = node.kind === ts.SyntaxKind.ClassDeclaration;
    // Ambient-module scoping: prefix interface names so they don't collide with the
    // browser index. The remap lets typeName() resolve bare references inside the
    // module body to the prefixed interface.
    var prefix = result.namePrefix || '';
    var remap = result.nameRemap;
    var name = prefix ? prefix + originalName : originalName;
    if (prefix && remap && !remap[originalName]) remap[originalName] = name;
    var env = result.environment || 'common';

    if (!Object.prototype.hasOwnProperty.call(result.interfaces, name)) {
        result.interfaces[name] = { location: getLocation(node, filename, sourceFile), extends: [], typeParams: [], members: Object.create(null), environment: env };
    }
    var iface = result.interfaces[name];
    if (node.typeParameters && node.typeParameters.length > 0 && (!iface.typeParams || iface.typeParams.length === 0)) {
        iface.typeParams = node.typeParameters.map(function (tp) {
            return tp.name && tp.name.text ? tp.name.text : 'T';
        });
    }
    var bases = extractHeritageNames(node);
    for (var b = 0; b < bases.length; b++) {
        var baseName = (remap && remap[bases[b]]) ? remap[bases[b]] : bases[b];
        if (iface.extends.indexOf(baseName) < 0) iface.extends.push(baseName);
    }

    var staticIfaceName = isClass ? 'TYPE$' + name + '$Static' : null;
    if (staticIfaceName && !Object.prototype.hasOwnProperty.call(result.interfaces, staticIfaceName)) {
        result.interfaces[staticIfaceName] = { location: getLocation(node, filename, sourceFile), extends: [], members: Object.create(null), environment: env };
        result.variables[originalName] = { type: staticIfaceName, location: getLocation(node.name, filename, sourceFile), environment: env };
    }

    var members = iface.members;
    var staticMembers = staticIfaceName ? result.interfaces[staticIfaceName].members : null;

    for (var i = 0; i < node.members.length; i++) {
        var m = node.members[i];
        var isStatic = false;
        if (m.modifiers) {
            for (var j = 0; j < m.modifiers.length; j++) {
                if (m.modifiers[j].kind === ts.SyntaxKind.StaticKeyword) isStatic = true;
            }
        }

        var targetMembers = (isStatic && staticMembers) ? staticMembers : members;
        
        if (m.kind === ts.SyntaxKind.Constructor) {
            if (staticMembers) {
                if (!Object.prototype.hasOwnProperty.call(staticMembers, 'new')) staticMembers['new'] = [];
                var constructorSignature = {
                    kind: 'method',
                    params: extractParams(m.parameters, result),
                    returns: name,
                    doc: jsDocText(m, sourceFile),
                    location: getLocation(m, filename, sourceFile)
                };
                staticMembers['new'].push(constructorSignature);
            }
            continue;
        }

        // ConstructSignature: `new(args): T` inside an interface (not a class constructor).
        if (m.kind === ts.SyntaxKind.ConstructSignature) {
            if (!Object.prototype.hasOwnProperty.call(targetMembers, 'new')) targetMembers['new'] = [];
            var constructSignature = {
                kind: 'method',
                params: extractParams(m.parameters, result),
                returns: typeName(m.type, result),
                doc: jsDocText(m, sourceFile),
                location: getLocation(m, filename, sourceFile)
            };
            targetMembers['new'].push(constructSignature);
            continue;
        }

        if (m.kind === ts.SyntaxKind.CallSignature) {
            if (!Object.prototype.hasOwnProperty.call(iface.members, '$call')) iface.members['$call'] = [];
            iface.members['$call'].push({
                kind: 'method',
                params: extractParams(m.parameters, result),
                returns: typeName(m.type, result),
                doc: jsDocText(m, sourceFile),
                location: getLocation(m, filename, sourceFile)
            });
            continue;
        }

        if (m.kind === ts.SyntaxKind.IndexSignature) {
            var indexKind = m.parameters && m.parameters[0] ? typeName(m.parameters[0].type, result) : 'string';
            targetMembers['$index:' + indexKind] = [{
                kind: 'property',
                type: typeName(m.type, result),
                location: getLocation(m, filename, sourceFile)
            }];
            continue;
        }

        if (!m.name || !m.name.text) continue;
        var memberName = m.name.text;
        if (!Object.prototype.hasOwnProperty.call(targetMembers, memberName)) targetMembers[memberName] = [];

        if (m.kind === ts.SyntaxKind.MethodSignature || m.kind === ts.SyntaxKind.MethodDeclaration) {
            targetMembers[memberName].push({
                kind: 'method',
                params: extractParams(m.parameters, result),
                returns: typeName(m.type, result),
                doc: jsDocText(m, sourceFile),
                location: getLocation(m, filename, sourceFile)
            });
        } else if (m.kind === ts.SyntaxKind.PropertySignature || m.kind === ts.SyntaxKind.PropertyDeclaration) {
            targetMembers[memberName].push({
                kind: 'property',
                type: typeName(m.type, result),
                optional: !!m.questionToken,
                doc: jsDocText(m, sourceFile),
                location: getLocation(m, filename, sourceFile)
            });
        } else if (m.kind === ts.SyntaxKind.GetAccessor) {
            // Getter-only or getter+setter pairs (e.g. `get location(): Location`).
            // Only the getter carries the readable type; setters are skipped.
            if (!Object.prototype.hasOwnProperty.call(targetMembers, memberName) || targetMembers[memberName].length === 0) {
                targetMembers[memberName].push({
                    kind: 'property',
                    type: typeName(m.type, result),
                    optional: false,
                    doc: jsDocText(m, sourceFile),
                    location: getLocation(m, filename, sourceFile)
                });
            }
        }
    }
}

function extractHeritageNames(node) {
    var out = [];
    if (!node.heritageClauses) return out;
    for (var i = 0; i < node.heritageClauses.length; i++) {
        var clause = node.heritageClauses[i];
        if (!clause.types) continue;
        for (var j = 0; j < clause.types.length; j++) {
            var t = clause.types[j];
            var expr = t.expression;
            var name = expr && (expr.text || (expr.name && expr.name.text));
            if (name) out.push(name);
        }
    }
    return out;
}

function collectVariables(node, result, filename, sourceFile) {
    var decls = node.declarationList.declarations;
    var env = result.environment || 'common';
    for (var i = 0; i < decls.length; i++) {
        var d = decls[i];
        if (d.name && d.name.text) {
            var name = d.name.text;
            var typ = typeName(d.type, result);
            if (d.type && d.type.kind === ts.SyntaxKind.TypeLiteral) {
                typ = 'TYPE$' + (result.namePrefix || '') + name;
                mergeInterface({ name: { text: typ }, members: d.type.members }, result, filename, sourceFile);
            }
            result.variables[name] = { type: typ, doc: jsDocText(d, sourceFile), location: getLocation(d, filename, sourceFile), environment: env };
        }
    }
}

function collectFunction(node, result, filename, sourceFile) {
    if (!node.name) return;
    var name = node.name.text;
    var env = result.environment || 'common';
    if (!Object.prototype.hasOwnProperty.call(result.functions, name)) result.functions[name] = [];
    result.functions[name].push({ kind: 'method', params: extractParams(node.parameters, result), returns: typeName(node.type, result), doc: jsDocText(node, sourceFile), location: getLocation(node, filename, sourceFile), environment: env });
}

function collectTypeAlias(node, result, filename, sourceFile) {
    if (!node.name || !node.name.text) return;
    var originalName = node.name.text;
    var type = node.type;
    if (!type) return;
    var prefix = result.namePrefix || '';
    var name = prefix ? prefix + originalName : originalName;
    if (result.nameRemap && prefix && !result.nameRemap[originalName]) result.nameRemap[originalName] = name;
    var env = result.environment || 'common';
    if (Object.prototype.hasOwnProperty.call(result.interfaces, name)) return; // real interface wins

    if (type.kind === ts.SyntaxKind.TypeLiteral) {
        // type Foo = { ... } — treat as an inline interface
        mergeInterface({ name: { text: originalName }, members: type.members, heritageClauses: null }, result, filename, sourceFile);
    } else if (type.kind === ts.SyntaxKind.TypeReference) {
        // type Foo = Bar — create a transparent alias interface that extends Bar
        var baseType = typeName(type, result);
        if (baseType && baseType !== 'any' && baseType !== 'unknown') {
            result.interfaces[name] = {
                location: getLocation(node, filename, sourceFile),
                extends: [baseType],
                typeParams: node.typeParameters ? node.typeParameters.map(function (tp) { return tp.name.text; }) : [],
                members: Object.create(null),
                environment: env
            };
        }
    }
    else if (type.kind === ts.SyntaxKind.FunctionType) {
        result.interfaces[name] = {
            location: getLocation(node, filename, sourceFile),
            extends: [],
            typeParams: node.typeParameters ? node.typeParameters.map(function (tp) { return tp.name.text; }) : [],
            members: {
                '$call': [{
                    kind: 'method',
                    params: extractParams(type.parameters, result),
                    returns: typeName(type.type, result),
                    location: getLocation(type, filename, sourceFile),
                    environment: env
                }]
            },
            environment: env
        };
    }
    else if (type.kind === ts.SyntaxKind.UnionType || type.kind === ts.SyntaxKind.IntersectionType) {
        // Union/intersection aliases (e.g. type BodyInit = Blob | string) can't become a single
        // interface; record the raw shape so the Kotlin side can expand them per-branch.
        if (result.aliases) {
            result.aliases[originalName] = typeName(type, result);
        }
    }
}

function getLocation(node, filename, sourceFile) {
    if (!node || !filename || !sourceFile) return null;
    var targetNode = node;
    if (node.name) {
        targetNode = node.name;
    }
    var offset = 0;
    if (typeof targetNode.getStart === 'function') offset = targetNode.getStart(sourceFile);
    else if (typeof targetNode.pos === 'number') offset = targetNode.pos;
    else return null;
    return { filePath: filename, offset: offset };
}

function extractParams(params, result) {
    if (!params) return [];
    var out = [];
    for (var i = 0; i < params.length; i++) {
        var p = params[i];
        out.push({ name: p.name && p.name.text ? p.name.text : 'arg' + i, type: typeName(p.type, result), optional: !!(p.questionToken || p.initializer), rest: !!p.dotDotDotToken });
    }
    return out;
}

function typeName(node, result, depth) {
    if (!node) return 'any';
    depth = depth || 0;
    switch (node.kind) {
        case ts.SyntaxKind.StringKeyword: return 'string';
        case ts.SyntaxKind.NumberKeyword: return 'number';
        case ts.SyntaxKind.BooleanKeyword: return 'boolean';
        case ts.SyntaxKind.VoidKeyword: return 'void';
        case ts.SyntaxKind.AnyKeyword: return 'any';
        case ts.SyntaxKind.NeverKeyword: return 'never';
        case ts.SyntaxKind.BigIntKeyword: return 'bigint';
        case ts.SyntaxKind.UnknownKeyword: return 'unknown';
        case ts.SyntaxKind.UndefinedKeyword: return 'undefined';
        case ts.SyntaxKind.NullKeyword: return 'null';
        case ts.SyntaxKind.ObjectKeyword: return 'object';
        case ts.SyntaxKind.SymbolKeyword: return 'symbol';
        case ts.SyntaxKind.TypeReference:
            if (!node.typeName) return 'unknown';
            var refName;
            // QualifiedName (A.B) — use only the rightmost identifier so it matches our interface table keys.
            if (node.typeName.kind === ts.SyntaxKind.QualifiedName) {
                refName = (node.typeName.right && node.typeName.right.text) || 'unknown';
            } else {
                refName = node.typeName.text || 'unknown';
            }
            // Ambient-module remap: resolve bare references inside the module body to the
            // prefixed interface so member resolution finds the scoped declaration.
            if (result && result.nameRemap && result.nameRemap[refName]) {
                refName = result.nameRemap[refName];
            }
            // Keep generic instantiations (`Promise<Response>`, `NodeListOf<E>`) — the Kotlin
            // side parses the string form back into a structured type. Depth-capped to keep
            // pathological nested generics bounded.
            if (node.typeArguments && node.typeArguments.length > 0 && depth < 3) {
                var args = node.typeArguments.map(function (t) { return typeName(t, result, depth + 1); });
                return refName + '<' + args.join(',') + '>';
            }
            return refName;
        case ts.SyntaxKind.ArrayType: return typeName(node.elementType, result, depth + 1) + '[]';
        case ts.SyntaxKind.UnionType: return node.types ? node.types.map(function(t) { return typeName(t, result, depth + 1); }).join('|') : 'any';
        case ts.SyntaxKind.IntersectionType: return node.types ? node.types.map(function(t) { return typeName(t, result, depth + 1); }).join('&') : 'any';
        case ts.SyntaxKind.ParenthesizedType: return typeName(node.type, result, depth);
        case ts.SyntaxKind.TypeLiteral: return 'object';
        case ts.SyntaxKind.FunctionType: return 'Function';
        case ts.SyntaxKind.ConstructorType: return 'Function';
        case ts.SyntaxKind.TupleType: return 'any[]';
        case ts.SyntaxKind.ThisType: return 'this';
        case ts.SyntaxKind.LiteralType:
            if (node.literal) {
                var lk = node.literal.kind;
                if (lk === ts.SyntaxKind.StringLiteral || lk === ts.SyntaxKind.NoSubstitutionTemplateLiteral) return 'string';
                if (lk === ts.SyntaxKind.NumericLiteral) return 'number';
                if (lk === ts.SyntaxKind.BigIntLiteral) return 'bigint';
                if (lk === ts.SyntaxKind.TrueKeyword || lk === ts.SyntaxKind.FalseKeyword) return 'boolean';
                if (lk === ts.SyntaxKind.NullKeyword) return 'null';
            }
            return 'any';
        case ts.SyntaxKind.TemplateLiteralType: return 'string';
        case ts.SyntaxKind.TypeOperator:
            // `readonly T` → unwrap to T; `keyof T` / `unique symbol` → any
            if (node.operator === ts.SyntaxKind.ReadonlyKeyword) return typeName(node.type, result, depth);
            return 'any';
        case ts.SyntaxKind.TypePredicate: // `x is T` — returns boolean at runtime; the narrowed type is a TypeScript-only concept
            return 'boolean';
        case ts.SyntaxKind.IndexedAccessType: return 'any'; // T[K]
        case ts.SyntaxKind.MappedType: return 'object';    // { [K in T]: V }
        case ts.SyntaxKind.Identifier:
            var name = node.text;
            if (result && result.nameRemap && result.nameRemap[name]) {
                return result.nameRemap[name];
            }
            if (result && result.variables[name]) {
                var vt = result.variables[name].type;
                if (vt.startsWith('TYPE$') || vt.startsWith('NAMESPACE$') || vt.startsWith('MODULE$')) return vt;
            }
            return name;
        default: return 'any';
    }
}

readline.on('line', (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try {
        const result = extractSymbols(trimmed);
        process.stdout.write(result + '\n');
    } catch (e) {
        process.stdout.write(JSON.stringify({ error: String(e) }) + '\n');
    }
});
