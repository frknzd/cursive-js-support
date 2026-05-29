'use strict';

const ts = require('./typescript.js');
const readline = require('readline').createInterface({ input: process.stdin, crlfDelay: Infinity });

function extractSymbols(filesJson) {
    var files = JSON.parse(filesJson);
    var result = { interfaces: Object.create(null), variables: Object.create(null), functions: Object.create(null) };
    for (var filename in files) {
        if (!Object.prototype.hasOwnProperty.call(files, filename)) continue;
        var sourceFile = ts.createSourceFile(filename, files[filename], ts.ScriptTarget.Latest, true);
        visitStatements(sourceFile.statements, result, filename, sourceFile);
    }
    
    // Post-process: If we have a 'default' export that is 'any' or just an identifier,
    // and we also have many top-level functions/variables, libraries often intend those
    // to be the members of the default export (UMD style).
    if (result.variables['default'] && (result.variables['default'].type === 'any' || !result.interfaces[result.variables['default'].type])) {
        var topLevelCount = Object.keys(result.functions).length + Object.keys(result.variables).length;
        if (topLevelCount > 5) {
            var syntheticName = 'MODULE$Members';
            if (!result.interfaces[syntheticName]) {
                result.interfaces[syntheticName] = { location: null, extends: [], members: Object.create(null) };
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

function jsDocText(node, sourceFile) {
    if (!node || !sourceFile) return null;
    try {
        function pushCommentText(c) {
            if (typeof c === 'string' && c.trim()) parts.push(c.trim());
            else if (c && Array.isArray(c)) {
                var t = c.map(function (x) { return (x && x.text) ? x.text : ''; }).join('').trim();
                if (t) parts.push(t);
            }
        }
        var parts = [];
        var tags = typeof ts.getJSDocCommentsAndTags === 'function'
            ? ts.getJSDocCommentsAndTags(node)
            : null;
        if (tags && tags.length > 0) {
            for (var j = 0; j < tags.length; j++) {
                var d = tags[j];
                if (!d) continue;
                pushCommentText(d.comment);
            }
        }
        if (parts.length === 0 && node.jsDoc && node.jsDoc.length) {
            for (var k = 0; k < node.jsDoc.length; k++) {
                var jdoc = node.jsDoc[k];
                if (jdoc) pushCommentText(jdoc.comment);
            }
        }
        var joined = parts.join('\n\n').trim();
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
                        location: getLocation(node.name, filename, sourceFile)
                    };
                }
                break;
        }
    }
}

function collectExportDefaultDeclaration(node, result, filename, sourceFile) {
    var decl = node.declaration;
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
                location: getLocation(decl.name, filename, sourceFile)
            };
        } else {
            result.variables['default'] = {
                type: 'any',
                doc: jsDocText(node, sourceFile),
                location: getLocation(node, filename, sourceFile)
            };
        }
        return;
    }
    var expr = node.expression;
    if (!expr) return;
    result.variables['default'] = {
        type: typeName(expr, result),
        doc: jsDocText(node, sourceFile),
        location: getLocation(expr, filename, sourceFile)
    };
}

