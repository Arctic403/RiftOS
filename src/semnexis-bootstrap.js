const SEMNEXIS_BOOTSTRAP_VERSION = '0.4.0-quickjs-bootstrap';
const SEMNEXIS_LANGUAGE = 'Semnexis';
const SEMNEXIS_GRAPH_SCHEMA = 'SEMNEXIS_PROGRAM_GRAPH_V0';
const SEMNEXIS_PLAN_SCHEMA = 'SEMNEXIS_EXECUTION_PLAN_V0';
const SEMNEXIS_NATIVE_IR_SCHEMA = 'SEMNEXIS_NATIVE_IR_V0';

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
      if (!Number.isSafeInteger(integer) || integer > 2147483647) fail('parser: integer literal is outside signed i32 range');
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


class NativeIRModule {
  constructor() {
    this.schema = SEMNEXIS_NATIVE_IR_SCHEMA;
    this.functions = [];
  }
  addFunction(fn) {
    this.functions.push(fn);
    return fn;
  }
  verify() {
    if (this.schema !== SEMNEXIS_NATIVE_IR_SCHEMA) fail('ir verify: schema mismatch');
    if (!this.functions.length) fail('ir verify: module has no functions');

    const byName = new Map();
    for (const fn of this.functions) {
      if (!fn || typeof fn !== 'object') fail('ir verify: malformed function');
      if (!fn.name || byName.has(fn.name)) fail("ir verify: duplicate or empty function '" + String(fn.name || '') + "'");
      byName.set(fn.name, fn);
      if (fn.returnType !== 'i32') fail("ir verify: unsupported return type on '" + fn.name + "'");
      if (fn.effect !== 'pure' && fn.effect !== 'time') fail("ir verify: unsupported effect on '" + fn.name + "'");
      if (!fn.region) fail("ir verify: function '" + fn.name + "' has no region");
      if (!Number.isInteger(fn.graphNode) || fn.graphNode < 0) fail("ir verify: function '" + fn.name + "' has invalid graph provenance");
      for (const cap of fn.requiresCapabilities) if (cap !== 'time') fail("ir verify: unsupported required capability '" + cap + "'");
      for (const cap of fn.grantsCapabilities) if (cap !== 'time') fail("ir verify: unsupported granted capability '" + cap + "'");
      if (fn.name !== 'main' && fn.grantsCapabilities.length) fail("ir verify: only main may grant ambient capability in V0");
    }

    for (const fn of this.functions) {
      const defined = new Set();
      const localNames = new Set();
      for (let i = 0; i < fn.parameters.length; i += 1) {
        const parameter = fn.parameters[i];
        if (parameter.index !== i || parameter.value !== '%arg' + i) fail("ir verify: function '" + fn.name + "' has non-canonical parameter identity");
        if (parameter.type !== 'i32') fail("ir verify: function '" + fn.name + "' has unsupported parameter type");
        if (!parameter.name) fail("ir verify: function '" + fn.name + "' has unnamed parameter");
        if (defined.has(parameter.value)) fail("ir verify: duplicate SSA parameter '" + parameter.value + "'");
        defined.add(parameter.value);
      }

      let nextValue = 0;
      let regionBegins = 0;
      let regionEnds = 0;
      let returns = 0;

      for (let index = 0; index < fn.instructions.length; index += 1) {
        const inst = fn.instructions[index];
        if (inst.index !== index) fail("ir verify: function '" + fn.name + "' has non-canonical instruction index");
        if (!Number.isInteger(inst.graphNode) || inst.graphNode < 0) fail("ir verify: instruction lacks graph provenance in '" + fn.name + "'");

        const args = Array.isArray(inst.args) ? inst.args : [];
        for (const arg of args) {
          if (typeof arg === 'string' && arg.startsWith('%') && !defined.has(arg)) {
            fail("ir verify: use-before-definition '" + arg + "' in function '" + fn.name + "'");
          }
        }

        if (inst.result != null) {
          const expected = '%v' + nextValue;
          if (inst.result !== expected) fail("ir verify: non-canonical SSA result '" + inst.result + "', expected '" + expected + "'");
          if (defined.has(inst.result)) fail("ir verify: duplicate SSA result '" + inst.result + "'");
          if (inst.type !== 'i32') fail("ir verify: SSA result '" + inst.result + "' is not i32");
          defined.add(inst.result);
          nextValue += 1;
        }

        switch (inst.op) {
          case 'region.begin':
            if (inst.result != null || inst.region !== fn.region || args.length) fail("ir verify: malformed region.begin in '" + fn.name + "'");
            if (index !== 0) fail("ir verify: region.begin must be first in '" + fn.name + "'");
            regionBegins += 1;
            break;
          case 'region.end':
            if (inst.result != null || inst.region !== fn.region || args.length) fail("ir verify: malformed region.end in '" + fn.name + "'");
            if (index !== fn.instructions.length - 2) fail("ir verify: region.end must precede return in '" + fn.name + "'");
            regionEnds += 1;
            break;
          case 'const.i32':
            if (args.length || !Number.isInteger(inst.value) || inst.value < -2147483648 || inst.value > 2147483647) {
              fail("ir verify: invalid i32 constant in '" + fn.name + "'");
            }
            break;
          case 'copy.i32':
            if (args.length !== 1 || inst.result == null) fail("ir verify: malformed copy.i32 in '" + fn.name + "'");
            break;
          case 'i32.add.checked':
          case 'i32.sub.checked':
          case 'i32.mul.checked':
          case 'i32.div.checked':
            if (args.length !== 2 || inst.result == null) fail("ir verify: malformed checked arithmetic in '" + fn.name + "'");
            break;
          case 'call':
            if (!inst.target || !byName.has(inst.target) || inst.result == null) fail("ir verify: unresolved call in '" + fn.name + "'");
            if (args.length !== byName.get(inst.target).parameters.length) fail("ir verify: call arity mismatch for '" + inst.target + "'");
            if (byName.get(inst.target).returnType !== inst.type) fail("ir verify: call result type mismatch for '" + inst.target + "'");
            break;
          case 'intrinsic.clock':
            if (args.length || inst.result == null || inst.effect !== 'time' || inst.capability !== 'time') {
              fail("ir verify: malformed clock intrinsic in '" + fn.name + "'");
            }
            break;
          case 'local.bind':
            if (inst.result != null || args.length !== 1 || !inst.name || localNames.has(inst.name)) {
              fail("ir verify: malformed local.bind in '" + fn.name + "'");
            }
            localNames.add(inst.name);
            break;
          case 'ret.i32':
            if (inst.result != null || args.length !== 1 || index !== fn.instructions.length - 1) {
              fail("ir verify: malformed ret.i32 in '" + fn.name + "'");
            }
            returns += 1;
            break;
          default:
            fail("ir verify: unsupported operation '" + inst.op + "'");
        }
      }

      if (regionBegins !== 1 || regionEnds !== 1 || returns !== 1) {
        fail("ir verify: function '" + fn.name + "' must have one region begin/end and one return");
      }
    }
  }
  dump() {
    const out = [SEMNEXIS_NATIVE_IR_SCHEMA];
    for (const fn of this.functions) {
      out.push(
        'function ' + fn.name +
        ' graph=' + fn.graphNode +
        ' return=' + fn.returnType +
        ' effect=' + fn.effect +
        ' region=' + fn.region +
        ' requires=' + (fn.requiresCapabilities.length ? fn.requiresCapabilities.join(',') : '-') +
        ' grants=' + (fn.grantsCapabilities.length ? fn.grantsCapabilities.join(',') : '-')
      );
      for (const parameter of fn.parameters) {
        out.push(
          'param ' + parameter.index + ' ' + parameter.value + ':' + parameter.type +
          ' name=' + parameter.name + ' graph=' + parameter.graphNode
        );
      }
      for (const inst of fn.instructions) out.push(dumpNativeIRInstruction(inst));
      out.push('endfunction ' + fn.name);
    }
    return out.join('\n') + '\n';
  }
}

