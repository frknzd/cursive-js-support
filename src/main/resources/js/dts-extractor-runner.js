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
                if (!node.isExportEquals) {
                    collectExportDefaultExpression(node, result, filename, sourceFile);
                }
                break;
            case ts.SyntaxKind.ModuleDeclaration:
                if (node.body && node.body.statements) visitStatements(node.body.statements, result, filename, sourceFile);
                break;
        }
    }
}

function collectExportDefaultDeclaration(node, result, filename, sourceFile) {
    var decl = node.declaration;
    if (decl) {
        if (decl.name && decl.name.text) {
            result.variables['default'] = {
                type: decl.name.text,
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
    if (expr.name && expr.name.text) {
        result.variables['default'] = {
            type: expr.name.text,
            doc: jsDocText(node, sourceFile),
            location: getLocation(expr.name, filename, sourceFile)
        };
    } else if (expr.kind === ts.SyntaxKind.Identifier) {
        result.variables['default'] = {
            type: typeName(expr),
            doc: jsDocText(node, sourceFile),
            location: getLocation(expr, filename, sourceFile)
        };
    } else {
        result.variables['default'] = {
            type: 'any',
            doc: jsDocText(node, sourceFile),
            location: getLocation(node, filename, sourceFile)
        };
    }
}

function collectExportDefaultExpression(node, result, filename, sourceFile) {
    var expr = node.expression;
    if (!expr) return;
    result.variables['default'] = {
        type: typeName(expr),
        doc: jsDocText(node, sourceFile),
        location: getLocation(expr, filename, sourceFile)
    };
}

function mergeInterface(node, result, filename, sourceFile) {
    var name = node.name.text;
    if (!Object.prototype.hasOwnProperty.call(result.interfaces, name)) {
        result.interfaces[name] = { location: getLocation(node, filename, sourceFile), extends: [], members: Object.create(null) };
    }
    var iface = result.interfaces[name];
    var bases = extractHeritageNames(node);
    for (var b = 0; b < bases.length; b++) {
        if (iface.extends.indexOf(bases[b]) < 0) iface.extends.push(bases[b]);
    }
    var members = iface.members;
    for (var i = 0; i < node.members.length; i++) {
        var m = node.members[i];
        if (!m.name || !m.name.text) continue;
        var memberName = m.name.text;
        if (!Object.prototype.hasOwnProperty.call(members, memberName)) members[memberName] = [];
        if (m.kind === ts.SyntaxKind.MethodSignature) {
            var docM = jsDocText(m, sourceFile);
            members[memberName].push({ kind: 'method', params: extractParams(m.parameters), returns: typeName(m.type), doc: docM, location: getLocation(m, filename, sourceFile) });
        } else if (m.kind === ts.SyntaxKind.PropertySignature) {
            var docP = jsDocText(m, sourceFile);
            members[memberName].push({ kind: 'property', type: typeName(m.type), optional: !!m.questionToken, doc: docP, location: getLocation(m, filename, sourceFile) });
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
            var typ = typeName(d.type);
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
    result.functions[name].push({ kind: 'method', params: extractParams(node.parameters), returns: typeName(node.type), doc: jsDocText(node, sourceFile), location: getLocation(node, filename, sourceFile) });
}

function getLocation(node, filename, sourceFile) {
    if (!node || !filename || !sourceFile) return null;
    var targetNode = node;
    if (node.name) {
        switch (node.kind) {
            case ts.SyntaxKind.MethodSignature:
            case ts.SyntaxKind.PropertySignature:
            case ts.SyntaxKind.FunctionDeclaration:
            case ts.SyntaxKind.MethodDeclaration:
            case ts.SyntaxKind.VariableDeclaration:
            case ts.SyntaxKind.InterfaceDeclaration:
            case ts.SyntaxKind.ClassDeclaration:
                targetNode = node.name;
                break;
            default:
                targetNode = node.name;
        }
    }
    var offset = 0;
    if (typeof targetNode.getStart === 'function') offset = targetNode.getStart(sourceFile);
    else if (typeof targetNode.pos === 'number') offset = targetNode.pos;
    else return null;
    return { filePath: filename, offset: offset };
}

function extractParams(params) {
    if (!params) return [];
    var out = [];
    for (var i = 0; i < params.length; i++) {
        var p = params[i];
        out.push({ name: p.name && p.name.text ? p.name.text : 'arg' + i, type: typeName(p.type), optional: !!(p.questionToken || p.initializer), rest: !!p.dotDotDotToken });
    }
    return out;
}

function typeName(node) {
    if (!node) return 'any';
    switch (node.kind) {
        case ts.SyntaxKind.StringKeyword: return 'string';
        case ts.SyntaxKind.NumberKeyword: return 'number';
        case ts.SyntaxKind.BooleanKeyword: return 'boolean';
        case ts.SyntaxKind.VoidKeyword: return 'void';
        case ts.SyntaxKind.AnyKeyword: return 'any';
        case ts.SyntaxKind.TypeReference: return node.typeName ? (node.typeName.text || 'unknown') : 'unknown';
        case ts.SyntaxKind.ArrayType: return typeName(node.elementType) + '[]';
        case ts.SyntaxKind.UnionType: return node.types ? node.types.map(typeName).join('|') : 'any';
        case ts.SyntaxKind.IntersectionType: return node.types ? node.types.map(typeName).join('&') : 'any';
        case ts.SyntaxKind.ParenthesizedType: return typeName(node.type);
        case ts.SyntaxKind.TypeLiteral: return 'object';
        case ts.SyntaxKind.FunctionType: return 'Function';
        case ts.SyntaxKind.TupleType: return 'any[]';
        case ts.SyntaxKind.ThisType: return 'this';
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