function collectExportAssignment(node, result, filename, sourceFile) {
    if (node.isExportEquals) {
        var type = typeName(node.expression, result);
        result.variables['default'] = {
            type: type,
            doc: jsDocText(node, sourceFile),
            location: getLocation(node.expression, filename, sourceFile)
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
        location: getLocation(expr, filename, sourceFile)
    };
}

function collectModuleDeclaration(node, result, filename, sourceFile) {
    if (node.name && node.name.text) {
        var name = node.name.text;
        // Ambient module: declare module "react-dom" { ... }
        if (node.name.kind === ts.SyntaxKind.StringLiteral) {
            if (node.body && node.body.statements) {
                visitStatements(node.body.statements, result, filename, sourceFile);
            }
            return;
        }

        var ifaceName = 'NAMESPACE$' + name;
        if (!Object.prototype.hasOwnProperty.call(result.interfaces, ifaceName)) {
            result.interfaces[ifaceName] = { location: getLocation(node, filename, sourceFile), extends: [], members: Object.create(null) };
        }
        var subResult = { interfaces: result.interfaces, variables: Object.create(null), functions: Object.create(null) };
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
        result.variables[name] = { type: ifaceName, doc: jsDocText(node, sourceFile), location: getLocation(node, filename, sourceFile) };
    }
}

function collectExportDeclaration(node, result, filename, sourceFile) {
    if (node.exportClause && node.exportClause.elements) {
        node.exportClause.elements.forEach(function (e) {
            var exportedName = e.name.text;
            var localName = e.propertyName ? e.propertyName.text : exportedName;
            var type = localName;
            if (result.variables[localName] && result.variables[localName].type.startsWith('TYPE$')) {
                type = result.variables[localName].type;
            }
            result.variables[exportedName] = {
                type: type,
                location: getLocation(e.name, filename, sourceFile)
            };
        });
    }
}

function mergeInterface(node, result, filename, sourceFile) {
    if (!node.name || !node.name.text) return;
    var name = node.name.text;
    var isClass = node.kind === ts.SyntaxKind.ClassDeclaration;

    if (!Object.prototype.hasOwnProperty.call(result.interfaces, name)) {
        result.interfaces[name] = { location: getLocation(node, filename, sourceFile), extends: [], members: Object.create(null) };
    }
    var iface = result.interfaces[name];
    var bases = extractHeritageNames(node);
    for (var b = 0; b < bases.length; b++) {
        if (iface.extends.indexOf(bases[b]) < 0) iface.extends.push(bases[b]);
    }

    var staticIfaceName = isClass ? 'TYPE$' + name + '$Static' : null;
    if (staticIfaceName && !Object.prototype.hasOwnProperty.call(result.interfaces, staticIfaceName)) {
        result.interfaces[staticIfaceName] = { location: getLocation(node, filename, sourceFile), extends: [], members: Object.create(null) };
        result.variables[name] = { type: staticIfaceName, location: getLocation(node.name, filename, sourceFile) };
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
                staticMembers['new'].push({
                    kind: 'method',
                    params: extractParams(m.parameters, result),
                    returns: name,
                    doc: jsDocText(m, sourceFile),
                    location: getLocation(m, filename, sourceFile)
                });
            }
            continue;
        }

        // ConstructSignature: `new(args): T` inside an interface (not a class constructor).
        if (m.kind === ts.SyntaxKind.ConstructSignature) {
            if (!Object.prototype.hasOwnProperty.call(targetMembers, 'new')) targetMembers['new'] = [];
            targetMembers['new'].push({
                kind: 'method',
                params: extractParams(m.parameters, result),
                returns: typeName(m.type, result),
                doc: jsDocText(m, sourceFile),
                location: getLocation(m, filename, sourceFile)
            });
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
    for (var i = 0; i < decls.length; i++) {
        var d = decls[i];
        if (d.name && d.name.text) {
            var name = d.name.text;
            var typ = typeName(d.type, result);
            if (d.type && d.type.kind === ts.SyntaxKind.TypeLiteral) {
                typ = 'TYPE$' + name;
                mergeInterface({ name: { text: typ }, members: d.type.members }, result, filename, sourceFile);
            }
            result.variables[name] = { type: typ, doc: jsDocText(d, sourceFile), location: getLocation(d, filename, sourceFile) };
        }
    }
}

function collectFunction(node, result, filename, sourceFile) {
    if (!node.name) return;
    var name = node.name.text;
    if (!Object.prototype.hasOwnProperty.call(result.functions, name)) result.functions[name] = [];
    result.functions[name].push({ kind: 'method', params: extractParams(node.parameters, result), returns: typeName(node.type, result), doc: jsDocText(node, sourceFile), location: getLocation(node, filename, sourceFile) });
}

function collectTypeAlias(node, result, filename, sourceFile) {
    if (!node.name || !node.name.text) return;
    var name = node.name.text;
    var type = node.type;
    if (!type) return;
    if (Object.prototype.hasOwnProperty.call(result.interfaces, name)) return; // real interface wins

    if (type.kind === ts.SyntaxKind.TypeLiteral) {
        // type Foo = { ... } — treat as an inline interface
        mergeInterface({ name: { text: name }, members: type.members, heritageClauses: null }, result, filename, sourceFile);
    } else if (type.kind === ts.SyntaxKind.TypeReference) {
        // type Foo = Bar — create a transparent alias interface that extends Bar
        var baseType = typeName(type, result);
        if (baseType && baseType !== 'any' && baseType !== 'unknown') {
            result.interfaces[name] = { location: getLocation(node, filename, sourceFile), extends: [baseType], members: Object.create(null) };
        }
    }
    // Union/intersection aliases (e.g. type BodyInit = Blob | string) are not modelled as interfaces
    // because we can't pick a single concrete receiver type for member resolution.
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

function typeName(node, result) {
    if (!node) return 'any';
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
            // QualifiedName (A.B) — use only the rightmost identifier so it matches our interface table keys.
            if (node.typeName.kind === ts.SyntaxKind.QualifiedName) {
                return (node.typeName.right && node.typeName.right.text) || 'unknown';
            }
            return node.typeName.text || 'unknown';
        case ts.SyntaxKind.ArrayType: return typeName(node.elementType, result) + '[]';
        case ts.SyntaxKind.UnionType: return node.types ? node.types.map(function(t) { return typeName(t, result); }).join('|') : 'any';
        case ts.SyntaxKind.IntersectionType: return node.types ? node.types.map(function(t) { return typeName(t, result); }).join('&') : 'any';
        case ts.SyntaxKind.ParenthesizedType: return typeName(node.type, result);
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
            if (node.operator === ts.SyntaxKind.ReadonlyKeyword) return typeName(node.type, result);
            return 'any';
        case ts.SyntaxKind.TypePredicate: // `x is T` — returns boolean at runtime; the narrowed type is a TypeScript-only concept
            return 'boolean';
        case ts.SyntaxKind.IndexedAccessType: return 'any'; // T[K]
        case ts.SyntaxKind.MappedType: return 'object';    // { [K in T]: V }
        case ts.SyntaxKind.Identifier:
            var name = node.text;
            if (result && result.variables[name]) {
                var vt = result.variables[name].type;
                if (vt.startsWith('TYPE$') || vt.startsWith('NAMESPACE$')) return vt;
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