function dumpNativeIRInstruction(inst) {
  let body = 'inst ' + inst.index + ' ';
  const result = inst.result != null ? inst.result + ':' + inst.type + ' = ' : '';
  if (inst.op === 'region.begin' || inst.op === 'region.end') {
    body += inst.op + ' region=' + inst.region;
  } else if (inst.op === 'const.i32') {
    body += result + inst.op + ' ' + inst.value;
  } else if (inst.op === 'copy.i32') {
    body += result + inst.op + ' ' + inst.args[0];
  } else if (inst.op === 'i32.add.checked' || inst.op === 'i32.sub.checked' ||
             inst.op === 'i32.mul.checked' || inst.op === 'i32.div.checked') {
    body += result + inst.op + ' ' + inst.args[0] + ' ' + inst.args[1];
  } else if (inst.op === 'call') {
    body += result + 'call @' + inst.target;
    for (const arg of inst.args) body += ' ' + arg;
  } else if (inst.op === 'intrinsic.clock') {
    body += result + 'intrinsic.clock effect=' + inst.effect + ' capability=' + inst.capability;
  } else if (inst.op === 'local.bind') {
    body += 'local.bind name=' + inst.name + ' value=' + inst.args[0];
  } else if (inst.op === 'ret.i32') {
    body += 'ret.i32 ' + inst.args[0];
  } else {
    body += inst.op;
  }
  return body + ' graph=' + inst.graphNode;
}

function graphTypeName(graph, node) {
  return graph.nodes[graph.singleEdgeTarget(node, 'has_type')].name;
}

function graphFunctionReturnType(graph, functionNode) {
  return graph.nodes[graph.singleEdgeTarget(functionNode, 'returns_type')].name;
}

function graphFunctionEffect(graph, functionNode) {
  return graph.nodes[graph.singleEdgeTarget(functionNode, 'has_effect')].name;
}

function graphFunctionCapabilities(graph, functionNode, relation) {
  return graph.edgesFrom(functionNode, relation).map((edge) => graph.nodes[edge.to].name);
}

function graphFunctionRegion(graph, functionNode) {
  return graph.nodes[graph.singleEdgeTarget(functionNode, 'executes_in')].name;
}

function makeIRFunction(graph, fnNode) {
  const parameters = graph.edgesFrom(fnNode.id, 'contains')
    .map((edge) => graph.nodes[edge.to])
    .filter((node) => node.kind === NodeKind.Parameter)
    .map((node, index) => ({
      index:index,
      value:'%arg' + index,
      type:graphTypeName(graph, node.id),
      name:node.name,
      graphNode:node.id
    }));
  return {
    name:fnNode.name,
    graphNode:fnNode.id,
    returnType:graphFunctionReturnType(graph, fnNode.id),
    effect:graphFunctionEffect(graph, fnNode.id),
    requiresCapabilities:graphFunctionCapabilities(graph, fnNode.id, 'requires_capability'),
    grantsCapabilities:graphFunctionCapabilities(graph, fnNode.id, 'grants_capability'),
    region:graphFunctionRegion(graph, fnNode.id),
    parameters:parameters,
    instructions:[]
  };
}

function emitIR(ctx, op, fields) {
  const inst = Object.assign({
    index:ctx.fn.instructions.length,
    op:op,
    graphNode:fields.graphNode
  }, fields);
  ctx.fn.instructions.push(inst);
  return inst;
}

function newIRValue(ctx) {
  const value = '%v' + ctx.nextValue;
  ctx.nextValue += 1;
  return value;
}

function lowerGraphExpressionToIR(graph, nodeId, ctx) {
  if (ctx.expressionValues.has(nodeId)) return ctx.expressionValues.get(nodeId);
  const node = graph.nodes[nodeId];
  if (!node || !isExpressionKind(node.kind)) fail('ir lower: expected expression graph node');

  let value;
  if (node.kind === NodeKind.Constant) {
    const raw = Number(graph.attribute(node.id, 'value'));
    value = newIRValue(ctx);
    emitIR(ctx, 'const.i32', {
      result:value,
      type:'i32',
      value:raw,
      args:[],
      graphNode:node.id
    });
  } else if (node.kind === NodeKind.NameRef) {
    const targetId = graph.singleEdgeTarget(node.id, 'resolves_to');
    const target = graph.nodes[targetId];
    let source;
    if (target.kind === NodeKind.Parameter) {
      source = ctx.parameterValues.get(targetId);
    } else if (target.kind === NodeKind.Local) {
      if (!ctx.localValues.has(targetId)) fail("ir lower: local '" + target.name + "' used before initialization");
      source = ctx.localValues.get(targetId);
    } else {
      fail('ir lower: NameRef resolved to unsupported symbol');
    }
    if (!source) fail("ir lower: unresolved SSA source for '" + node.name + "'");
    value = newIRValue(ctx);
    emitIR(ctx, 'copy.i32', {
      result:value,
      type:graphTypeName(graph, node.id),
      args:[source],
      graphNode:node.id
    });
  } else if (node.kind === NodeKind.Binary) {
    const left = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'lhs'), ctx);
    const right = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'rhs'), ctx);
    const operation = graph.attribute(node.id, 'operation');
    const opMap = {
      Plus:'i32.add.checked',
      Minus:'i32.sub.checked',
      Star:'i32.mul.checked',
      Slash:'i32.div.checked'
    };
    if (!opMap[operation]) fail("ir lower: unsupported binary operation '" + operation + "'");
    value = newIRValue(ctx);
    emitIR(ctx, opMap[operation], {
      result:value,
      type:graphTypeName(graph, node.id),
      args:[left, right],
      graphNode:node.id
    });
  } else if (node.kind === NodeKind.Call) {
    const argEdges = graph.edgesFrom(node.id)
      .filter((edge) => /^arg[0-9]+$/.test(edge.relation))
      .sort((a,b) => Number(a.relation.slice(3)) - Number(b.relation.slice(3)));
    const args = argEdges.map((edge) => lowerGraphExpressionToIR(graph, edge.to, ctx));
    const targetId = graph.singleEdgeTarget(node.id, 'calls');
    const target = graph.nodes[targetId];
    value = newIRValue(ctx);
    if (target.kind === NodeKind.Function) {
      emitIR(ctx, 'call', {
        result:value,
        type:graphTypeName(graph, node.id),
        target:target.name,
        args:args,
        graphNode:node.id
      });
    } else if (target.kind === NodeKind.Intrinsic && target.name === 'clock') {
      emitIR(ctx, 'intrinsic.clock', {
        result:value,
        type:graphTypeName(graph, node.id),
        args:[],
        effect:'time',
        capability:'time',
        graphNode:node.id
      });
    } else {
      fail("ir lower: unsupported call target '" + target.name + "'");
    }
  } else {
    fail("ir lower: unsupported expression kind '" + node.kind + "'");
  }

  ctx.expressionValues.set(nodeId, value);
  return value;
}

function buildNativeIR(graph) {
  graph.verify();
  const ir = new NativeIRModule();

  for (const fnNode of graph.nodes) {
    if (fnNode.kind !== NodeKind.Function) continue;
    const fn = ir.addFunction(makeIRFunction(graph, fnNode));
    const ctx = {
      fn:fn,
      nextValue:0,
      parameterValues:new Map(),
      localValues:new Map(),
      expressionValues:new Map()
    };
    for (const parameter of fn.parameters) ctx.parameterValues.set(parameter.graphNode, parameter.value);

    const regionNode = graph.singleEdgeTarget(fnNode.id, 'executes_in');
    emitIR(ctx, 'region.begin', {
      region:fn.region,
      args:[],
      graphNode:regionNode
    });

    const locals = graph.edgesFrom(fnNode.id, 'contains')
      .map((edge) => graph.nodes[edge.to])
      .filter((node) => node.kind === NodeKind.Local);

    for (const local of locals) {
      const initializerNode = graph.singleEdgeTarget(local.id, 'initialized_by');
      const initializerValue = lowerGraphExpressionToIR(graph, initializerNode, ctx);
      ctx.localValues.set(local.id, initializerValue);
      emitIR(ctx, 'local.bind', {
        name:local.name,
        args:[initializerValue],
        graphNode:local.id
      });
    }

    const returns = graph.edgesFrom(fnNode.id, 'contains')
      .map((edge) => graph.nodes[edge.to])
      .filter((node) => node.kind === NodeKind.Return);
    if (returns.length !== 1) fail("ir lower: function '" + fn.name + "' must contain exactly one return");
    const returnNode = returns[0];
    const returnExpr = graph.singleEdgeTarget(returnNode.id, 'returns_value');
    const returnValue = lowerGraphExpressionToIR(graph, returnExpr, ctx);

    emitIR(ctx, 'region.end', {
      region:fn.region,
      args:[],
      graphNode:regionNode
    });
    emitIR(ctx, 'ret.i32', {
      type:'i32',
      args:[returnValue],
      graphNode:returnNode.id
    });
  }

  ir.verify();
  return ir;
}


