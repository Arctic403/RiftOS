const SEMNEXIS_BOOTSTRAP_VERSION = '0.1.0-quickjs-bootstrap';
const SEMNEXIS_LANGUAGE = 'Semnexis';
const SEMNEXIS_GRAPH_SCHEMA = 'SEMNEXIS_PROGRAM_GRAPH_V0';
const SEMNEXIS_PLAN_SCHEMA = 'SEMNEXIS_EXECUTION_PLAN_V0';

const TokenKind = Object.freeze({
  End:'End', Identifier:'Identifier', Integer:'Integer',
  KwFn:'KwFn', KwLet:'KwLet', KwReturn:'KwReturn', KwWith:'KwWith',
  LParen:'LParen', RParen:'RParen', LBrace:'LBrace', RBrace:'RBrace',
  Arrow:'Arrow', Colon:'Colon', Comma:'Comma', Equal:'Equal',
  Semicolon:'Semicolon', Plus:'Plus', Minus:'Minus', Star:'Star', Slash:'Slash'
});

const NodeKind = Object.freeze({
  Module:'Module', Function:'Function', Type:'Type', Effect:'Effect',
  Capability:'Capability', Intrinsic:'Intrinsic', Region:'Region',
  Parameter:'Parameter', Local:'Local', Return:'Return', Constant:'Constant',
  NameRef:'NameRef', Binary:'Binary', Call:'Call'
});

function fail(message) { throw new Error(message); }
function isAlpha(c) { return /^[A-Za-z_]$/.test(c); }
function isAlphaNum(c) { return /^[A-Za-z0-9_]$/.test(c); }
function isDigit(c) { return /^[0-9]$/.test(c); }
function isSpace(c) { return c !== '' && /\s/.test(c); }

class Lexer {
  constructor(source) {
    this.source = String(source || '');
    this.pos = 0;
    this.line = 1;
    this.column = 1;
  }
  peek(offset) {
    const i = this.pos + (offset || 0);
    return i < this.source.length ? this.source[i] : '';
  }
  advance() {
    if (this.pos >= this.source.length) return '';
    const c = this.source[this.pos++];
    if (c === '\n') { this.line += 1; this.column = 1; }
    else this.column += 1;
    return c;
  }
  skipWhitespaceAndComments() {
    for (;;) {
      while (isSpace(this.peek())) this.advance();
      if (this.peek() === '/' && this.peek(1) === '/') {
        while (this.peek() !== '\n' && this.peek() !== '') this.advance();
        continue;
      }
      return;
    }
  }
  make(kind, start, line, column) {
    return {kind:kind, lexeme:this.source.slice(start, this.pos), line:line, column:column};
  }
  scan() {
    const out = [];
    for (;;) {
      this.skipWhitespaceAndComments();
      const start = this.pos, line = this.line, column = this.column;
      const c = this.peek();
      if (c === '') {
        out.push({kind:TokenKind.End, lexeme:'', line:this.line, column:this.column});
        return out;
      }
      if (isAlpha(c)) {
        this.advance();
        while (isAlphaNum(this.peek())) this.advance();
        const token = this.make(TokenKind.Identifier, start, line, column);
        if (token.lexeme === 'fn') token.kind = TokenKind.KwFn;
        else if (token.lexeme === 'let') token.kind = TokenKind.KwLet;
        else if (token.lexeme === 'return') token.kind = TokenKind.KwReturn;
        else if (token.lexeme === 'with') token.kind = TokenKind.KwWith;
        out.push(token);
        continue;
      }
      if (isDigit(c)) {
        this.advance();
        while (isDigit(this.peek())) this.advance();
        out.push(this.make(TokenKind.Integer, start, line, column));
        continue;
      }
      const one = {
        '(' : TokenKind.LParen, ')' : TokenKind.RParen,
        '{' : TokenKind.LBrace, '}' : TokenKind.RBrace,
        ':' : TokenKind.Colon, ',' : TokenKind.Comma,
        '=' : TokenKind.Equal, ';' : TokenKind.Semicolon,
        '+' : TokenKind.Plus, '*' : TokenKind.Star, '/' : TokenKind.Slash
      };
      if (one[c]) {
        this.advance();
        out.push(this.make(one[c], start, line, column));
        continue;
      }
      if (c === '-') {
        this.advance();
        if (this.peek() === '>') {
          this.advance();
          out.push(this.make(TokenKind.Arrow, start, line, column));
        } else out.push(this.make(TokenKind.Minus, start, line, column));
        continue;
      }
      fail('lexer: unexpected character at ' + line + ':' + column);
    }
  }
}