const NATIVE_IR_BINARY_MAGIC = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x30,0x00,0x00]);
const NATIVE_IR_BINARY_VERSION = 0;
const MAX_NATIVE_IR_BINARY_BYTES = 4 * 1024 * 1024;
const MAX_NATIVE_IR_FUNCTIONS = 1024;
const MAX_NATIVE_IR_PARAMETERS = 1024;
const MAX_NATIVE_IR_INSTRUCTIONS = 100000;
const MAX_NATIVE_IR_STRING_BYTES = 4096;

function utf8EncodeNativeIR(value) {
  const text = String(value);
  const out = [];
  for (let i = 0; i < text.length; i += 1) {
    let cp = text.charCodeAt(i);
    if (cp >= 0xD800 && cp <= 0xDBFF) {
      const next = i + 1 < text.length ? text.charCodeAt(i + 1) : -1;
      if (next >= 0xDC00 && next <= 0xDFFF) {
        cp = 0x10000 + ((cp - 0xD800) << 10) + (next - 0xDC00);
        i += 1;
      } else cp = 0xFFFD;
    } else if (cp >= 0xDC00 && cp <= 0xDFFF) cp = 0xFFFD;

    if (cp <= 0x7F) out.push(cp);
    else if (cp <= 0x7FF) {
      out.push(0xC0 | (cp >> 6));
      out.push(0x80 | (cp & 0x3F));
    } else if (cp <= 0xFFFF) {
      out.push(0xE0 | (cp >> 12));
      out.push(0x80 | ((cp >> 6) & 0x3F));
      out.push(0x80 | (cp & 0x3F));
    } else {
      out.push(0xF0 | (cp >> 18));
      out.push(0x80 | ((cp >> 12) & 0x3F));
      out.push(0x80 | ((cp >> 6) & 0x3F));
      out.push(0x80 | (cp & 0x3F));
    }
  }
  return Uint8Array.from(out);
}

function utf8DecodeNativeIR(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length;) {
    const a = bytes[i++];
    let cp;
    if (a <= 0x7F) cp = a;
    else if ((a & 0xE0) === 0xC0) {
      if (i >= bytes.length) fail('ir binary: truncated UTF-8');
      const b = bytes[i++];
      if ((b & 0xC0) !== 0x80) fail('ir binary: malformed UTF-8');
      cp = ((a & 0x1F) << 6) | (b & 0x3F);
      if (cp < 0x80) fail('ir binary: overlong UTF-8');
    } else if ((a & 0xF0) === 0xE0) {
      if (i + 1 >= bytes.length) fail('ir binary: truncated UTF-8');
      const b = bytes[i++], c = bytes[i++];
      if ((b & 0xC0) !== 0x80 || (c & 0xC0) !== 0x80) fail('ir binary: malformed UTF-8');
      cp = ((a & 0x0F) << 12) | ((b & 0x3F) << 6) | (c & 0x3F);
      if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) fail('ir binary: invalid UTF-8 scalar');
    } else if ((a & 0xF8) === 0xF0) {
      if (i + 2 >= bytes.length) fail('ir binary: truncated UTF-8');
      const b = bytes[i++], c = bytes[i++], d = bytes[i++];
      if ((b & 0xC0) !== 0x80 || (c & 0xC0) !== 0x80 || (d & 0xC0) !== 0x80) fail('ir binary: malformed UTF-8');
      cp = ((a & 0x07) << 18) | ((b & 0x3F) << 12) | ((c & 0x3F) << 6) | (d & 0x3F);
      if (cp < 0x10000 || cp > 0x10FFFF) fail('ir binary: invalid UTF-8 scalar');
    } else fail('ir binary: malformed UTF-8');

    if (cp <= 0xFFFF) out += String.fromCharCode(cp);
    else {
      cp -= 0x10000;
      out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
    }
  }
  return out;
}

class NativeIRBinaryWriter {
  constructor() { this.bytes = []; }
  u8(value) { this.bytes.push(value & 0xFF); }
  u16(value) {
    if (!Number.isInteger(value) || value < 0 || value > 0xFFFF) fail('ir binary: u16 out of range');
    this.u8(value); this.u8(value >>> 8);
  }
  u32(value) {
    if (!Number.isInteger(value) || value < 0 || value > 0xFFFFFFFF) fail('ir binary: u32 out of range');
    this.u8(value); this.u8(value >>> 8); this.u8(value >>> 16); this.u8(value >>> 24);
  }
  i32(value) {
    if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) fail('ir binary: i32 out of range');
    this.u32(value >>> 0);
  }
  string(value) {
    const bytes = utf8EncodeNativeIR(value);
    if (bytes.length > MAX_NATIVE_IR_STRING_BYTES || bytes.length > 0xFFFF) fail('ir binary: string too large');
    this.u16(bytes.length);
    for (const byte of bytes) this.u8(byte);
  }
  finish() {
    if (this.bytes.length > MAX_NATIVE_IR_BINARY_BYTES) fail('ir binary: payload too large');
    return Uint8Array.from(this.bytes);
  }
}

class NativeIRBinaryReader {
  constructor(bytes) {
    if (bytes instanceof Uint8Array) this.bytes = bytes;
    else if (Array.isArray(bytes)) this.bytes = Uint8Array.from(bytes);
    else if (bytes instanceof ArrayBuffer) this.bytes = new Uint8Array(bytes);
    else fail('ir binary: expected bytes');
    if (this.bytes.length > MAX_NATIVE_IR_BINARY_BYTES) fail('ir binary: payload too large');
    this.pos = 0;
  }
  need(count) {
    if (this.pos + count > this.bytes.length) fail('ir binary: truncated payload');
  }
  u8() { this.need(1); return this.bytes[this.pos++]; }
  u16() {
    this.need(2);
    const value = this.bytes[this.pos] | (this.bytes[this.pos + 1] << 8);
    this.pos += 2;
    return value >>> 0;
  }
  u32() {
    this.need(4);
    const value = (this.bytes[this.pos] |
      (this.bytes[this.pos + 1] << 8) |
      (this.bytes[this.pos + 2] << 16) |
      (this.bytes[this.pos + 3] << 24)) >>> 0;
    this.pos += 4;
    return value;
  }
  i32() { return this.u32() | 0; }
  string() {
    const length = this.u16();
    if (length > MAX_NATIVE_IR_STRING_BYTES) fail('ir binary: string too large');
    this.need(length);
    const value = utf8DecodeNativeIR(this.bytes.slice(this.pos, this.pos + length));
    this.pos += length;
    return value;
  }
  done() { return this.pos === this.bytes.length; }
}

const NATIVE_IR_OPCODE = Object.freeze({
  'region.begin':1,
  'region.end':2,
  'const.i32':3,
  'copy.i32':4,
  'i32.add.checked':5,
  'i32.sub.checked':6,
  'i32.mul.checked':7,
  'i32.div.checked':8,
  'call':9,
  'intrinsic.clock':10,
  'local.bind':11,
  'ret.i32':12
});

const NATIVE_IR_OPCODE_NAME = Object.freeze(Object.keys(NATIVE_IR_OPCODE).reduce(function(out, name) {
  out[NATIVE_IR_OPCODE[name]] = name;
  return out;
}, {}));

function nativeIRTypeCode(type) {
  if (type == null) return 0;
  if (type === 'i32') return 1;
  fail("ir binary: unsupported type '" + type + "'");
}

function nativeIRTypeName(code) {
  if (code === 0) return null;
  if (code === 1) return 'i32';
  fail('ir binary: unknown type code ' + code);
}

function nativeIREffectCode(effect) {
  if (effect === 'pure') return 0;
  if (effect === 'time') return 1;
  fail("ir binary: unsupported effect '" + effect + "'");
}

function nativeIREffectName(code) {
  if (code === 0) return 'pure';
  if (code === 1) return 'time';
  fail('ir binary: unknown effect code ' + code);
}

function nativeIRValueRef(value) {
  if (/^%arg[0-9]+$/.test(value)) {
    const index = Number(value.slice(4));
    if (!Number.isInteger(index) || index < 0 || index > 0x7FFFFFFE) fail('ir binary: parameter reference out of range');
    return (0x80000000 | index) >>> 0;
  }
  if (/^%v[0-9]+$/.test(value)) {
    const index = Number(value.slice(2));
    if (!Number.isInteger(index) || index < 0 || index > 0x7FFFFFFE) fail('ir binary: SSA reference out of range');
    return index >>> 0;
  }
  fail("ir binary: invalid SSA reference '" + value + "'");
}

function nativeIRValueName(ref) {
  if (ref === 0xFFFFFFFF) fail('ir binary: none is not a value reference');
  if ((ref & 0x80000000) !== 0) return '%arg' + (ref & 0x7FFFFFFF);
  return '%v' + ref;
}

function encodeNativeIRV0(ir) {
  ir.verify();
  if (ir.functions.length > MAX_NATIVE_IR_FUNCTIONS) fail('ir binary: too many functions');
  const writer = new NativeIRBinaryWriter();
  for (const byte of NATIVE_IR_BINARY_MAGIC) writer.u8(byte);
  writer.u16(NATIVE_IR_BINARY_VERSION);
  writer.u16(0);
  writer.u32(ir.functions.length);

  for (const fn of ir.functions) {
    if (fn.parameters.length > MAX_NATIVE_IR_PARAMETERS) fail("ir binary: too many parameters in '" + fn.name + "'");
    if (fn.instructions.length > MAX_NATIVE_IR_INSTRUCTIONS) fail("ir binary: too many instructions in '" + fn.name + "'");
    writer.string(fn.name);
    writer.u32(fn.graphNode);
    writer.u8(nativeIRTypeCode(fn.returnType));
    writer.u8(nativeIREffectCode(fn.effect));
    let capabilityFlags = 0;
    if (fn.requiresCapabilities.includes('time')) capabilityFlags |= 1;
    if (fn.grantsCapabilities.includes('time')) capabilityFlags |= 2;
    writer.u8(capabilityFlags);
    writer.string(fn.region);
    writer.u16(fn.parameters.length);
    writer.u32(fn.instructions.length);

    for (const parameter of fn.parameters) {
      writer.u16(parameter.index);
      writer.u8(nativeIRTypeCode(parameter.type));
      writer.string(parameter.name);
      writer.u32(parameter.graphNode);
    }

    for (const inst of fn.instructions) {
      const opcode = NATIVE_IR_OPCODE[inst.op];
      if (!opcode) fail("ir binary: unsupported operation '" + inst.op + "'");
      writer.u32(inst.index);
      writer.u8(opcode);
      writer.u32(inst.graphNode);
      writer.u32(inst.result == null ? 0xFFFFFFFF : nativeIRValueRef(inst.result));
      writer.u8(nativeIRTypeCode(inst.type));
      const args = Array.isArray(inst.args) ? inst.args : [];
      if (args.length > 255) fail('ir binary: too many instruction operands');
      writer.u8(args.length);
      for (const arg of args) writer.u32(nativeIRValueRef(arg));

      if (inst.op === 'const.i32') writer.i32(inst.value);
      else if (inst.op === 'call') writer.string(inst.target);
      else if (inst.op === 'local.bind') writer.string(inst.name);
    }
  }
  return writer.finish();
}

function decodeNativeIRV0(bytes) {
  const reader = new NativeIRBinaryReader(bytes);
  for (const expected of NATIVE_IR_BINARY_MAGIC) {
    if (reader.u8() !== expected) fail('ir binary: magic mismatch');
  }
  if (reader.u16() !== NATIVE_IR_BINARY_VERSION) fail('ir binary: unsupported version');
  if (reader.u16() !== 0) fail('ir binary: nonzero reserved flags');
  const functionCount = reader.u32();
  if (!functionCount || functionCount > MAX_NATIVE_IR_FUNCTIONS) fail('ir binary: invalid function count');

  const ir = new NativeIRModule();
  for (let f = 0; f < functionCount; f += 1) {
    const name = reader.string();
    const graphNode = reader.u32();
    const returnType = nativeIRTypeName(reader.u8());
    const effect = nativeIREffectName(reader.u8());
    const capabilityFlags = reader.u8();
    if ((capabilityFlags & ~3) !== 0) fail('ir binary: unknown capability flags');
    const region = reader.string();
    const parameterCount = reader.u16();
    const instructionCount = reader.u32();
    if (parameterCount > MAX_NATIVE_IR_PARAMETERS) fail("ir binary: too many parameters in '" + name + "'");
    if (instructionCount > MAX_NATIVE_IR_INSTRUCTIONS) fail("ir binary: too many instructions in '" + name + "'");

    const fn = ir.addFunction({
      name:name,
      graphNode:graphNode,
      returnType:returnType,
      effect:effect,
      requiresCapabilities:(capabilityFlags & 1) !== 0 ? ['time'] : [],
      grantsCapabilities:(capabilityFlags & 2) !== 0 ? ['time'] : [],
      region:region,
      parameters:[],
      instructions:[]
    });

    for (let p = 0; p < parameterCount; p += 1) {
      const index = reader.u16();
      const type = nativeIRTypeName(reader.u8());
      const parameterName = reader.string();
      const parameterGraphNode = reader.u32();
      fn.parameters.push({
        index:index,
        value:'%arg' + index,
        type:type,
        name:parameterName,
        graphNode:parameterGraphNode
      });
    }

    for (let i = 0; i < instructionCount; i += 1) {
      const index = reader.u32();
      const opcode = reader.u8();
      const op = NATIVE_IR_OPCODE_NAME[opcode];
      if (!op) fail('ir binary: unknown opcode ' + opcode);
      const graphNode = reader.u32();
      const resultRef = reader.u32();
      const type = nativeIRTypeName(reader.u8());
      const argCount = reader.u8();
      const args = [];
      for (let a = 0; a < argCount; a += 1) args.push(nativeIRValueName(reader.u32()));

      const inst = {
        index:index,
        op:op,
        graphNode:graphNode,
        args:args
      };
      if (resultRef !== 0xFFFFFFFF) inst.result = nativeIRValueName(resultRef);
      if (type != null) inst.type = type;
      if (op === 'region.begin' || op === 'region.end') inst.region = region;
      else if (op === 'const.i32') inst.value = reader.i32();
      else if (op === 'call') inst.target = reader.string();
      else if (op === 'intrinsic.clock') {
        inst.effect = 'time';
        inst.capability = 'time';
      } else if (op === 'local.bind') inst.name = reader.string();
      fn.instructions.push(inst);
    }
  }

  if (!reader.done()) fail('ir binary: trailing bytes');
  ir.verify();
  return ir;
}


const SEMNEXIS_ARM32_ELF_SCHEMA = 'SEMNEXIS_ARM32_ELF_PROOF_V0';
const ARM32_ELF_BASE_VADDR = 0x00010000;
const ARM32_ELF_HEADER_BYTES = 52;
const ARM32_ELF_PROGRAM_HEADER_BYTES = 32;
const ARM32_ELF_CODE_OFFSET = ARM32_ELF_HEADER_BYTES + ARM32_ELF_PROGRAM_HEADER_BYTES;
const ARM32_ELF_CODE_BYTES = 16;
const ARM32_ELF_TOTAL_BYTES = ARM32_ELF_CODE_OFFSET + ARM32_ELF_CODE_BYTES;