class Parser {
  constructor(tokens) { this.tokens = tokens; this.pos = 0; }
  peek(offset) {
    const i = this.pos + (offset || 0);
    return this.tokens[Math.min(i, this.tokens.length - 1)];
  }
  match(kind) {
    if (this.peek().kind !== kind) return false;
    this.pos += 1;
    return true;
  }
  expect(kind, message) {
    const token = this.peek();
    if (token.kind !== kind) {
      fail('parser: ' + message + ' at ' + token.line + ':' + token.column + ", found '" + token.lexeme + "'");
    }
    this.pos += 1;
    return token;
  }
  parseModule() {
    const functions = [];
    while (this.peek().kind !== TokenKind.End) functions.push(this.parseFunction());
    if (!functions.length) fail('parser: module must contain at least one function');
    return {functions:functions};
  }
  parseParameter() {
    const name = this.expect(TokenKind.Identifier, 'expected parameter name');
    this.expect(TokenKind.Colon, "expected ':' after parameter name");
    const type = this.expect(TokenKind.Identifier, 'expected parameter type');
    return {name:name.lexeme, type:type.lexeme};
  }
  parseLocal() {
    this.expect(TokenKind.KwLet, "expected 'let'");
    const name = this.expect(TokenKind.Identifier, 'expected local name');
    this.expect(TokenKind.Equal, "expected '=' after local name");
    const initializer = this.parseExpr();
    this.expect(TokenKind.Semicolon, "expected ';' after local binding");
    return {name:name.lexeme, initializer:initializer};
  }
  parseFunction() {
    this.expect(TokenKind.KwFn, "expected 'fn'");
    const name = this.expect(TokenKind.Identifier, 'expected function name');
    this.expect(TokenKind.LParen, "expected '('");
    const parameters = [];
    if (this.peek().kind !== TokenKind.RParen) {
      for (;;) {
        parameters.push(this.parseParameter());
        if (!this.match(TokenKind.Comma)) break;
      }
    }
    this.expect(TokenKind.RParen, "expected ')'");
    this.expect(TokenKind.Arrow, "expected '->'");
    const returnType = this.expect(TokenKind.Identifier, 'expected return type');
    const grantedCapabilities = [];
    if (this.match(TokenKind.KwWith)) {
      for (;;) {
        grantedCapabilities.push(this.expect(TokenKind.Identifier, "expected capability name after 'with'").lexeme);
        if (!this.match(TokenKind.Comma)) break;
      }
    }
    this.expect(TokenKind.LBrace, "expected '{'");
    const locals = [];
    while (this.peek().kind === TokenKind.KwLet) locals.push(this.parseLocal());
    this.expect(TokenKind.KwReturn, "expected 'return'");
    const returnExpr = this.parseExpr();
    this.expect(TokenKind.Semicolon, "expected ';' after return expression");
    this.expect(TokenKind.RBrace, "expected '}'");
    return {
      name:name.lexeme, parameters:parameters, returnType:returnType.lexeme,
      grantedCapabilities:grantedCapabilities, locals:locals, returnExpr:returnExpr
    };
  }
  parseExpr() { return this.parseAdditive(); }
  parseAdditive() {
    let left = this.parseMultiplicative();
    while (this.peek().kind === TokenKind.Plus || this.peek().kind === TokenKind.Minus) {
      const op = this.tokens[this.pos++].kind;
      const right = this.parseMultiplicative();
      left = {kind:'Binary', op:op, left:left, right:right};
    }
    return left;
  }
  parseMultiplicative() {
    let left = this.parsePrimary();
    while (this.peek().kind === TokenKind.Star || this.peek().kind === TokenKind.Slash) {
      const op = this.tokens[this.pos++].kind;
      const right = this.parsePrimary();
      left = {kind:'Binary', op:op, left:left, right:right};
    }
    return left;
  }
  parsePrimary() {
    if (this.match(TokenKind.LParen)) {
      const expression = this.parseExpr();
      this.expect(TokenKind.RParen, "expected ')' after expression");
      return expression;
    }
    if (this.peek().kind === TokenKind.Integer) {
      const token = this.tokens[this.pos++];
      const integer = Number(token.lexeme);
      if (!Number.isSafeInteger(integer)) fail('parser: integer literal is outside safe bootstrap range');
      return {kind:'Integer', integer:integer};
    }
    if (this.peek().kind === TokenKind.Identifier) {
      const token = this.tokens[this.pos++];
      if (this.match(TokenKind.LParen)) {
        const args = [];
        if (this.peek().kind !== TokenKind.RParen) {
          for (;;) {
            args.push(this.parseExpr());
            if (!this.match(TokenKind.Comma)) break;
          }
        }
        this.expect(TokenKind.RParen, "expected ')' after call arguments");
        return {kind:'Call', name:token.lexeme, arguments:args};
      }
      return {kind:'Name', name:token.lexeme};
    }
    const token = this.peek();
    fail('parser: expected expression at ' + token.line + ':' + token.column + ", found '" + token.lexeme + "'");
  }
}