function checkedI32BigInt(value, context) {
  const min = -2147483648n;
  const max = 2147483647n;
  if (value < min || value > max) fail('arm32 backend: checked i32 overflow in ' + context);
  return value;
}

function evaluatePureNativeIRFunction(ir, fnName, args, state) {
  const fn = ir.functions.find((candidate) => candidate.name === fnName);
  if (!fn) fail("arm32 backend: unknown function '" + fnName + "'");
  if (fn.effect !== 'pure' || fn.requiresCapabilities.length || fn.grantsCapabilities.length) {
    fail("arm32 backend: effectful/capability function '" + fnName + "' is not supported by proof backend");
  }
  if (args.length !== fn.parameters.length) fail("arm32 backend: argument count mismatch for '" + fnName + "'");
  if (state.depth >= 128) fail('arm32 backend: call depth limit exceeded');
  if (state.active.has(fnName)) fail("arm32 backend: recursive function '" + fnName + "' is not supported by proof backend");

  state.active.add(fnName);
  state.depth += 1;
  try {
    const values = new Map();
    for (let i = 0; i < fn.parameters.length; i += 1) {
      values.set(fn.parameters[i].value, checkedI32BigInt(BigInt(args[i]), 'parameter ' + fn.parameters[i].name));
    }

    let returned = false;
    let returnValue = 0n;
    const readValue = (name) => {
      if (!values.has(name)) fail("arm32 backend: SSA value '" + name + "' is unavailable");
      return values.get(name);
    };

    for (const inst of fn.instructions) {
      let value;
      switch (inst.op) {
        case 'region.begin':
        case 'region.end':
        case 'local.bind':
          break;
        case 'const.i32':
          value = BigInt(inst.value);
          break;
        case 'copy.i32':
          value = readValue(inst.args[0]);
          break;
        case 'i32.add.checked':
          value = checkedI32BigInt(readValue(inst.args[0]) + readValue(inst.args[1]), 'add');
          break;
        case 'i32.sub.checked':
          value = checkedI32BigInt(readValue(inst.args[0]) - readValue(inst.args[1]), 'sub');
          break;
        case 'i32.mul.checked':
          value = checkedI32BigInt(readValue(inst.args[0]) * readValue(inst.args[1]), 'mul');
          break;
        case 'i32.div.checked': {
          const lhs = readValue(inst.args[0]);
          const rhs = readValue(inst.args[1]);
          if (rhs === 0n) fail('arm32 backend: division by zero');
          if (lhs === -2147483648n && rhs === -1n) fail('arm32 backend: checked i32 overflow in div');
          value = lhs / rhs;
          break;
        }
        case 'call': {
          const callArgs = inst.args.map((arg) => Number(readValue(arg)));
          value = BigInt(evaluatePureNativeIRFunction(ir, inst.target, callArgs, state));
          break;
        }
        case 'intrinsic.clock':
          fail('arm32 backend: clock requires runtime lowering and is not supported by proof backend');
          break;
        case 'ret.i32':
          returnValue = readValue(inst.args[0]);
          returned = true;
          break;
        default:
          fail("arm32 backend: unsupported IR operation '" + inst.op + "'");
      }
      if (inst.result != null) {
        if (value == null) fail("arm32 backend: result operation '" + inst.op + "' produced no value");
        values.set(inst.result, checkedI32BigInt(value, inst.op));
      }
    }
    if (!returned) fail("arm32 backend: function '" + fnName + "' did not return");
    return Number(checkedI32BigInt(returnValue, 'return'));
  } finally {
    state.depth -= 1;
    state.active.delete(fnName);
  }
}

function evaluatePureNativeIRV0(ir) {
  ir.verify();
  const main = ir.functions.find((fn) => fn.name === 'main');
  if (!main) fail("arm32 backend: entry function 'main' is required");
  if (main.parameters.length !== 0) fail("arm32 backend: entry function 'main' must have zero parameters");
  return evaluatePureNativeIRFunction(ir, 'main', [], {depth:0, active:new Set()});
}

function writeU16LE(bytes, offset, value) {
  bytes[offset] = value & 0xff;
  bytes[offset + 1] = (value >>> 8) & 0xff;
}

function writeU32LE(bytes, offset, value) {
  const v = value >>> 0;
  bytes[offset] = v & 0xff;
  bytes[offset + 1] = (v >>> 8) & 0xff;
  bytes[offset + 2] = (v >>> 16) & 0xff;
  bytes[offset + 3] = (v >>> 24) & 0xff;
}

function readU16LE(bytes, offset) {
  return (bytes[offset] | (bytes[offset + 1] << 8)) >>> 0;
}

function readU32LE(bytes, offset) {
  return (bytes[offset] |
    (bytes[offset + 1] << 8) |
    (bytes[offset + 2] << 16) |
    (bytes[offset + 3] << 24)) >>> 0;
}

function emitArm32ElfProofV0(ir) {
  const result = evaluatePureNativeIRV0(ir);
  const bytes = new Uint8Array(ARM32_ELF_TOTAL_BYTES);

  bytes[0] = 0x7f; bytes[1] = 0x45; bytes[2] = 0x4c; bytes[3] = 0x46;
  bytes[4] = 1;
  bytes[5] = 1;
  bytes[6] = 1;
  bytes[7] = 0;
  writeU16LE(bytes, 16, 2);
  writeU16LE(bytes, 18, 40);
  writeU32LE(bytes, 20, 1);
  writeU32LE(bytes, 24, ARM32_ELF_BASE_VADDR + ARM32_ELF_CODE_OFFSET);
  writeU32LE(bytes, 28, ARM32_ELF_HEADER_BYTES);
  writeU32LE(bytes, 32, 0);
  writeU32LE(bytes, 36, 0x05000000);
  writeU16LE(bytes, 40, ARM32_ELF_HEADER_BYTES);
  writeU16LE(bytes, 42, ARM32_ELF_PROGRAM_HEADER_BYTES);
  writeU16LE(bytes, 44, 1);
  writeU16LE(bytes, 46, 0);
  writeU16LE(bytes, 48, 0);
  writeU16LE(bytes, 50, 0);

  const ph = ARM32_ELF_HEADER_BYTES;
  writeU32LE(bytes, ph + 0, 1);
  writeU32LE(bytes, ph + 4, 0);
  writeU32LE(bytes, ph + 8, ARM32_ELF_BASE_VADDR);
  writeU32LE(bytes, ph + 12, ARM32_ELF_BASE_VADDR);
  writeU32LE(bytes, ph + 16, ARM32_ELF_TOTAL_BYTES);
  writeU32LE(bytes, ph + 20, ARM32_ELF_TOTAL_BYTES);
  writeU32LE(bytes, ph + 24, 5);
  writeU32LE(bytes, ph + 28, 0x1000);

  const code = ARM32_ELF_CODE_OFFSET;
  writeU32LE(bytes, code + 0, 0xE59F0004);
  writeU32LE(bytes, code + 4, 0xE3A07001);
  writeU32LE(bytes, code + 8, 0xEF000000);
  writeU32LE(bytes, code + 12, result >>> 0);

  const artifact = Object.freeze({
    schema:SEMNEXIS_ARM32_ELF_SCHEMA,
    target:'armv7a-linux-androideabi26',
    elfClass:'ELF32',
    machine:'EM_ARM',
    elfType:'ET_EXEC',
    entry:ARM32_ELF_BASE_VADDR + ARM32_ELF_CODE_OFFSET,
    bytes:bytes,
    byteLength:bytes.length,
    constantResult:result,
    executionPolicy:'generated-artifact-not-executed-from-riftfs'
  });
  verifyArm32ElfProofV0(artifact);
  return artifact;
}

function verifyArm32ElfProofV0(artifact) {
  if (!artifact || artifact.schema !== SEMNEXIS_ARM32_ELF_SCHEMA) fail('arm32 elf verify: schema mismatch');
  const bytes = artifact.bytes;
  if (!(bytes instanceof Uint8Array) || bytes.length !== ARM32_ELF_TOTAL_BYTES) fail('arm32 elf verify: size mismatch');
  if (bytes[0] !== 0x7f || bytes[1] !== 0x45 || bytes[2] !== 0x4c || bytes[3] !== 0x46) fail('arm32 elf verify: magic mismatch');
  if (bytes[4] !== 1 || bytes[5] !== 1 || bytes[6] !== 1) fail('arm32 elf verify: class/data/version mismatch');
  if (readU16LE(bytes, 16) !== 2) fail('arm32 elf verify: expected ET_EXEC');
  if (readU16LE(bytes, 18) !== 40) fail('arm32 elf verify: expected EM_ARM');
  if (readU32LE(bytes, 20) !== 1) fail('arm32 elf verify: ELF version mismatch');
  if (readU32LE(bytes, 24) !== ARM32_ELF_BASE_VADDR + ARM32_ELF_CODE_OFFSET) fail('arm32 elf verify: entry mismatch');
  if (readU32LE(bytes, 28) !== ARM32_ELF_HEADER_BYTES) fail('arm32 elf verify: program-header offset mismatch');
  if (readU32LE(bytes, 36) !== 0x05000000) fail('arm32 elf verify: ARM EABI flags mismatch');
  if (readU16LE(bytes, 40) !== ARM32_ELF_HEADER_BYTES ||
      readU16LE(bytes, 42) !== ARM32_ELF_PROGRAM_HEADER_BYTES ||
      readU16LE(bytes, 44) !== 1) fail('arm32 elf verify: ELF header sizes mismatch');

  const ph = ARM32_ELF_HEADER_BYTES;
  if (readU32LE(bytes, ph + 0) !== 1 ||
      readU32LE(bytes, ph + 4) !== 0 ||
      readU32LE(bytes, ph + 8) !== ARM32_ELF_BASE_VADDR ||
      readU32LE(bytes, ph + 16) !== bytes.length ||
      readU32LE(bytes, ph + 20) !== bytes.length ||
      readU32LE(bytes, ph + 24) !== 5 ||
      readU32LE(bytes, ph + 28) !== 0x1000) {
    fail('arm32 elf verify: PT_LOAD contract mismatch');
  }

  const code = ARM32_ELF_CODE_OFFSET;
  if (readU32LE(bytes, code + 0) !== 0xE59F0004 ||
      readU32LE(bytes, code + 4) !== 0xE3A07001 ||
      readU32LE(bytes, code + 8) !== 0xEF000000) {
    fail('arm32 elf verify: bootstrap code mismatch');
  }
  if ((readU32LE(bytes, code + 12) | 0) !== artifact.constantResult) fail('arm32 elf verify: result literal mismatch');
  return true;
}


const SEMNEXIS_ARM32_RUNTIME_ELF_SCHEMA = 'SEMNEXIS_ARM32_RUNTIME_ELF_V0';
const ARM32_RUNTIME_MAX_FUNCTIONS = 256;
const ARM32_RUNTIME_MAX_PARAMETERS = 4;
const ARM32_RUNTIME_MAX_SSA_VALUES = 1000;
const ARM32_RUNTIME_TRAP_EXIT_CODE = 125;

function arm32Movw(rd, imm16) {
  if (!Number.isInteger(rd) || rd < 0 || rd > 15) fail('arm32 runtime: invalid MOVW register');
  const imm = imm16 & 0xffff;
  return (0xE3000000 | ((imm & 0xF000) << 4) | (rd << 12) | (imm & 0x0FFF)) >>> 0;
}

function arm32Movt(rd, imm16) {
  if (!Number.isInteger(rd) || rd < 0 || rd > 15) fail('arm32 runtime: invalid MOVT register');
  const imm = imm16 & 0xffff;
  return (0xE3400000 | ((imm & 0xF000) << 4) | (rd << 12) | (imm & 0x0FFF)) >>> 0;
}

function arm32LoadI32(words, rd, value) {
  const raw = value >>> 0;
  words.push(arm32Movw(rd, raw & 0xffff));
  words.push(arm32Movt(rd, (raw >>> 16) & 0xffff));
}

function arm32LdrSp(rd, offset) {
  if (!Number.isInteger(offset) || offset < 0 || offset > 4095) fail('arm32 runtime: stack load offset out of range');
  return (0xE59D0000 | (rd << 12) | offset) >>> 0;
}

function arm32StrSp(rd, offset) {
  if (!Number.isInteger(offset) || offset < 0 || offset > 4095) fail('arm32 runtime: stack store offset out of range');
  return (0xE58D0000 | (rd << 12) | offset) >>> 0;
}

function arm32BranchWord(conditionBase, fromAddress, targetAddress) {
  if ((fromAddress & 3) !== 0 || (targetAddress & 3) !== 0) fail('arm32 runtime: unaligned branch address');
  const delta = targetAddress - (fromAddress + 8);
  if ((delta & 3) !== 0) fail('arm32 runtime: branch delta is not word-aligned');
  const words = delta / 4;
  if (words < -0x800000 || words > 0x7fffff) fail('arm32 runtime: branch target out of range');
  return (conditionBase | (words & 0x00ffffff)) >>> 0;
}

function arm32RuntimeAllowedFunction(fn) {
  if (fn.effect !== 'pure' || fn.requiresCapabilities.length || fn.grantsCapabilities.length) {
    fail("arm32 runtime: effectful/capability function '" + fn.name + "' is not supported");
  }
  if (fn.parameters.length > ARM32_RUNTIME_MAX_PARAMETERS) {
    fail("arm32 runtime: function '" + fn.name + "' exceeds four register parameters");
  }
}

function verifyAcyclicRuntimeCalls(ir) {
  const edges = new Map();
  for (const fn of ir.functions) edges.set(fn.name, []);
  for (const fn of ir.functions) {
    for (const inst of fn.instructions) if (inst.op === 'call') edges.get(fn.name).push(inst.target);
  }
  const visiting = new Set();
  const visited = new Set();
  const visit = (name) => {
    if (visiting.has(name)) fail("arm32 runtime: recursive call cycle includes '" + name + "'");
    if (visited.has(name)) return;
    visiting.add(name);
    for (const target of edges.get(name) || []) visit(target);
    visiting.delete(name);
    visited.add(name);
  };
  for (const fn of ir.functions) visit(fn.name);
}

function allocateArm32RuntimeFrame(fn) {
  const slots = new Map();
  let nextSlot = 0;
  for (const parameter of fn.parameters) {
    slots.set(parameter.value, nextSlot);
    nextSlot += 1;
  }
  for (const inst of fn.instructions) {
    if (inst.result != null) {
      if (slots.has(inst.result)) fail("arm32 runtime: duplicate allocated value '" + inst.result + "'");
      slots.set(inst.result, nextSlot);
      nextSlot += 1;
    }
  }
  if (nextSlot > ARM32_RUNTIME_MAX_SSA_VALUES) fail("arm32 runtime: function '" + fn.name + "' has too many SSA values");
  const rawBytes = nextSlot * 4;
  const frameBytes = rawBytes === 0 ? 0 : Math.ceil(rawBytes / 8) * 8;
  if (frameBytes > 4096) fail("arm32 runtime: function '" + fn.name + "' frame exceeds V0 stack-offset limit");
  return {slots:slots, slotCount:nextSlot, frameBytes:frameBytes};
}

function arm32RuntimeSlotOffset(frame, value) {
  if (!frame.slots.has(value)) fail("arm32 runtime: missing stack slot for '" + value + "'");
  const offset = frame.slots.get(value) * 4;
  if (offset > 4095) fail('arm32 runtime: stack slot offset exceeds encoding limit');
  return offset;
}