function isExpressionKind(kind) {
  return kind === NodeKind.Constant || kind === NodeKind.NameRef ||
    kind === NodeKind.Binary || kind === NodeKind.Call;
}

class ProgramGraph {
  constructor() { this.nodes = []; this.edges = []; }
  addNode(kind, name) {
    const id = this.nodes.length;
    this.nodes.push({id:id, kind:kind, name:String(name), attributes:[]});
    return id;
  }
  addAttribute(node, key, value) {
    if (!this.nodes[node]) fail('graph: attribute target does not exist');
    this.nodes[node].attributes.push([String(key), String(value)]);
  }
  addEdge(from, to, relation) {
    if (!this.nodes[from] || !this.nodes[to]) fail('graph: edge endpoint does not exist');
    if (!relation) fail('graph: edge relation cannot be empty');
    this.edges.push({from:from, to:to, relation:String(relation)});
  }
  edgesFrom(from, relation) {
    return this.edges.filter(function(edge) {
      return edge.from === from && (relation == null || edge.relation === relation);
    });
  }
  singleEdgeTarget(from, relation) {
    const rows = this.edgesFrom(from, relation);
    if (rows.length !== 1) fail("graph: expected exactly one '" + relation + "' edge");
    return rows[0].to;
  }
  hasEdgeToKind(from, relation, kind) {
    return this.edges.some((edge) => edge.from === from && edge.relation === relation && this.nodes[edge.to].kind === kind);
  }
  countEdges(from, relation) { return this.edgesFrom(from, relation).length; }
  attribute(node, key) {
    const row = this.nodes[node].attributes.find(function(pair) { return pair[0] === key; });
    return row ? row[1] : null;
  }
  verify() {
    if (!this.nodes.length) fail('graph verify: graph is empty');
    for (let i = 0; i < this.nodes.length; i += 1) {
      if (this.nodes[i].id !== i) fail('graph verify: non-deterministic node identity');
    }

    const edgeKeys = new Set();
    for (const edge of this.edges) {
      if (!this.nodes[edge.from] || !this.nodes[edge.to]) fail('graph verify: dangling edge');
      if (!edge.relation) fail('graph verify: edge relation is empty');
      const edgeKey = edge.from + '|' + edge.to + '|' + edge.relation;
      if (edgeKeys.has(edgeKey)) fail('graph verify: duplicate semantic edge');
      edgeKeys.add(edgeKey);

      const sourceKind = this.nodes[edge.from].kind;
      const targetKind = this.nodes[edge.to].kind;
      const relation = edge.relation;
      if (relation === 'returns_type') {
        if ((sourceKind !== NodeKind.Function && sourceKind !== NodeKind.Intrinsic) || targetKind !== NodeKind.Type) fail('graph verify: invalid returns_type edge');
      } else if (relation === 'has_effect') {
        if ((sourceKind !== NodeKind.Function && sourceKind !== NodeKind.Intrinsic) || targetKind !== NodeKind.Effect) fail('graph verify: invalid has_effect edge');
      } else if (relation === 'requires_capability' || relation === 'grants_capability') {
        if ((sourceKind !== NodeKind.Function && sourceKind !== NodeKind.Intrinsic) || targetKind !== NodeKind.Capability) fail('graph verify: invalid capability edge');
      } else if (relation === 'executes_in') {
        if (sourceKind !== NodeKind.Function || targetKind !== NodeKind.Region) fail('graph verify: invalid executes_in edge');
      } else if (relation === 'calls') {
        if (sourceKind !== NodeKind.Call || (targetKind !== NodeKind.Function && targetKind !== NodeKind.Intrinsic)) fail('graph verify: invalid calls edge');
      } else if (relation === 'resolves_to') {
        if (sourceKind !== NodeKind.NameRef || (targetKind !== NodeKind.Parameter && targetKind !== NodeKind.Local)) fail('graph verify: invalid resolves_to edge');
      } else if (relation === 'initialized_by') {
        if (sourceKind !== NodeKind.Local || !isExpressionKind(targetKind)) fail('graph verify: invalid initialized_by edge');
      } else if (relation === 'returns_value') {
        if (sourceKind !== NodeKind.Return || !isExpressionKind(targetKind)) fail('graph verify: invalid returns_value edge');
      } else if (relation === 'contains_expr') {
        if (sourceKind !== NodeKind.Function || !isExpressionKind(targetKind)) fail('graph verify: invalid contains_expr edge');
      } else if (relation === 'lhs' || relation === 'rhs') {
        if (sourceKind !== NodeKind.Binary || !isExpressionKind(targetKind)) fail('graph verify: invalid binary operand edge');
      } else if (/^arg[0-9]+$/.test(relation)) {
        if (sourceKind !== NodeKind.Call || !isExpressionKind(targetKind)) fail('graph verify: invalid call argument edge');
      } else if (relation.indexOf('arg') === 0) {
        fail("graph verify: malformed call argument relation '" + relation + "'");
      } else if (relation === 'has_type') {
        if (targetKind !== NodeKind.Type) fail('graph verify: has_type must target Type');
      } else if (relation === 'contains') {
        const validModuleChild = sourceKind === NodeKind.Module && targetKind === NodeKind.Function;
        const validFunctionChild = sourceKind === NodeKind.Function &&
          (targetKind === NodeKind.Parameter || targetKind === NodeKind.Local || targetKind === NodeKind.Return);
        if (!validModuleChild && !validFunctionChild) fail('graph verify: invalid contains edge');
      } else fail("graph verify: unknown relation '" + relation + "'");
    }

    let moduleCount = 0;
    const functionNames = new Set();
    const expressionOwners = new Map();

    for (const edge of this.edges) {
      if (edge.relation === 'contains_expr') {
        const owners = expressionOwners.get(edge.to) || [];
        owners.push(edge.from);
        expressionOwners.set(edge.to, owners);
      }
    }

    for (const node of this.nodes) {
      if (node.kind === NodeKind.Module) moduleCount += 1;

      if (node.kind === NodeKind.Function) {
        if (functionNames.has(node.name)) fail("graph verify: duplicate function '" + node.name + "'");
        functionNames.add(node.name);
        if (!this.hasEdgeToKind(node.id, 'returns_type', NodeKind.Type) || this.countEdges(node.id, 'returns_type') !== 1) fail("graph verify: function '" + node.name + "' must have exactly one return type");
        if (this.countEdges(node.id, 'has_effect') !== 1) fail("graph verify: function '" + node.name + "' must have exactly one inferred effect");
        if (!this.hasEdgeToKind(node.id, 'executes_in', NodeKind.Region) || this.countEdges(node.id, 'executes_in') !== 1) fail("graph verify: function '" + node.name + "' must have exactly one execution region");
      }

      if (node.kind === NodeKind.Intrinsic) {
        if (this.countEdges(node.id, 'returns_type') !== 1 || this.countEdges(node.id, 'has_effect') !== 1) fail("graph verify: intrinsic '" + node.name + "' missing type/effect contract");
      }

      if (node.kind === NodeKind.Parameter) {
        if (!this.hasEdgeToKind(node.id, 'has_type', NodeKind.Type) || this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: parameter '" + node.name + "' must have exactly one type");
      }

      if (node.kind === NodeKind.Local) {
        if (!this.hasEdgeToKind(node.id, 'has_type', NodeKind.Type) || this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: local '" + node.name + "' must have exactly one inferred type");
        if (this.countEdges(node.id, 'initialized_by') !== 1) fail("graph verify: local '" + node.name + "' must have exactly one initializer");
      }

      if (node.kind === NodeKind.NameRef) {
        if (this.countEdges(node.id, 'resolves_to') !== 1 || this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: name reference '" + node.name + "' must resolve and type exactly once");
        const target = this.singleEdgeTarget(node.id, 'resolves_to');
        if (this.singleEdgeTarget(node.id, 'has_type') !== this.singleEdgeTarget(target, 'has_type')) fail("graph verify: name reference '" + node.name + "' type does not match resolved symbol");
      }

      if (node.kind === NodeKind.Call) {
        if (this.countEdges(node.id, 'calls') !== 1 || this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: call '" + node.name + "' must resolve and type exactly once");
        const target = this.singleEdgeTarget(node.id, 'calls');
        const targetNode = this.nodes[target];
        let arity = 0;
        if (targetNode.kind === NodeKind.Intrinsic) arity = Number(this.attribute(target, 'arity'));
        else arity = this.edgesFrom(target, 'contains').filter((edge) => this.nodes[edge.to].kind === NodeKind.Parameter).length;
        const argEdges = this.edgesFrom(node.id).filter((edge) => /^arg[0-9]+$/.test(edge.relation));
        if (argEdges.length !== arity) fail("graph verify: call '" + node.name + "' argument count does not match target");
        const indexes = argEdges.map((edge) => Number(edge.relation.slice(3))).sort((a,b) => a-b);
        for (let i = 0; i < indexes.length; i += 1) if (indexes[i] !== i) fail("graph verify: call '" + node.name + "' argument indexes are not contiguous");
      }

      if (node.kind === NodeKind.Return) {
        if (this.countEdges(node.id, 'returns_value') !== 1 || this.countEdges(node.id, 'has_type') !== 1) fail('graph verify: return node must have one value and type');
      }

      if (isExpressionKind(node.kind)) {
        const owners = expressionOwners.get(node.id) || [];
        if (owners.length !== 1) fail('graph verify: expression node must belong to exactly one function');
      }
    }

    if (moduleCount !== 1) fail('graph verify: graph must contain exactly one module');
  }
  dump() {
    const out = [SEMNEXIS_GRAPH_SCHEMA];
    for (const node of this.nodes) {
      let line = 'node ' + node.id + ' ' + node.kind + ' ' + node.name;
      for (const pair of node.attributes) line += ' ' + pair[0] + '=' + pair[1];
      out.push(line);
    }
    for (const edge of this.edges) out.push('edge ' + edge.from + ' -> ' + edge.to + ' ' + edge.relation);
    return out.join('\n') + '\n';
  }
}

class ExecutionPlan {
  constructor() { this.steps = []; }
  add(action, detail) { this.steps.push({index:this.steps.length, action:String(action), detail:String(detail)}); }
  dump() {
    const out = [SEMNEXIS_PLAN_SCHEMA];
    for (const step of this.steps) out.push('step ' + step.index + ' ' + step.action + ' ' + step.detail);
    return out.join('\n') + '\n';
  }
}

function requireTypeNode(types, type) {
  if (!types.has(type)) fail("type: unknown type '" + type + "'");
  return types.get(type);
}

function attachExpression(graph, ownerFunction, expressionNode) {
  graph.addEdge(ownerFunction, expressionNode, 'contains_expr');
}

function lowerExpr(expr, graph, ownerFunction, symbols, functions, intrinsics, types) {
  if (expr.kind === 'Integer') {
    const node = graph.addNode(NodeKind.Constant, 'integer');
    graph.addAttribute(node, 'value', String(expr.integer));
    graph.addEdge(node, requireTypeNode(types, 'i32'), 'has_type');
    attachExpression(graph, ownerFunction, node);
    return {node:node, type:'i32'};
  }

  if (expr.kind === 'Name') {
    const symbol = symbols.get(expr.name);
    if (!symbol) fail("resolve: unknown name '" + expr.name + "'");
    const node = graph.addNode(NodeKind.NameRef, expr.name);
    graph.addEdge(node, symbol.node, 'resolves_to');
    graph.addEdge(node, requireTypeNode(types, symbol.type), 'has_type');
    attachExpression(graph, ownerFunction, node);
    return {node:node, type:symbol.type};
  }

  if (expr.kind === 'Binary') {
    const left = lowerExpr(expr.left, graph, ownerFunction, symbols, functions, intrinsics, types);
    const right = lowerExpr(expr.right, graph, ownerFunction, symbols, functions, intrinsics, types);
    if (left.type !== 'i32' || right.type !== 'i32') fail('type: bootstrap arithmetic requires i32 operands');
    const node = graph.addNode(NodeKind.Binary, expr.op);
    graph.addAttribute(node, 'operation', expr.op);
    graph.addEdge(node, left.node, 'lhs');
    graph.addEdge(node, right.node, 'rhs');
    graph.addEdge(node, requireTypeNode(types, 'i32'), 'has_type');
    attachExpression(graph, ownerFunction, node);
    return {node:node, type:'i32'};
  }

  if (expr.kind !== 'Call') fail('compiler: unknown expression kind');

  if (functions.has(expr.name)) {
    const functionInfo = functions.get(expr.name);
    const target = functionInfo.decl;
    if (expr.arguments.length !== target.parameters.length) fail("call: '" + expr.name + "' expects " + target.parameters.length + ' argument(s), got ' + expr.arguments.length);
    const node = graph.addNode(NodeKind.Call, expr.name);
    graph.addEdge(node, functionInfo.node, 'calls');
    graph.addEdge(node, requireTypeNode(types, target.returnType), 'has_type');
    attachExpression(graph, ownerFunction, node);
    for (let i = 0; i < expr.arguments.length; i += 1) {
      const arg = lowerExpr(expr.arguments[i], graph, ownerFunction, symbols, functions, intrinsics, types);
      const expected = target.parameters[i].type;
      if (arg.type !== expected) fail('type: argument ' + i + " of '" + expr.name + "' requires " + expected + ', got ' + arg.type);
      graph.addEdge(node, arg.node, 'arg' + i);
    }
    return {node:node, type:target.returnType};
  }

  if (intrinsics.has(expr.name)) {
    const intrinsic = intrinsics.get(expr.name);
    if (expr.arguments.length !== intrinsic.arity) fail("call: intrinsic '" + expr.name + "' expects " + intrinsic.arity + ' argument(s), got ' + expr.arguments.length);
    const node = graph.addNode(NodeKind.Call, expr.name);
    graph.addAttribute(node, 'intrinsic', 'true');
    graph.addEdge(node, intrinsic.node, 'calls');
    graph.addEdge(node, requireTypeNode(types, intrinsic.returnType), 'has_type');
    attachExpression(graph, ownerFunction, node);
    for (let i = 0; i < expr.arguments.length; i += 1) {
      const arg = lowerExpr(expr.arguments[i], graph, ownerFunction, symbols, functions, intrinsics, types);
      graph.addEdge(node, arg.node, 'arg' + i);
    }
    return {node:node, type:intrinsic.returnType};
  }

  fail("resolve: unknown function or intrinsic '" + expr.name + "'");
}

function buildFunctionCallGraph(graph) {
  const calls = new Map();
  for (const node of graph.nodes) if (node.kind === NodeKind.Function) calls.set(node.id, []);
  for (const edge of graph.edges) {
    if (edge.relation !== 'contains_expr') continue;
    if (graph.nodes[edge.from].kind !== NodeKind.Function || graph.nodes[edge.to].kind !== NodeKind.Call) continue;
    calls.get(edge.from).push(graph.singleEdgeTarget(edge.to, 'calls'));
  }
  return calls;
}

function intrinsicHasTimeEffect(graph, intrinsicNode) {
  return graph.edges.some((edge) => edge.from === intrinsicNode && edge.relation === 'has_effect' &&
    graph.nodes[edge.to].kind === NodeKind.Effect && graph.nodes[edge.to].name === 'time');
}

function functionGrantsTime(graph, functionNode) {
  return graph.edges.some((edge) => edge.from === functionNode && edge.relation === 'grants_capability' &&
    graph.nodes[edge.to].kind === NodeKind.Capability && graph.nodes[edge.to].name === 'time');
}

function buildPlan(graph) {
  const plan = new ExecutionPlan();
  for (const fn of graph.nodes) {
    if (fn.kind !== NodeKind.Function) continue;
    plan.add('enter_function', fn.name);

    for (const edge of graph.edges) if (edge.from === fn.id && edge.relation === 'requires_capability') plan.add('receive_capability', graph.nodes[edge.to].name);
    for (const edge of graph.edges) if (edge.from === fn.id && edge.relation === 'grants_capability') plan.add('grant_capability', graph.nodes[edge.to].name);
    for (const edge of graph.edges) if (edge.from === fn.id && edge.relation === 'executes_in') plan.add('create_region', graph.nodes[edge.to].name);

    for (const edge of graph.edges) {
      if (edge.from !== fn.id || edge.relation !== 'contains') continue;
      const child = graph.nodes[edge.to];
      if (child.kind === NodeKind.Parameter) plan.add('bind_parameter', child.name);
    }
    for (const edge of graph.edges) {
      if (edge.from !== fn.id || edge.relation !== 'contains') continue;
      const child = graph.nodes[edge.to];
      if (child.kind === NodeKind.Local) plan.add('initialize_local', child.name);
    }
    for (const edge of graph.edges) {
      if (edge.from !== fn.id || edge.relation !== 'contains_expr') continue;
      const child = graph.nodes[edge.to];
      if (child.kind !== NodeKind.Call) continue;
      const target = graph.singleEdgeTarget(child.id, 'calls');
      plan.add(graph.nodes[target].kind === NodeKind.Intrinsic ? 'invoke_intrinsic' : 'invoke', graph.nodes[target].name);
    }

    plan.add('return', fn.name);
    for (const edge of graph.edges) if (edge.from === fn.id && edge.relation === 'executes_in') plan.add('destroy_region', graph.nodes[edge.to].name);
    plan.add('leave_function', fn.name);
  }
  return plan;
}

export function compileSemnexisV0(source) {
  const lexer = new Lexer(source);
  const parser = new Parser(lexer.scan());
  const module = parser.parseModule();
  const graph = new ProgramGraph();
  const moduleNode = graph.addNode(NodeKind.Module, 'root');

  const types = new Map();
  const i32Type = graph.addNode(NodeKind.Type, 'i32');
  graph.addAttribute(i32Type, 'width', '32');
  graph.addAttribute(i32Type, 'signed', 'true');
  types.set('i32', i32Type);

  const pureEffect = graph.addNode(NodeKind.Effect, 'pure');
  graph.addAttribute(pureEffect, 'observable_effects', 'none');
  const timeEffect = graph.addNode(NodeKind.Effect, 'time');
  graph.addAttribute(timeEffect, 'observable_effects', 'time');
  const timeCapability = graph.addNode(NodeKind.Capability, 'time');
  graph.addAttribute(timeCapability, 'authority', 'clock');

  const intrinsics = new Map();
  const clockIntrinsic = graph.addNode(NodeKind.Intrinsic, 'clock');
  graph.addAttribute(clockIntrinsic, 'arity', '0');
  graph.addEdge(clockIntrinsic, i32Type, 'returns_type');
  graph.addEdge(clockIntrinsic, timeEffect, 'has_effect');
  graph.addEdge(clockIntrinsic, timeCapability, 'requires_capability');
  intrinsics.set('clock', {node:clockIntrinsic, returnType:'i32', arity:0});

  const functions = new Map();
  for (const fn of module.functions) {
    if (functions.has(fn.name)) fail("resolve: duplicate function '" + fn.name + "'");
    if (intrinsics.has(fn.name)) fail("resolve: function name conflicts with intrinsic '" + fn.name + "'");
    requireTypeNode(types, fn.returnType);
    for (const parameter of fn.parameters) requireTypeNode(types, parameter.type);

    const fnNode = graph.addNode(NodeKind.Function, fn.name);
    graph.addAttribute(fnNode, 'symbol', 'fn::' + fn.name);
    graph.addEdge(moduleNode, fnNode, 'contains');
    graph.addEdge(fnNode, requireTypeNode(types, fn.returnType), 'returns_type');

    const seenGrants = new Set();
    for (const capability of fn.grantedCapabilities) {
      if (seenGrants.has(capability)) fail("capability: duplicate grant '" + capability + "' on function '" + fn.name + "'");
      seenGrants.add(capability);
      if (capability !== 'time') fail("capability: unknown capability '" + capability + "'");
      if (fn.name !== 'main') fail("capability: only entry function 'main' may grant capability '" + capability + "' in V0");
      graph.addEdge(fnNode, timeCapability, 'grants_capability');
    }

    const regionNode = graph.addNode(NodeKind.Region, fn.name + '.local');
    graph.addAttribute(regionNode, 'lifetime', 'function');
    graph.addAttribute(regionNode, 'escape', 'false');
    graph.addAttribute(regionNode, 'proof', 'no_reference_values_v0');
    graph.addEdge(fnNode, regionNode, 'executes_in');
    functions.set(fn.name, {decl:fn, node:fnNode, region:regionNode});
  }

  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    const symbols = new Map();

    for (const parameter of fn.parameters) {
      if (symbols.has(parameter.name)) fail("resolve: duplicate symbol '" + parameter.name + "' in function '" + fn.name + "'");
      const parameterNode = graph.addNode(NodeKind.Parameter, parameter.name);
      graph.addAttribute(parameterNode, 'symbol', 'fn::' + fn.name + '::param::' + parameter.name);
      graph.addEdge(fnNode, parameterNode, 'contains');
      graph.addEdge(parameterNode, requireTypeNode(types, parameter.type), 'has_type');
      symbols.set(parameter.name, {node:parameterNode, type:parameter.type});
    }

    for (const local of fn.locals) {
      if (symbols.has(local.name)) fail("resolve: duplicate symbol '" + local.name + "' in function '" + fn.name + "'");
      const initializer = lowerExpr(local.initializer, graph, fnNode, symbols, functions, intrinsics, types);
      const localNode = graph.addNode(NodeKind.Local, local.name);
      graph.addAttribute(localNode, 'symbol', 'fn::' + fn.name + '::local::' + local.name);
      graph.addAttribute(localNode, 'type_proof', 'initializer');
      graph.addEdge(fnNode, localNode, 'contains');
      graph.addEdge(localNode, requireTypeNode(types, initializer.type), 'has_type');
      graph.addEdge(localNode, initializer.node, 'initialized_by');
      symbols.set(local.name, {node:localNode, type:initializer.type});
    }

    const returnValue = lowerExpr(fn.returnExpr, graph, fnNode, symbols, functions, intrinsics, types);
    if (returnValue.type !== fn.returnType) fail("type: function '" + fn.name + "' returns " + fn.returnType + ' but expression is ' + returnValue.type);

    const returnNode = graph.addNode(NodeKind.Return, 'return');
    graph.addEdge(fnNode, returnNode, 'contains');
    graph.addEdge(returnNode, returnValue.node, 'returns_value');
    graph.addEdge(returnNode, requireTypeNode(types, fn.returnType), 'has_type');
  }

  const callGraph = buildFunctionCallGraph(graph);
  const directTime = new Map(), transitiveTime = new Map(), requiresTime = new Map();

  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    directTime.set(fnNode, false);
    transitiveTime.set(fnNode, false);
    requiresTime.set(fnNode, false);
    for (const target of callGraph.get(fnNode) || []) {
      if (graph.nodes[target].kind === NodeKind.Intrinsic && intrinsicHasTimeEffect(graph, target)) {
        directTime.set(fnNode, true);
        transitiveTime.set(fnNode, true);
        requiresTime.set(fnNode, true);
      }
    }
    if (functionGrantsTime(graph, fnNode)) requiresTime.set(fnNode, false);
  }

  let changed = true;
  while (changed) {
    changed = false;
    for (const fn of module.functions) {
      const fnNode = functions.get(fn.name).node;
      let nextEffect = directTime.get(fnNode);
      let nextRequirement = directTime.get(fnNode);
      for (const target of callGraph.get(fnNode) || []) {
        if (graph.nodes[target].kind !== NodeKind.Function) continue;
        nextEffect = nextEffect || transitiveTime.get(target);
        nextRequirement = nextRequirement || requiresTime.get(target);
      }
      if (functionGrantsTime(graph, fnNode)) nextRequirement = false;
      if (nextEffect !== transitiveTime.get(fnNode)) { transitiveTime.set(fnNode, nextEffect); changed = true; }
      if (nextRequirement !== requiresTime.get(fnNode)) { requiresTime.set(fnNode, nextRequirement); changed = true; }
    }
  }

  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    if (transitiveTime.get(fnNode)) {
      graph.addAttribute(fnNode, 'effect_proof', 'transitive_call_graph_v0');
      graph.addEdge(fnNode, timeEffect, 'has_effect');
    } else {
      graph.addAttribute(fnNode, 'effect_proof', 'closed_pure_graph_v0');
      graph.addEdge(fnNode, pureEffect, 'has_effect');
    }
    if (requiresTime.get(fnNode)) {
      graph.addAttribute(fnNode, 'capability_proof', 'transitive_requirement_v0');
      graph.addEdge(fnNode, timeCapability, 'requires_capability');
    } else if (transitiveTime.get(fnNode)) graph.addAttribute(fnNode, 'capability_proof', 'satisfied_by_grant_v0');
    else graph.addAttribute(fnNode, 'capability_proof', 'none_required_v0');
  }

  const main = functions.get('main');
  if (main && requiresTime.get(main.node)) fail("capability: entry function 'main' requires time; grant it with 'with time'");

  graph.verify();
  const plan = buildPlan(graph);
  return Object.freeze({
    schema:'semnexis-bootstrap-compile-result/1',
    language:SEMNEXIS_LANGUAGE,
    compiler:SEMNEXIS_BOOTSTRAP_VERSION,
    host:'quickjs',
    module:module,
    graph:graph,
    plan:plan,
    graphText:graph.dump(),
    planText:plan.dump()
  });
}

export function inspectSemnexisV0(source) {
  const result = compileSemnexisV0(source);
  return Object.freeze({
    schema:'semnexis-bootstrap-inspect/1',
    language:result.language,
    compiler:result.compiler,
    host:result.host,
    nodes:result.graph.nodes.length,
    edges:result.graph.edges.length,
    planSteps:result.plan.steps.length,
    functions:result.module.functions.map(function(fn) { return fn.name; })
  });
}

if (typeof globalThis !== 'undefined') {
  globalThis.SemnexisBootstrap = Object.freeze({
    version:SEMNEXIS_BOOTSTRAP_VERSION,
    language:SEMNEXIS_LANGUAGE,
    graphSchema:SEMNEXIS_GRAPH_SCHEMA,
    planSchema:SEMNEXIS_PLAN_SCHEMA,
    compile:compileSemnexisV0,
    inspect:inspectSemnexisV0
  });
}