function compileArm32RuntimeFunctionV0(fn) {
  arm32RuntimeAllowedFunction(fn);
  const frame = allocateArm32RuntimeFrame(fn);
  const words = [];
  const patches = [];

  words.push(0xE92D4800);
  if (frame.frameBytes > 0) {
    arm32LoadI32(words, 12, frame.frameBytes);
    words.push(0xE04DD00C);
  }

  for (let i = 0; i < fn.parameters.length; i += 1) {
    words.push(arm32StrSp(i, arm32RuntimeSlotOffset(frame, fn.parameters[i].value)));
  }

  for (const inst of fn.instructions) {
    switch (inst.op) {
      case 'region.begin':
      case 'region.end':
      case 'local.bind':
        break;
      case 'const.i32':
        arm32LoadI32(words, 0, inst.value);
        words.push(arm32StrSp(0, arm32RuntimeSlotOffset(frame, inst.result)));
        break;
      case 'copy.i32':
        words.push(arm32LdrSp(0, arm32RuntimeSlotOffset(frame, inst.args[0])));
        words.push(arm32StrSp(0, arm32RuntimeSlotOffset(frame, inst.result)));
        break;
      case 'i32.add.checked':
      case 'i32.sub.checked': {
        words.push(arm32LdrSp(0, arm32RuntimeSlotOffset(frame, inst.args[0])));
        words.push(arm32LdrSp(1, arm32RuntimeSlotOffset(frame, inst.args[1])));
        words.push(inst.op === 'i32.add.checked' ? 0xE0902001 : 0xE0502001);
        patches.push({wordIndex:words.length, kind:'bvs', target:'$trap'});
        words.push(0);
        words.push(arm32StrSp(2, arm32RuntimeSlotOffset(frame, inst.result)));
        break;
      }
      case 'call':
        if (inst.args.length > ARM32_RUNTIME_MAX_PARAMETERS) {
          fail("arm32 runtime: call to '" + inst.target + "' exceeds four register arguments");
        }
        for (let i = 0; i < inst.args.length; i += 1) {
          words.push(arm32LdrSp(i, arm32RuntimeSlotOffset(frame, inst.args[i])));
        }
        patches.push({wordIndex:words.length, kind:'bl', target:inst.target});
        words.push(0);
        words.push(arm32StrSp(0, arm32RuntimeSlotOffset(frame, inst.result)));
        break;
      case 'ret.i32':
        words.push(arm32LdrSp(0, arm32RuntimeSlotOffset(frame, inst.args[0])));
        if (frame.frameBytes > 0) {
          arm32LoadI32(words, 12, frame.frameBytes);
          words.push(0xE08DD00C);
        }
        words.push(0xE8BD8800);
        break;
      case 'i32.mul.checked':
      case 'i32.div.checked':
        fail("arm32 runtime: operation '" + inst.op + "' is not lowered in V0 runtime backend");
        break;
      case 'intrinsic.clock':
        fail('arm32 runtime: clock requires runtime capability lowering');
        break;
      default:
        fail("arm32 runtime: unsupported IR operation '" + inst.op + "'");
    }
  }

  if (!words.length || words[words.length - 1] !== 0xE8BD8800) {
    fail("arm32 runtime: function '" + fn.name + "' has no canonical return epilogue");
  }

  return {
    name:fn.name,
    frameBytes:frame.frameBytes,
    slotCount:frame.slotCount,
    parameterCount:fn.parameters.length,
    words:words,
    patches:patches
  };
}

function emitArm32RuntimeElfV0(ir) {
  ir.verify();
  if (!ir.functions.length || ir.functions.length > ARM32_RUNTIME_MAX_FUNCTIONS) fail('arm32 runtime: invalid function count');
  const main = ir.functions.find((fn) => fn.name === 'main');
  if (!main) fail("arm32 runtime: entry function 'main' is required");
  if (main.parameters.length !== 0) fail("arm32 runtime: entry function 'main' must have zero parameters");
  for (const fn of ir.functions) arm32RuntimeAllowedFunction(fn);
  verifyAcyclicRuntimeCalls(ir);

  const compiled = ir.functions.map(compileArm32RuntimeFunctionV0);
  const startWords = [0, 0xE3A07001, 0xEF000000];
  const trapWords = [
    arm32Movw(0, ARM32_RUNTIME_TRAP_EXIT_CODE),
    arm32Movt(0, 0),
    0xE3A07001,
    0xEF000000
  ];

  let cursor = ARM32_ELF_CODE_OFFSET + startWords.length * 4;
  const functionAddresses = new Map();
  const functionMeta = [];
  for (const fn of compiled) {
    const address = ARM32_ELF_BASE_VADDR + cursor;
    functionAddresses.set(fn.name, address);
    functionMeta.push({
      name:fn.name,
      address:address,
      fileOffset:cursor,
      bytes:fn.words.length * 4,
      frameBytes:fn.frameBytes,
      slotCount:fn.slotCount,
      parameterCount:fn.parameterCount
    });
    cursor += fn.words.length * 4;
  }
  const trapOffset = cursor;
  const trapAddress = ARM32_ELF_BASE_VADDR + trapOffset;
  cursor += trapWords.length * 4;
  const totalBytes = cursor;

  const entry = ARM32_ELF_BASE_VADDR + ARM32_ELF_CODE_OFFSET;
  startWords[0] = arm32BranchWord(0xEB000000, entry, functionAddresses.get('main'));

  for (let f = 0; f < compiled.length; f += 1) {
    const fn = compiled[f];
    const meta = functionMeta[f];
    for (const patch of fn.patches) {
      const from = meta.address + patch.wordIndex * 4;
      const target = patch.target === '$trap' ? trapAddress : functionAddresses.get(patch.target);
      if (target == null) fail("arm32 runtime: missing patch target '" + patch.target + "'");
      fn.words[patch.wordIndex] = arm32BranchWord(
        patch.kind === 'bl' ? 0xEB000000 : 0x6A000000,
        from,
        target
      );
    }
  }

  const bytes = new Uint8Array(totalBytes);
  bytes[0] = 0x7f; bytes[1] = 0x45; bytes[2] = 0x4c; bytes[3] = 0x46;
  bytes[4] = 1;
  bytes[5] = 1;
  bytes[6] = 1;
  bytes[7] = 0;
  writeU16LE(bytes, 16, 2);
  writeU16LE(bytes, 18, 40);
  writeU32LE(bytes, 20, 1);
  writeU32LE(bytes, 24, entry);
  writeU32LE(bytes, 28, ARM32_ELF_HEADER_BYTES);
  writeU32LE(bytes, 32, 0);
  writeU32LE(bytes, 36, 0x05000000);
  writeU16LE(bytes, 40, ARM32_ELF_HEADER_BYTES);
  writeU16LE(bytes, 42, ARM32_ELF_PROGRAM_HEADER_BYTES);
  writeU16LE(bytes, 44, 1);
  writeU16LE(bytes, 46, 0);
  writeU16LE(bytes, 48, 0);
  writeU16LE(bytes, 50, 0);

  const ph = ARM32_ELF_HEADER_BYTES;
  writeU32LE(bytes, ph + 0, 1);
  writeU32LE(bytes, ph + 4, 0);
  writeU32LE(bytes, ph + 8, ARM32_ELF_BASE_VADDR);
  writeU32LE(bytes, ph + 12, ARM32_ELF_BASE_VADDR);
  writeU32LE(bytes, ph + 16, totalBytes);
  writeU32LE(bytes, ph + 20, totalBytes);
  writeU32LE(bytes, ph + 24, 5);
  writeU32LE(bytes, ph + 28, 0x1000);

  let outOffset = ARM32_ELF_CODE_OFFSET;
  for (const word of startWords) {
    writeU32LE(bytes, outOffset, word);
    outOffset += 4;
  }
  for (const fn of compiled) {
    for (const word of fn.words) {
      writeU32LE(bytes, outOffset, word);
      outOffset += 4;
    }
  }
  for (const word of trapWords) {
    writeU32LE(bytes, outOffset, word);
    outOffset += 4;
  }
  if (outOffset !== totalBytes) fail('arm32 runtime: image layout size mismatch');

  const artifact = Object.freeze({
    schema:SEMNEXIS_ARM32_RUNTIME_ELF_SCHEMA,
    target:'armv7a-linux-androideabi26',
    elfClass:'ELF32',
    machine:'EM_ARM',
    elfType:'ET_EXEC',
    entry:entry,
    bytes:bytes,
    byteLength:bytes.length,
    functions:functionMeta,
    trapAddress:trapAddress,
    trapExitCode:ARM32_RUNTIME_TRAP_EXIT_CODE,
    constantEvaluated:false,
    runtimeLowered:true,
    executionPolicy:'generated-artifact-not-executed-from-riftfs'
  });
  verifyArm32RuntimeElfV0(artifact);
  return artifact;
}

function verifyArm32RuntimeElfV0(artifact) {
  if (!artifact || artifact.schema !== SEMNEXIS_ARM32_RUNTIME_ELF_SCHEMA) fail('arm32 runtime verify: schema mismatch');
  const bytes = artifact.bytes;
  if (!(bytes instanceof Uint8Array) || bytes.length < ARM32_ELF_CODE_OFFSET + 12) fail('arm32 runtime verify: image too small');
  if (bytes[0] !== 0x7f || bytes[1] !== 0x45 || bytes[2] !== 0x4c || bytes[3] !== 0x46) fail('arm32 runtime verify: magic mismatch');
  if (bytes[4] !== 1 || bytes[5] !== 1 || bytes[6] !== 1) fail('arm32 runtime verify: ELF identity mismatch');
  if (readU16LE(bytes, 16) !== 2 || readU16LE(bytes, 18) !== 40 || readU32LE(bytes, 20) !== 1) fail('arm32 runtime verify: ELF type/machine/version mismatch');
  if (readU32LE(bytes, 24) !== artifact.entry || artifact.entry !== ARM32_ELF_BASE_VADDR + ARM32_ELF_CODE_OFFSET) fail('arm32 runtime verify: entry mismatch');
  if (readU32LE(bytes, 28) !== ARM32_ELF_HEADER_BYTES || readU32LE(bytes, 36) !== 0x05000000) fail('arm32 runtime verify: ELF header contract mismatch');
  if (readU16LE(bytes, 40) !== ARM32_ELF_HEADER_BYTES ||
      readU16LE(bytes, 42) !== ARM32_ELF_PROGRAM_HEADER_BYTES ||
      readU16LE(bytes, 44) !== 1) fail('arm32 runtime verify: header sizes mismatch');

  const ph = ARM32_ELF_HEADER_BYTES;
  if (readU32LE(bytes, ph + 0) !== 1 ||
      readU32LE(bytes, ph + 4) !== 0 ||
      readU32LE(bytes, ph + 8) !== ARM32_ELF_BASE_VADDR ||
      readU32LE(bytes, ph + 16) !== bytes.length ||
      readU32LE(bytes, ph + 20) !== bytes.length ||
      readU32LE(bytes, ph + 24) !== 5 ||
      readU32LE(bytes, ph + 28) !== 0x1000) fail('arm32 runtime verify: PT_LOAD mismatch');

  const start = ARM32_ELF_CODE_OFFSET;
  const startBl = readU32LE(bytes, start);
  if (((startBl & 0xFF000000) >>> 0) !== 0xEB000000) fail('arm32 runtime verify: entry does not BL main');
  if (readU32LE(bytes, start + 4) !== 0xE3A07001 || readU32LE(bytes, start + 8) !== 0xEF000000) {
    fail('arm32 runtime verify: entry exit sequence mismatch');
  }

  const seen = new Set();
  for (const fn of artifact.functions) {
    if (!fn.name || seen.has(fn.name)) fail('arm32 runtime verify: duplicate function metadata');
    seen.add(fn.name);
    if ((fn.address & 3) !== 0 || (fn.fileOffset & 3) !== 0 || (fn.bytes & 3) !== 0 || fn.bytes < 8) {
      fail("arm32 runtime verify: malformed function layout for '" + fn.name + "'");
    }
    if (fn.fileOffset < ARM32_ELF_CODE_OFFSET + 12 || fn.fileOffset + fn.bytes > bytes.length) {
      fail("arm32 runtime verify: function range escapes image for '" + fn.name + "'");
    }
    if (fn.address !== ARM32_ELF_BASE_VADDR + fn.fileOffset) fail("arm32 runtime verify: function address mismatch for '" + fn.name + "'");
    if ((fn.frameBytes & 7) !== 0) fail("arm32 runtime verify: unaligned frame for '" + fn.name + "'");
    if (readU32LE(bytes, fn.fileOffset) !== 0xE92D4800) fail("arm32 runtime verify: prologue mismatch for '" + fn.name + "'");
    if (readU32LE(bytes, fn.fileOffset + fn.bytes - 4) !== 0xE8BD8800) fail("arm32 runtime verify: epilogue mismatch for '" + fn.name + "'");
  }
  if (!seen.has('main')) fail('arm32 runtime verify: main metadata missing');

  const trapOffset = artifact.trapAddress - ARM32_ELF_BASE_VADDR;
  if (trapOffset < ARM32_ELF_CODE_OFFSET || trapOffset + 16 !== bytes.length) fail('arm32 runtime verify: trap layout mismatch');
  if (readU32LE(bytes, trapOffset) !== arm32Movw(0, artifact.trapExitCode) ||
      readU32LE(bytes, trapOffset + 4) !== arm32Movt(0, 0) ||
      readU32LE(bytes, trapOffset + 8) !== 0xE3A07001 ||
      readU32LE(bytes, trapOffset + 12) !== 0xEF000000) {
    fail('arm32 runtime verify: overflow trap mismatch');
  }
  if (artifact.constantEvaluated !== false || artifact.runtimeLowered !== true) fail('arm32 runtime verify: backend mode markers mismatch');
  return true;
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
  const ir = buildNativeIR(graph);
  return Object.freeze({
    schema:'semnexis-bootstrap-compile-result/1',
    language:SEMNEXIS_LANGUAGE,
    compiler:SEMNEXIS_BOOTSTRAP_VERSION,
    host:'quickjs',
    module:module,
    graph:graph,
    plan:plan,
    ir:ir,
    graphText:graph.dump(),
    planText:plan.dump(),
    irText:ir.dump()
  });
}

export function encodeSemnexisNativeIRV0(ir) {
  return encodeNativeIRV0(ir);
}

export function decodeSemnexisNativeIRV0(bytes) {
  return decodeNativeIRV0(bytes);
}

export function emitSemnexisArm32ElfProofV0(ir) {
  return emitArm32ElfProofV0(ir);
}

export function verifySemnexisArm32ElfProofV0(artifact) {
  return verifyArm32ElfProofV0(artifact);
}

export function emitSemnexisArm32RuntimeElfV0(ir) {
  return emitArm32RuntimeElfV0(ir);
}

export function verifySemnexisArm32RuntimeElfV0(artifact) {
  return verifyArm32RuntimeElfV0(artifact);
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
    irFunctions:result.ir.functions.length,
    irInstructions:result.ir.functions.reduce(function(total, fn) { return total + fn.instructions.length; }, 0),
    functions:result.module.functions.map(function(fn) { return fn.name; })
  });
}

if (typeof globalThis !== 'undefined') {
  globalThis.SemnexisBootstrap = Object.freeze({
    version:SEMNEXIS_BOOTSTRAP_VERSION,
    language:SEMNEXIS_LANGUAGE,
    graphSchema:SEMNEXIS_GRAPH_SCHEMA,
    planSchema:SEMNEXIS_PLAN_SCHEMA,
    irSchema:SEMNEXIS_NATIVE_IR_SCHEMA,
    irBinaryFormat:'SNIRV0',
    arm32ElfSchema:SEMNEXIS_ARM32_ELF_SCHEMA,
    arm32RuntimeElfSchema:SEMNEXIS_ARM32_RUNTIME_ELF_SCHEMA,
    encodeIR:encodeNativeIRV0,
    decodeIR:decodeNativeIRV0,
    emitArm32Proof:emitArm32ElfProofV0,
    verifyArm32Proof:verifyArm32ElfProofV0,
    emitArm32Runtime:emitArm32RuntimeElfV0,
    verifyArm32Runtime:verifyArm32RuntimeElfV0,
    compile:compileSemnexisV0,
    inspect:inspectSemnexisV0
  });
}
