const SEMNEXIS_BOOTSTRAP_VERSION = '0.7.0-quickjs-bootstrap';
const SEMNEXIS_LANGUAGE = 'Semnexis';
const SEMNEXIS_GRAPH_SCHEMA = 'SEMNEXIS_PROGRAM_GRAPH_V0';
const SEMNEXIS_PLAN_SCHEMA = 'SEMNEXIS_EXECUTION_PLAN_V0';
const SEMNEXIS_NATIVE_IR_SCHEMA = 'SEMNEXIS_NATIVE_IR_V0';
const MAX_SEMNEXIS_SOURCE_CHARS = 512 * 1024;
const MAX_SEMNEXIS_TOKENS = 65536;
const MAX_SEMNEXIS_FUNCTIONS = 1024;
const MAX_SEMNEXIS_STRUCTS = 256;
const MAX_SEMNEXIS_STRUCT_FIELDS = 16;
const MAX_SEMNEXIS_FLAT_RECORD_WORDS = 4;
const MAX_SEMNEXIS_EXPRESSION_DEPTH = 256;
const MAX_SEMNEXIS_TYPE_DEPTH = 16;
const MAX_SEMNEXIS_GRAPH_NODES = 65536;
const MAX_SEMNEXIS_GRAPH_EDGES = 262144;
const MAX_SEMNEXIS_CFG_BLOCKS = 512;
const MAX_SEMNEXIS_IDENTIFIER_CHARS = 255;
const MAX_SEMNEXIS_INTEGER_CHARS = 10;

const TokenKind = Object.freeze({
  End:'End', Identifier:'Identifier', Integer:'Integer',
  KwFn:'KwFn', KwStruct:'KwStruct', KwLet:'KwLet', KwReturn:'KwReturn', KwWith:'KwWith',
  KwIf:'KwIf', KwElse:'KwElse', KwLoop:'KwLoop', KwWhile:'KwWhile', KwNext:'KwNext', KwYield:'KwYield',
  LParen:'LParen', RParen:'RParen', LBrace:'LBrace', RBrace:'RBrace',
  Arrow:'Arrow', Colon:'Colon', Comma:'Comma', Dot:'Dot', Equal:'Equal', EqEq:'EqEq', BangEq:'BangEq',
  Less:'Less', LessEq:'LessEq', Greater:'Greater', GreaterEq:'GreaterEq',
  Semicolon:'Semicolon', Plus:'Plus', Minus:'Minus', Star:'Star', Slash:'Slash'
});

const NodeKind = Object.freeze({
  Module:'Module', Function:'Function', Type:'Type', Effect:'Effect',
  Capability:'Capability', Intrinsic:'Intrinsic', Region:'Region',
  Parameter:'Parameter', Local:'Local', Return:'Return', Constant:'Constant',
  NameRef:'NameRef', Binary:'Binary', Convert:'Convert', Call:'Call', Record:'Record', Field:'Field', Conditional:'Conditional', Loop:'Loop', LoopState:'LoopState'
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
        if (token.lexeme.length > MAX_SEMNEXIS_IDENTIFIER_CHARS) fail('lexer: identifier exceeds compiler budget at ' + line + ':' + column);
        if (token.lexeme === 'fn') token.kind = TokenKind.KwFn;
        else if (token.lexeme === 'struct') token.kind = TokenKind.KwStruct;
        else if (token.lexeme === 'let') token.kind = TokenKind.KwLet;
        else if (token.lexeme === 'return') token.kind = TokenKind.KwReturn;
        else if (token.lexeme === 'with') token.kind = TokenKind.KwWith;
        else if (token.lexeme === 'if') token.kind = TokenKind.KwIf;
        else if (token.lexeme === 'else') token.kind = TokenKind.KwElse;
        else if (token.lexeme === 'loop') token.kind = TokenKind.KwLoop;
        else if (token.lexeme === 'while') token.kind = TokenKind.KwWhile;
        else if (token.lexeme === 'next') token.kind = TokenKind.KwNext;
        else if (token.lexeme === 'yield') token.kind = TokenKind.KwYield;
        out.push(token);
        continue;
      }
      if (isDigit(c)) {
        this.advance();
        while (isDigit(this.peek())) this.advance();
        const token = this.make(TokenKind.Integer, start, line, column);
        if (token.lexeme.length > MAX_SEMNEXIS_INTEGER_CHARS) fail('lexer: integer literal exceeds compiler budget at ' + line + ':' + column);
        out.push(token);
        continue;
      }
      const one = {
        '(' : TokenKind.LParen, ')' : TokenKind.RParen,
        '{' : TokenKind.LBrace, '}' : TokenKind.RBrace,
        ':' : TokenKind.Colon, ',' : TokenKind.Comma, '.' : TokenKind.Dot,
        ';' : TokenKind.Semicolon,
        '+' : TokenKind.Plus, '*' : TokenKind.Star, '/' : TokenKind.Slash
      };
      if (one[c]) {
        this.advance();
        out.push(this.make(one[c], start, line, column));
        continue;
      }
      if (c === '=') {
        this.advance();
        if (this.peek() === '=') { this.advance(); out.push(this.make(TokenKind.EqEq, start, line, column)); }
        else out.push(this.make(TokenKind.Equal, start, line, column));
        continue;
      }
      if (c === '!') {
        this.advance();
        if (this.peek() !== '=') fail("lexer: expected '=' after '!' at " + line + ':' + column);
        this.advance();
        out.push(this.make(TokenKind.BangEq, start, line, column));
        continue;
      }
      if (c === '<' || c === '>') {
        const first = this.advance();
        const equal = this.peek() === '=';
        if (equal) this.advance();
        const kind = first === '<'
          ? (equal ? TokenKind.LessEq : TokenKind.Less)
          : (equal ? TokenKind.GreaterEq : TokenKind.Greater);
        out.push(this.make(kind, start, line, column));
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
  constructor(tokens) { this.tokens = tokens; this.pos = 0; this.expressionDepth = 0; this.typeDepth = 0; }
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
    const structs = [];
    const functions = [];
    while (this.peek().kind !== TokenKind.End) {
      if (this.peek().kind === TokenKind.KwStruct) {
        if (structs.length >= MAX_SEMNEXIS_STRUCTS) fail('parser: struct count exceeds compiler budget');
        structs.push(this.parseStruct());
        continue;
      }
      if (functions.length >= MAX_SEMNEXIS_FUNCTIONS) fail('parser: function count exceeds compiler budget');
      functions.push(this.parseFunction());
    }
    if (!functions.length) fail('parser: module must contain at least one function');
    return {structs:structs, functions:functions};
  }
  parseStruct() {
    this.expect(TokenKind.KwStruct, "expected 'struct'");
    const name = this.expect(TokenKind.Identifier, 'expected struct name').lexeme;
    this.expect(TokenKind.LBrace, "expected '{' after struct name");
    const fields = [];
    const names = new Set();
    while (this.peek().kind !== TokenKind.RBrace) {
      if (fields.length >= MAX_SEMNEXIS_STRUCT_FIELDS) fail("parser: struct '" + name + "' exceeds field budget");
      const fieldName = this.expect(TokenKind.Identifier, 'expected struct field name').lexeme;
      if (names.has(fieldName)) fail("parser: duplicate struct field '" + fieldName + "'");
      names.add(fieldName);
      this.expect(TokenKind.Colon, "expected ':' after struct field name");
      const type = this.parseTypeReference();
      fields.push({name:fieldName, type:type});
      if (!this.match(TokenKind.Comma)) break;
    }
    this.expect(TokenKind.RBrace, "expected '}' after struct fields");
    if (!fields.length) fail("parser: struct '" + name + "' requires at least one field");
    return {name:name, fields:fields};
  }
  parseTypeReference() {
    this.typeDepth += 1;
    if (this.typeDepth > MAX_SEMNEXIS_TYPE_DEPTH) {
      this.typeDepth -= 1;
      fail('parser: type nesting exceeds compiler budget');
    }
    try {
      const base = this.expect(TokenKind.Identifier, 'expected type name').lexeme;
      if (!this.match(TokenKind.Less)) return base;
      const args = [];
      for (;;) {
        args.push(this.parseTypeReference());
        if (!this.match(TokenKind.Comma)) break;
      }
      this.expect(TokenKind.Greater, "expected '>' after type arguments");
      return base + '<' + args.join(',') + '>';
    } finally {
      this.typeDepth -= 1;
    }
  }
  parseParameter() {
    const name = this.expect(TokenKind.Identifier, 'expected parameter name');
    this.expect(TokenKind.Colon, "expected ':' after parameter name");
    const type = this.parseTypeReference();
    return {name:name.lexeme, type:type};
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
    const returnType = this.parseTypeReference();
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
      name:name.lexeme, parameters:parameters, returnType:returnType,
      grantedCapabilities:grantedCapabilities, locals:locals, returnExpr:returnExpr
    };
  }
  parseExpr() {
    this.expressionDepth += 1;
    if (this.expressionDepth > MAX_SEMNEXIS_EXPRESSION_DEPTH) {
      this.expressionDepth -= 1;
      fail('parser: expression nesting exceeds compiler budget');
    }
    try {
      if (this.peek().kind === TokenKind.KwIf) return this.parseIfExpression();
      if (this.peek().kind === TokenKind.KwLoop) return this.parseLoopExpression();
      return this.parseComparison();
    } finally {
      this.expressionDepth -= 1;
    }
  }
  parseIfExpression() {
    this.expect(TokenKind.KwIf, "expected 'if'");
    const condition = this.parseComparison();
    this.expect(TokenKind.LBrace, "expected '{' after if condition");
    const thenExpr = this.parseExpr();
    this.expect(TokenKind.RBrace, "expected '}' after if branch");
    this.expect(TokenKind.KwElse, "expected 'else' after if branch");
    this.expect(TokenKind.LBrace, "expected '{' after else");
    const elseExpr = this.parseExpr();
    this.expect(TokenKind.RBrace, "expected '}' after else branch");
    return {kind:'IfExpr', condition:condition, thenExpr:thenExpr, elseExpr:elseExpr};
  }
  parseLoopExpression() {
    this.expect(TokenKind.KwLoop, "expected 'loop'");
    this.expect(TokenKind.LParen, "expected '(' after loop");
    const states = [];
    if (this.peek().kind !== TokenKind.RParen) {
      for (;;) {
        const name = this.expect(TokenKind.Identifier, 'expected loop state name');
        this.expect(TokenKind.Equal, "expected '=' after loop state name");
        states.push({name:name.lexeme, initializer:this.parseExpr()});
        if (!this.match(TokenKind.Comma)) break;
      }
    }
    this.expect(TokenKind.RParen, "expected ')' after loop state list");
    if (!states.length) fail('parser: loop requires at least one carried state');
    this.expect(TokenKind.KwWhile, "expected 'while' after loop state list");
    const condition = this.parseComparison();
    this.expect(TokenKind.LBrace, "expected '{' before loop next values");
    this.expect(TokenKind.KwNext, "expected 'next' in loop body");
    this.expect(TokenKind.LParen, "expected '(' after next");
    const nextValues = [];
    if (this.peek().kind !== TokenKind.RParen) {
      for (;;) {
        nextValues.push(this.parseExpr());
        if (!this.match(TokenKind.Comma)) break;
      }
    }
    this.expect(TokenKind.RParen, "expected ')' after next values");
    this.match(TokenKind.Semicolon);
    this.expect(TokenKind.RBrace, "expected '}' after loop body");
    this.expect(TokenKind.KwYield, "expected 'yield' after loop body");
    const yieldExpr = this.parseExpr();
    return {kind:'LoopExpr', states:states, condition:condition, nextValues:nextValues, yieldExpr:yieldExpr};
  }
  parseComparison() {
    const left = this.parseAdditive();
    const kind = this.peek().kind;
    if (kind !== TokenKind.EqEq && kind !== TokenKind.BangEq &&
        kind !== TokenKind.Less && kind !== TokenKind.LessEq &&
        kind !== TokenKind.Greater && kind !== TokenKind.GreaterEq) return left;
    this.pos += 1;
    const right = this.parseAdditive();
    const next = this.peek().kind;
    if (next === TokenKind.EqEq || next === TokenKind.BangEq || next === TokenKind.Less ||
        next === TokenKind.LessEq || next === TokenKind.Greater || next === TokenKind.GreaterEq) {
      fail('parser: chained comparisons are not supported in bootstrap control flow');
    }
    return {kind:'Compare', op:kind, left:left, right:right};
  }
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
  looksLikeTypeArgumentCall() {
    if (this.peek().kind !== TokenKind.Less) return false;
    let depth = 0;
    let sawType = false;
    for (let offset = 0; offset < MAX_SEMNEXIS_TYPE_DEPTH * 8; offset += 1) {
      const kind = this.peek(offset).kind;
      if (kind === TokenKind.Less) {
        depth += 1;
        if (depth > MAX_SEMNEXIS_TYPE_DEPTH) return false;
      } else if (kind === TokenKind.Greater) {
        depth -= 1;
        if (depth < 0) return false;
        if (depth === 0) return sawType && this.peek(offset + 1).kind === TokenKind.LParen;
      } else if (kind === TokenKind.Identifier) {
        sawType = true;
      } else if (kind === TokenKind.Comma && depth > 0) {
        continue;
      } else {
        return false;
      }
    }
    return false;
  }
  parseCallTypeArguments() {
    if (!this.looksLikeTypeArgumentCall()) return [];
    this.expect(TokenKind.Less, "expected '<' before call type arguments");
    const args = [];
    for (;;) {
      args.push(this.parseTypeReference());
      if (!this.match(TokenKind.Comma)) break;
    }
    this.expect(TokenKind.Greater, "expected '>' after call type arguments");
    return args;
  }
  parsePrimary() {
    let expression = null;
    if (this.match(TokenKind.LParen)) {
      expression = this.parseExpr();
      this.expect(TokenKind.RParen, "expected ')' after expression");
    } else if (this.peek().kind === TokenKind.Integer) {
      const token = this.tokens[this.pos++];
      const integer = Number(token.lexeme);
      if (!Number.isSafeInteger(integer) || integer > 2147483647) fail('parser: integer literal is outside signed i32 range');
      expression = {kind:'Integer', integer:integer};
    } else if (this.peek().kind === TokenKind.Identifier) {
      const token = this.tokens[this.pos++];
      const typeArguments = this.parseCallTypeArguments();
      if (this.match(TokenKind.LParen)) {
        const args = [];
        if (this.peek().kind !== TokenKind.RParen) {
          for (;;) {
            args.push(this.parseExpr());
            if (!this.match(TokenKind.Comma)) break;
          }
        }
        this.expect(TokenKind.RParen, "expected ')' after call arguments");
        expression = {kind:'Call', name:token.lexeme, typeArguments:typeArguments, arguments:args};
      } else if (this.peek().kind === TokenKind.LBrace && this.peek(1).kind === TokenKind.Identifier && this.peek(2).kind === TokenKind.Colon) {
        this.pos += 1;
        const fields = [];
        const names = new Set();
        if (this.peek().kind !== TokenKind.RBrace) {
          for (;;) {
            if (fields.length >= MAX_SEMNEXIS_STRUCT_FIELDS) fail("parser: record literal '" + token.lexeme + "' exceeds field budget");
            const fieldName = this.expect(TokenKind.Identifier, 'expected record field name').lexeme;
            if (names.has(fieldName)) fail("parser: duplicate record field '" + fieldName + "'");
            names.add(fieldName);
            this.expect(TokenKind.Colon, "expected ':' after record field name");
            fields.push({name:fieldName, value:this.parseExpr()});
            if (!this.match(TokenKind.Comma)) break;
          }
        }
        this.expect(TokenKind.RBrace, "expected '}' after record literal");
        expression = {kind:'RecordLiteral', typeName:token.lexeme, fields:fields};
      } else {
        expression = {kind:'Name', name:token.lexeme};
      }
    } else {
      const token = this.peek();
      fail('parser: expected expression at ' + token.line + ':' + token.column + ", found '" + token.lexeme + "'");
    }

    while (this.match(TokenKind.Dot)) {
      const field = this.expect(TokenKind.Identifier, "expected field name after '.'");
      expression = {kind:'FieldAccess', base:expression, field:field.lexeme};
    }
    return expression;
  }
}

function validateSemnexisAstBudgets(module) {
  const roots = [];
  for (const fn of module.functions) {
    for (const local of fn.locals) roots.push(local.initializer);
    roots.push(fn.returnExpr);
  }

  let nodeCount = 0;
  const stack = roots.map((expr) => ({expr:expr, depth:1}));
  while (stack.length) {
    const row = stack.pop();
    const expr = row.expr;
    if (!expr || typeof expr !== 'object') fail('compiler: malformed AST expression');
    nodeCount += 1;
    if (nodeCount > MAX_SEMNEXIS_TOKENS) fail('compiler: AST node count exceeds compiler budget');
    if (row.depth > MAX_SEMNEXIS_EXPRESSION_DEPTH) fail('compiler: AST structural depth exceeds compiler budget');

    const nextDepth = row.depth + 1;
    if (expr.kind === 'Binary' || expr.kind === 'Compare') {
      stack.push({expr:expr.left, depth:nextDepth});
      stack.push({expr:expr.right, depth:nextDepth});
    } else if (expr.kind === 'IfExpr') {
      stack.push({expr:expr.condition, depth:nextDepth});
      stack.push({expr:expr.thenExpr, depth:nextDepth});
      stack.push({expr:expr.elseExpr, depth:nextDepth});
    } else if (expr.kind === 'LoopExpr') {
      for (const state of expr.states) stack.push({expr:state.initializer, depth:nextDepth});
      stack.push({expr:expr.condition, depth:nextDepth});
      for (const value of expr.nextValues) stack.push({expr:value, depth:nextDepth});
      stack.push({expr:expr.yieldExpr, depth:nextDepth});
    } else if (expr.kind === 'Call') {
      for (const arg of expr.arguments) stack.push({expr:arg, depth:nextDepth});
    } else if (expr.kind === 'RecordLiteral') {
      for (const field of expr.fields) stack.push({expr:field.value, depth:nextDepth});
    } else if (expr.kind === 'FieldAccess') {
      stack.push({expr:expr.base, depth:nextDepth});
    } else if (expr.kind !== 'Integer' && expr.kind !== 'Name') {
      fail("compiler: unknown AST expression kind '" + String(expr.kind) + "'");
    }
  }
}

function isExpressionKind(kind) {
  return kind === NodeKind.Constant || kind === NodeKind.NameRef ||
    kind === NodeKind.Binary || kind === NodeKind.Convert || kind === NodeKind.Call || kind === NodeKind.Record || kind === NodeKind.Field || kind === NodeKind.Conditional || kind === NodeKind.Loop;
}

function astExpressionUsesControlFlow(expr) {
  if (!expr || typeof expr !== 'object') return false;
  if (expr.kind === 'IfExpr' || expr.kind === 'Compare' || expr.kind === 'LoopExpr') return true;
  if (expr.kind === 'Binary') return astExpressionUsesControlFlow(expr.left) || astExpressionUsesControlFlow(expr.right);
  if (expr.kind === 'Call') return expr.arguments.some(astExpressionUsesControlFlow);
  if (expr.kind === 'RecordLiteral') return expr.fields.some((field) => astExpressionUsesControlFlow(field.value));
  if (expr.kind === 'FieldAccess') return astExpressionUsesControlFlow(expr.base);
  return false;
}

class ProgramGraph {
  constructor() { this.nodes = []; this.edges = []; this.edgeIndex = new Map(); }
  addNode(kind, name) {
    if (this.nodes.length >= MAX_SEMNEXIS_GRAPH_NODES) fail('graph: node count exceeds compiler budget');
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
    if (this.edges.length >= MAX_SEMNEXIS_GRAPH_EDGES) fail('graph: edge count exceeds compiler budget');
    const edge = {from:from, to:to, relation:String(relation)};
    this.edges.push(edge);
    const rows = this.edgeIndex.get(from) || [];
    rows.push(edge);
    this.edgeIndex.set(from, rows);
  }
  edgesFrom(from, relation) {
    const rows = this.edgeIndex.get(from) || [];
    if (relation == null) return rows.slice();
    return rows.filter(function(edge) { return edge.relation === relation; });
  }
  singleEdgeTarget(from, relation) {
    const rows = this.edgesFrom(from, relation);
    if (rows.length !== 1) fail("graph: expected exactly one '" + relation + "' edge");
    return rows[0].to;
  }
  hasEdgeToKind(from, relation, kind) {
    return this.edgesFrom(from, relation).some((edge) => this.nodes[edge.to].kind === kind);
  }
  countEdges(from, relation) { return this.edgesFrom(from, relation).length; }
  attribute(node, key) {
    const row = this.nodes[node].attributes.find(function(pair) { return pair[0] === key; });
    return row ? row[1] : null;
  }
  verify() {
    if (!this.nodes.length) fail('graph verify: graph is empty');
    if (this.nodes.length > MAX_SEMNEXIS_GRAPH_NODES) fail('graph verify: node count exceeds compiler budget');
    if (this.edges.length > MAX_SEMNEXIS_GRAPH_EDGES) fail('graph verify: edge count exceeds compiler budget');
    this.edgeIndex = new Map();
    for (const edge of this.edges) {
      const rows = this.edgeIndex.get(edge.from) || [];
      rows.push(edge);
      this.edgeIndex.set(edge.from, rows);
    }
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
        if (sourceKind !== NodeKind.NameRef || (targetKind !== NodeKind.Parameter && targetKind !== NodeKind.Local && targetKind !== NodeKind.LoopState)) fail('graph verify: invalid resolves_to edge');
      } else if (relation === 'initialized_by') {
        if ((sourceKind !== NodeKind.Local && sourceKind !== NodeKind.LoopState) || !isExpressionKind(targetKind)) fail('graph verify: invalid initialized_by edge');
      } else if (relation === 'next_value') {
        if (sourceKind !== NodeKind.LoopState || !isExpressionKind(targetKind)) fail('graph verify: invalid loop next_value edge');
      } else if (relation === 'carries_state') {
        if (sourceKind !== NodeKind.Loop || targetKind !== NodeKind.LoopState) fail('graph verify: invalid carries_state edge');
      } else if (relation === 'returns_value') {
        if (sourceKind !== NodeKind.Return || !isExpressionKind(targetKind)) fail('graph verify: invalid returns_value edge');
      } else if (relation === 'contains_expr') {
        if (sourceKind !== NodeKind.Function || !isExpressionKind(targetKind)) fail('graph verify: invalid contains_expr edge');
      } else if (relation === 'lhs' || relation === 'rhs') {
        if (sourceKind !== NodeKind.Binary || !isExpressionKind(targetKind)) fail('graph verify: invalid binary operand edge');
      } else if (relation === 'operand') {
        if (sourceKind !== NodeKind.Convert || !isExpressionKind(targetKind)) fail('graph verify: invalid conversion operand edge');
      } else if (relation === 'condition') {
        if ((sourceKind !== NodeKind.Conditional && sourceKind !== NodeKind.Loop) || !isExpressionKind(targetKind)) fail('graph verify: invalid control-flow condition edge');
      } else if (relation === 'yield_value') {
        if (sourceKind !== NodeKind.Loop || !isExpressionKind(targetKind)) fail('graph verify: invalid loop yield edge');
      } else if (relation === 'then_value' || relation === 'else_value') {
        if (sourceKind !== NodeKind.Conditional || !isExpressionKind(targetKind)) fail('graph verify: invalid conditional value edge');
      } else if (/^arg[0-9]+$/.test(relation)) {
        if (sourceKind !== NodeKind.Call || !isExpressionKind(targetKind)) fail('graph verify: invalid call argument edge');
      } else if (relation.indexOf('arg') === 0) {
        fail("graph verify: malformed call argument relation '" + relation + "'");
      } else if (relation === 'base') {
        if (sourceKind !== NodeKind.Field || !isExpressionKind(targetKind)) fail('graph verify: invalid field projection base edge');
      } else if (/^field[0-9]+$/.test(relation)) {
        if (sourceKind !== NodeKind.Record || !isExpressionKind(targetKind)) fail('graph verify: invalid record field edge');
      } else if (relation.indexOf('field') === 0) {
        fail("graph verify: malformed record field relation '" + relation + "'");
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

      if (node.kind === NodeKind.Type && this.attribute(node.id, 'kind') === 'record') {
        const fieldCount = Number(this.attribute(node.id, 'field_count'));
        if (!Number.isInteger(fieldCount) || fieldCount < 1 || fieldCount > MAX_SEMNEXIS_FLAT_RECORD_WORDS) {
          fail("graph verify: record type '" + node.name + "' has invalid field count");
        }
        const fieldNames = new Set();
        for (let i = 0; i < fieldCount; i += 1) {
          const fieldName = this.attribute(node.id, 'field' + i + '_name');
          const fieldType = this.attribute(node.id, 'field' + i + '_type');
          if (!fieldName || fieldNames.has(fieldName)) fail("graph verify: record type '" + node.name + "' has invalid field names");
          if (!isBootstrapScalarType(fieldType)) fail("graph verify: record type '" + node.name + "' has unsupported field type");
          fieldNames.add(fieldName);
        }
      }

      if (node.kind === NodeKind.Parameter) {
        if (!this.hasEdgeToKind(node.id, 'has_type', NodeKind.Type) || this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: parameter '" + node.name + "' must have exactly one type");
      }

      if (node.kind === NodeKind.Local) {
        if (!this.hasEdgeToKind(node.id, 'has_type', NodeKind.Type) || this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: local '" + node.name + "' must have exactly one inferred type");
        if (this.countEdges(node.id, 'initialized_by') !== 1) fail("graph verify: local '" + node.name + "' must have exactly one initializer");
      }

      if (node.kind === NodeKind.LoopState) {
        if (this.countEdges(node.id, 'has_type') !== 1 || this.countEdges(node.id, 'initialized_by') !== 1 || this.countEdges(node.id, 'next_value') !== 1) {
          fail("graph verify: loop state '" + node.name + "' must have type, initializer and next value");
        }
        const stateTypeNodeId = this.singleEdgeTarget(node.id, 'has_type');
        const stateTypeNode = this.nodes[stateTypeNodeId];
        const stateType = stateTypeNode.name;
        if (stateType !== 'i32' && !(stateTypeNode.kind === NodeKind.Type && this.attribute(stateTypeNodeId, 'kind') === 'record')) {
          fail("graph verify: loop state '" + node.name + "' must be i32 or a flat record");
        }
        const initializer = this.singleEdgeTarget(node.id, 'initialized_by');
        const nextValue = this.singleEdgeTarget(node.id, 'next_value');
        if (this.singleEdgeTarget(initializer, 'has_type') !== stateTypeNodeId ||
            this.singleEdgeTarget(nextValue, 'has_type') !== stateTypeNodeId) {
          fail("graph verify: loop state '" + node.name + "' initializer/next type mismatch");
        }
      }

      if (node.kind === NodeKind.NameRef) {
        if (this.countEdges(node.id, 'resolves_to') !== 1 || this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: name reference '" + node.name + "' must resolve and type exactly once");
        const target = this.singleEdgeTarget(node.id, 'resolves_to');
        if (this.singleEdgeTarget(node.id, 'has_type') !== this.singleEdgeTarget(target, 'has_type')) fail("graph verify: name reference '" + node.name + "' type does not match resolved symbol");
      }

      if (node.kind === NodeKind.Convert) {
        if (this.countEdges(node.id, 'operand') !== 1 || this.countEdges(node.id, 'has_type') !== 1) {
          fail('graph verify: conversion must have one operand and one result type');
        }
        if (this.attribute(node.id, 'conversion') !== 'u8_to_i32') fail('graph verify: unsupported conversion kind');
        const operand = this.singleEdgeTarget(node.id, 'operand');
        const operandType = this.nodes[this.singleEdgeTarget(operand, 'has_type')].name;
        const resultType = this.nodes[this.singleEdgeTarget(node.id, 'has_type')].name;
        if (operandType !== 'u8' || resultType !== 'i32') fail('graph verify: malformed u8_to_i32 conversion');
      }

      if (node.kind === NodeKind.Record) {
        if (this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: record literal '" + node.name + "' must have exactly one type");
        const typeNode = this.nodes[this.singleEdgeTarget(node.id, 'has_type')];
        if (typeNode.kind !== NodeKind.Type || this.attribute(typeNode.id, 'kind') !== 'record' || typeNode.name !== node.name) {
          fail("graph verify: record literal '" + node.name + "' type contract mismatch");
        }
        const fieldCount = Number(this.attribute(typeNode.id, 'field_count'));
        const recordFieldEdges = this.edgesFrom(node.id).filter((edge) => /^field[0-9]+$/.test(edge.relation));
        if (recordFieldEdges.length !== fieldCount) fail("graph verify: record literal '" + node.name + "' field count mismatch");
        for (let i = 0; i < fieldCount; i += 1) {
          if (this.countEdges(node.id, 'field' + i) !== 1) fail("graph verify: record literal '" + node.name + "' missing field " + i);
          const value = this.singleEdgeTarget(node.id, 'field' + i);
          const expectedType = this.attribute(typeNode.id, 'field' + i + '_type');
          const actualType = this.nodes[this.singleEdgeTarget(value, 'has_type')].name;
          if (actualType !== expectedType) fail("graph verify: record literal '" + node.name + "' field type mismatch at index " + i);
        }
      }

      if (node.kind === NodeKind.Field) {
        if (this.countEdges(node.id, 'base') !== 1 || this.countEdges(node.id, 'has_type') !== 1) {
          fail("graph verify: field projection '" + node.name + "' must have one base and one type");
        }
        const base = this.singleEdgeTarget(node.id, 'base');
        const baseTypeNode = this.nodes[this.singleEdgeTarget(base, 'has_type')];
        if (baseTypeNode.kind !== NodeKind.Type || this.attribute(baseTypeNode.id, 'kind') !== 'record') {
          fail("graph verify: field projection '" + node.name + "' base is not a record");
        }
        const recordType = this.attribute(node.id, 'record_type');
        const fieldName = this.attribute(node.id, 'field_name');
        const fieldIndex = Number(this.attribute(node.id, 'field_index'));
        const fieldCount = Number(this.attribute(baseTypeNode.id, 'field_count'));
        if (recordType !== baseTypeNode.name || fieldName !== node.name || !Number.isInteger(fieldIndex) || fieldIndex < 0 || fieldIndex >= fieldCount) {
          fail("graph verify: field projection '" + node.name + "' metadata mismatch");
        }
        if (this.attribute(baseTypeNode.id, 'field' + fieldIndex + '_name') !== fieldName) {
          fail("graph verify: field projection '" + node.name + "' field name mismatch");
        }
        const expectedType = this.attribute(baseTypeNode.id, 'field' + fieldIndex + '_type');
        const actualType = this.nodes[this.singleEdgeTarget(node.id, 'has_type')].name;
        if (actualType !== expectedType) fail("graph verify: field projection '" + node.name + "' result type mismatch");
      }

      if (node.kind === NodeKind.Binary) {
        if (this.countEdges(node.id, 'lhs') !== 1 || this.countEdges(node.id, 'rhs') !== 1 || this.countEdges(node.id, 'has_type') !== 1) {
          fail("graph verify: binary '" + node.name + "' must have two operands and one type");
        }
        const operation = this.attribute(node.id, 'operation');
        const comparison = operation === 'EqEq' || operation === 'BangEq' || operation === 'Less' || operation === 'LessEq' || operation === 'Greater' || operation === 'GreaterEq';
        const typeName = this.nodes[this.singleEdgeTarget(node.id, 'has_type')].name;
        const lhs = this.singleEdgeTarget(node.id, 'lhs');
        const rhs = this.singleEdgeTarget(node.id, 'rhs');
        const lhsType = this.nodes[this.singleEdgeTarget(lhs, 'has_type')].name;
        const rhsType = this.nodes[this.singleEdgeTarget(rhs, 'has_type')].name;
        if (comparison && typeName !== 'bool') fail('graph verify: comparison result must be bool');
        if (comparison && (!isBootstrapScalarType(lhsType) || lhsType !== rhsType)) fail('graph verify: comparison operands must have the same scalar type');
        if (!comparison && (typeName !== 'i32' || lhsType !== 'i32' || rhsType !== 'i32')) fail('graph verify: arithmetic operands/result must be i32');
      }

      if (node.kind === NodeKind.Conditional) {
        if (this.countEdges(node.id, 'condition') !== 1 || this.countEdges(node.id, 'then_value') !== 1 ||
            this.countEdges(node.id, 'else_value') !== 1 || this.countEdges(node.id, 'has_type') !== 1) {
          fail('graph verify: conditional must have one condition, two values and one type');
        }
        const condition = this.singleEdgeTarget(node.id, 'condition');
        const thenValue = this.singleEdgeTarget(node.id, 'then_value');
        const elseValue = this.singleEdgeTarget(node.id, 'else_value');
        const conditionType = this.nodes[this.singleEdgeTarget(condition, 'has_type')].name;
        const resultType = this.singleEdgeTarget(node.id, 'has_type');
        if (conditionType !== 'bool') fail('graph verify: conditional condition must be bool');
        if (this.singleEdgeTarget(thenValue, 'has_type') !== resultType || this.singleEdgeTarget(elseValue, 'has_type') !== resultType) {
          fail('graph verify: conditional branch types must match result type');
        }
      }

      if (node.kind === NodeKind.Loop) {
        if (this.countEdges(node.id, 'condition') !== 1 || this.countEdges(node.id, 'yield_value') !== 1 ||
            this.countEdges(node.id, 'has_type') !== 1 || this.countEdges(node.id, 'carries_state') < 1) {
          fail('graph verify: loop must have state, condition, yield and type');
        }
        const condition = this.singleEdgeTarget(node.id, 'condition');
        if (this.nodes[this.singleEdgeTarget(condition, 'has_type')].name !== 'bool') fail('graph verify: loop condition must be bool');
        const resultType = this.singleEdgeTarget(node.id, 'has_type');
        const yieldValue = this.singleEdgeTarget(node.id, 'yield_value');
        if (this.singleEdgeTarget(yieldValue, 'has_type') !== resultType) fail('graph verify: loop yield type mismatch');
        const names = new Set();
        for (const edge of this.edgesFrom(node.id, 'carries_state')) {
          const state = this.nodes[edge.to];
          if (names.has(state.name)) fail("graph verify: duplicate loop state '" + state.name + "'");
          names.add(state.name);
        }
      }

      if (node.kind === NodeKind.Call) {
        if (this.countEdges(node.id, 'calls') !== 1 || this.countEdges(node.id, 'has_type') !== 1) fail("graph verify: call '" + node.name + "' must resolve and type exactly once");
        const target = this.singleEdgeTarget(node.id, 'calls');
        const targetNode = this.nodes[target];
        let arity = 0;
        let expectedTypes = [];
        let expectedKinds = [];
        if (targetNode.kind === NodeKind.Intrinsic) {
          arity = Number(this.attribute(target, 'arity'));
          expectedTypes = [];
          expectedKinds = [];
          for (let i = 0; i < arity; i += 1) {
            const expectedType = this.attribute(target, 'param' + i + '_type');
            const expectedKind = this.attribute(target, 'param' + i + '_kind');
            if (!expectedType && !expectedKind) fail("graph verify: intrinsic '" + targetNode.name + "' missing parameter contract");
            expectedTypes.push(expectedType || null);
            expectedKinds.push(expectedKind || null);
          }
        } else {
          const parameters = this.edgesFrom(target, 'contains')
            .map((edge) => this.nodes[edge.to])
            .filter((child) => child.kind === NodeKind.Parameter);
          arity = parameters.length;
          expectedTypes = parameters.map((parameter) => this.nodes[this.singleEdgeTarget(parameter.id, 'has_type')].name);
        }
        const targetReturnType = this.singleEdgeTarget(target, 'returns_type');
        if (this.singleEdgeTarget(node.id, 'has_type') !== targetReturnType) fail("graph verify: call '" + node.name + "' result type does not match target");
        const argEdges = this.edgesFrom(node.id).filter((edge) => /^arg[0-9]+$/.test(edge.relation));
        if (argEdges.length !== arity) fail("graph verify: call '" + node.name + "' argument count does not match target");
        const orderedArgs = argEdges.slice().sort((a,b) => Number(a.relation.slice(3)) - Number(b.relation.slice(3)));
        for (let i = 0; i < orderedArgs.length; i += 1) {
          if (Number(orderedArgs[i].relation.slice(3)) !== i) fail("graph verify: call '" + node.name + "' argument indexes are not contiguous");
          const argTypeNode = this.singleEdgeTarget(orderedArgs[i].to, 'has_type');
          const argType = this.nodes[argTypeNode].name;
          if (expectedTypes[i] != null) {
            if (argType !== expectedTypes[i]) fail("graph verify: call '" + node.name + "' argument type mismatch at index " + i);
          } else if (expectedKinds[i] === 'flat_record') {
            if (this.attribute(argTypeNode, 'kind') !== 'record') fail("graph verify: call '" + node.name + "' argument " + i + ' must be a flat record');
          } else fail("graph verify: call '" + node.name + "' has unsupported parameter contract at index " + i);
        }
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
  dump(maxChars) {
    const limit = maxChars == null ? Infinity : maxChars;
    const out = [];
    let chars = 0;
    const push = line => {
      chars += line.length + 1;
      if (chars > limit) fail('graph dump exceeds output budget');
      out.push(line);
    };
    push(SEMNEXIS_GRAPH_SCHEMA);
    for (const node of this.nodes) {
      let line = 'node ' + node.id + ' ' + node.kind + ' ' + node.name;
      for (const pair of node.attributes) line += ' ' + pair[0] + '=' + pair[1];
      push(line);
    }
    for (const edge of this.edges) push('edge ' + edge.from + ' -> ' + edge.to + ' ' + edge.relation);
    return out.join('\n') + '\n';
  }
}

class ExecutionPlan {
  constructor() { this.steps = []; }
  add(action, detail) { this.steps.push({index:this.steps.length, action:String(action), detail:String(detail)}); }
  dump(maxChars) {
    const limit = maxChars == null ? Infinity : maxChars;
    const out = [];
    let chars = 0;
    const push = line => {
      chars += line.length + 1;
      if (chars > limit) fail('plan dump exceeds output budget');
      out.push(line);
    };
    push(SEMNEXIS_PLAN_SCHEMA);
    for (const step of this.steps) push('step ' + step.index + ' ' + step.action + ' ' + step.detail);
    return out.join('\n') + '\n';
  }
}


function verifyNativeIRControlFlow(fn) {
  const blockBegins = fn.instructions.filter((inst) => inst.op === 'block.begin');
  if (!blockBegins.length) return;
  if (blockBegins.length > MAX_SEMNEXIS_CFG_BLOCKS) fail("ir verify: function '" + fn.name + "' exceeds CFG block budget");
  if (fn.instructions.length < 4 || fn.instructions[0].op !== 'region.begin' || fn.instructions[1].op !== 'block.begin') {
    fail("ir verify: control-flow function '" + fn.name + "' must begin region then entry block");
  }

  const blocks = new Map();
  const order = [];
  const instructionBlock = new Map();
  let current = null;
  for (const inst of fn.instructions) {
    if (inst.op === 'region.begin') {
      if (inst.index !== 0) fail("ir verify: region.begin position invalid in '" + fn.name + "'");
      continue;
    }
    if (inst.op === 'block.begin') {
      if (!inst.label || blocks.has(inst.label)) fail("ir verify: duplicate or empty block label in '" + fn.name + "'");
      current = {label:inst.label, begin:inst.index, instructions:[]};
      blocks.set(inst.label, current);
      order.push(inst.label);
      instructionBlock.set(inst.index, inst.label);
      continue;
    }
    if (!current) fail("ir verify: instruction outside basic block in '" + fn.name + "'");
    current.instructions.push(inst);
    instructionBlock.set(inst.index, current.label);
  }

  const predecessors = new Map(order.map((label) => [label, new Set()]));
  const successors = new Map(order.map((label) => [label, new Set()]));
  const branchOps = new Set(['br.cmp.eq','br.cmp.ne','br.cmp.lt','br.cmp.le','br.cmp.gt','br.cmp.ge']);
  const returnOps = new Set(['ret.i32','ret.u8','ret.record']);

  const addEdge = (from, to) => {
    if (!blocks.has(to)) fail("ir verify: branch target '" + to + "' does not exist in '" + fn.name + "'");
    successors.get(from).add(to);
    predecessors.get(to).add(from);
  };

  for (const label of order) {
    const block = blocks.get(label);
    if (!block.instructions.length) fail("ir verify: empty basic block '" + label + "'");
    const terminator = block.instructions[block.instructions.length - 1];
    if (terminator.op !== 'br' && !branchOps.has(terminator.op) && !returnOps.has(terminator.op)) {
      fail("ir verify: basic block '" + label + "' has no terminator");
    }
    for (let i = 0; i < block.instructions.length - 1; i += 1) {
      const op = block.instructions[i].op;
      if (op === 'br' || branchOps.has(op) || returnOps.has(op)) {
        fail("ir verify: terminator appears before end of block '" + label + "'");
      }
    }
    if (terminator.op === 'br') addEdge(label, terminator.target);
    else if (branchOps.has(terminator.op)) {
      addEdge(label, terminator.thenLabel);
      addEdge(label, terminator.elseLabel);
      if (terminator.thenLabel === terminator.elseLabel) fail("ir verify: conditional branch targets must differ in '" + label + "'");
    }
  }

  const entry = order[0];
  if (predecessors.get(entry).size !== 0) fail("ir verify: entry block '" + entry + "' cannot have predecessors");
  const reachable = new Set([entry]);
  const queue = [entry];
  while (queue.length) {
    const label = queue.shift();
    for (const next of successors.get(label)) {
      if (!reachable.has(next)) { reachable.add(next); queue.push(next); }
    }
  }
  if (reachable.size !== order.length) fail("ir verify: unreachable basic block in '" + fn.name + "'");

  for (const label of order) {
    const block = blocks.get(label);
    let seenNonPhi = false;
    const phis = block.instructions.filter((inst) => inst.op === 'phi.i32' || inst.op === 'phi.record');
    for (const inst of block.instructions) {
      if (inst.op === 'phi.i32' || inst.op === 'phi.record') {
        if (seenNonPhi) fail("ir verify: phi must appear before ordinary instructions in block '" + label + "'");
      } else if (inst.op !== 'region.end') seenNonPhi = true;
    }
    for (const phi of phis) {
      if (!Array.isArray(phi.incoming) || phi.incoming.length < 2 || phi.incoming.length !== phi.args.length) {
        fail("ir verify: malformed phi incoming list in block '" + label + "'");
      }
      const incomingLabels = phi.incoming.map((row) => row.label);
      if (new Set(incomingLabels).size !== incomingLabels.length) fail("ir verify: duplicate phi predecessor in block '" + label + "'");
      const pred = predecessors.get(label);
      if (pred.size !== incomingLabels.length || incomingLabels.some((name) => !pred.has(name))) {
        fail("ir verify: phi predecessors do not match CFG predecessors in block '" + label + "'");
      }
      for (let i = 0; i < phi.incoming.length; i += 1) {
        if (phi.incoming[i].value !== phi.args[i]) fail("ir verify: phi args/incoming mismatch in block '" + label + "'");
      }
    }
  }

  const allLabels = new Set(order);
  const dominators = new Map();
  for (const label of order) dominators.set(label, label === entry ? new Set([entry]) : new Set(allLabels));
  let changed = true;
  while (changed) {
    changed = false;
    for (const label of order) {
      if (label === entry) continue;
      const pred = Array.from(predecessors.get(label));
      if (!pred.length) fail("ir verify: non-entry block '" + label + "' has no predecessor");
      let next = new Set(dominators.get(pred[0]));
      for (let i = 1; i < pred.length; i += 1) {
        const other = dominators.get(pred[i]);
        next = new Set(Array.from(next).filter((value) => other.has(value)));
      }
      next.add(label);
      const previous = dominators.get(label);
      if (previous.size !== next.size || Array.from(previous).some((value) => !next.has(value))) {
        dominators.set(label, next);
        changed = true;
      }
    }
  }

  const definitions = new Map();
  for (const parameter of fn.parameters) definitions.set(parameter.value, {block:null, index:-1});
  for (const inst of fn.instructions) {
    if (inst.result != null) definitions.set(inst.result, {block:instructionBlock.get(inst.index), index:inst.index});
  }

  for (const label of order) {
    const block = blocks.get(label);
    for (const inst of block.instructions) {
      if (inst.op === 'phi.i32' || inst.op === 'phi.record') {
        for (const incoming of inst.incoming) {
          const def = definitions.get(incoming.value);
          if (!def) fail("ir verify: phi uses undefined value '" + incoming.value + "'");
          if (def.block != null && !dominators.get(incoming.label).has(def.block)) {
            fail("ir verify: phi value '" + incoming.value + "' does not dominate predecessor '" + incoming.label + "'");
          }
        }
        continue;
      }
      for (const arg of inst.args || []) {
        const def = definitions.get(arg);
        if (!def) fail("ir verify: use of undefined value '" + arg + "'");
        if (def.block != null && def.block !== label && !dominators.get(label).has(def.block)) {
          fail("ir verify: value '" + arg + "' does not dominate use in block '" + label + "'");
        }
      }
    }
  }
}

function verifyNativeIREffectsAndCapabilities(functions) {
  const byName = new Map(functions.map((fn) => [fn.name, fn]));
  const directTime = new Map();
  const directState = new Map();
  const callers = new Map();

  for (const fn of functions) callers.set(fn.name, []);
  for (const fn of functions) {
    let hasDirectTime = false;
    let hasDirectState = false;
    for (const inst of fn.instructions) {
      if (inst.op === 'intrinsic.clock') hasDirectTime = true;
      if (inst.op === 'arena.load.record' || inst.op === 'arena.store.record') hasDirectState = true;
      if (inst.op === 'call') {
        if (!byName.has(inst.target)) fail("ir verify: unresolved effect call target '" + inst.target + "'");
        callers.get(inst.target).push(fn.name);
      }
    }
    directTime.set(fn.name, hasDirectTime);
    directState.set(fn.name, hasDirectState);
  }

  const propagateEffect = (direct) => {
    const transitive = new Map(functions.map((fn) => [fn.name, false]));
    const queue = [];
    for (const fn of functions) {
      if (!direct.get(fn.name)) continue;
      transitive.set(fn.name, true);
      queue.push(fn.name);
    }
    for (let cursor = 0; cursor < queue.length; cursor += 1) {
      const target = queue[cursor];
      for (const caller of callers.get(target)) {
        if (transitive.get(caller)) continue;
        transitive.set(caller, true);
        queue.push(caller);
      }
    }
    return transitive;
  };

  const transitivelyTime = propagateEffect(directTime);
  const transitivelyState = propagateEffect(directState);

  const requiresTime = new Map(functions.map((fn) => [fn.name, false]));
  const requirementQueue = [];
  for (const fn of functions) {
    if (!directTime.get(fn.name) || fn.grantsCapabilities.includes('time')) continue;
    requiresTime.set(fn.name, true);
    requirementQueue.push(fn.name);
  }
  for (let cursor = 0; cursor < requirementQueue.length; cursor += 1) {
    const target = requirementQueue[cursor];
    for (const callerName of callers.get(target)) {
      if (requiresTime.get(callerName)) continue;
      const caller = byName.get(callerName);
      if (caller.grantsCapabilities.includes('time')) continue;
      requiresTime.set(callerName, true);
      requirementQueue.push(callerName);
    }
  }

  for (const fn of functions) {
    const hasTime = transitivelyTime.get(fn.name);
    const hasState = transitivelyState.get(fn.name);
    const expectedEffect = hasTime ? (hasState ? 'time_state' : 'time') : (hasState ? 'state' : 'pure');
    if (fn.effect !== expectedEffect) {
      fail("ir verify: function '" + fn.name + "' effect metadata mismatch; expected " + expectedEffect + ', got ' + fn.effect);
    }

    const expectedRequires = requiresTime.get(fn.name) ? ['time'] : [];
    const actualRequires = Array.from(new Set(fn.requiresCapabilities)).sort();
    if (actualRequires.length !== expectedRequires.length ||
        actualRequires.some((capability, index) => capability !== expectedRequires[index])) {
      fail("ir verify: function '" + fn.name + "' capability requirements do not match derived effects");
    }

    if (requiresTime.get(fn.name) && fn.name === 'main') {
      fail("ir verify: entry function 'main' uses time but does not grant time capability");
    }
  }
}

class NativeIRModule {
  constructor() {
    this.schema = SEMNEXIS_NATIVE_IR_SCHEMA;
    this.recordTypes = [];
    this.functions = [];
  }
  addRecordType(record) {
    this.recordTypes.push(record);
    return record;
  }
  addFunction(fn) {
    this.functions.push(fn);
    return fn;
  }
  verify() {
    if (this.schema !== SEMNEXIS_NATIVE_IR_SCHEMA) fail('ir verify: schema mismatch');
    if (!Array.isArray(this.recordTypes)) fail('ir verify: malformed record type table');
    if (this.recordTypes.length > MAX_NATIVE_IR_RECORD_TYPES) fail('ir verify: module exceeds record type budget');
    if (!this.functions.length) fail('ir verify: module has no functions');
    if (this.functions.length > MAX_NATIVE_IR_FUNCTIONS) fail('ir verify: module exceeds function budget');

    const recordByName = new Map();
    for (const record of this.recordTypes) {
      if (!record || typeof record !== 'object' || !record.name || recordByName.has(record.name) || !Array.isArray(record.fields)) {
        fail('ir verify: malformed or duplicate record type');
      }
      if (record.fields.length < 1 || record.fields.length > MAX_SEMNEXIS_FLAT_RECORD_WORDS) {
        fail("ir verify: record '" + record.name + "' exceeds flat record field limit");
      }
      const fieldNames = new Set();
      for (let i = 0; i < record.fields.length; i += 1) {
        const field = record.fields[i];
        if (!field || field.index !== i || !field.name || fieldNames.has(field.name) || !isBootstrapScalarType(field.type)) {
          fail("ir verify: malformed field metadata in record '" + record.name + "'");
        }
        fieldNames.add(field.name);
      }
      recordByName.set(record.name, record);
    }

    const byName = new Map();
    for (const fn of this.functions) {
      if (!fn || typeof fn !== 'object') fail('ir verify: malformed function');
      if (!Array.isArray(fn.parameters) || !Array.isArray(fn.instructions) ||
          !Array.isArray(fn.requiresCapabilities) || !Array.isArray(fn.grantsCapabilities)) {
        fail('ir verify: malformed function collections');
      }
      if (fn.parameters.length > MAX_NATIVE_IR_PARAMETERS) fail("ir verify: function '" + fn.name + "' exceeds parameter budget");
      if (fn.instructions.length > MAX_NATIVE_IR_INSTRUCTIONS) fail("ir verify: function '" + fn.name + "' exceeds instruction budget");
      if (!fn.name || byName.has(fn.name)) fail("ir verify: duplicate or empty function '" + String(fn.name || '') + "'");
      byName.set(fn.name, fn);
      if (!isBootstrapScalarType(fn.returnType) && !recordByName.has(fn.returnType)) fail("ir verify: unsupported return type on '" + fn.name + "'");
      if (fn.effect !== 'pure' && fn.effect !== 'time' && fn.effect !== 'state' && fn.effect !== 'time_state') {
        fail("ir verify: unsupported effect on '" + fn.name + "'");
      }
      if (!fn.region) fail("ir verify: function '" + fn.name + "' has no region");
      if (!Number.isInteger(fn.graphNode) || fn.graphNode < 0 || fn.graphNode > 0xFFFFFFFF) fail("ir verify: function '" + fn.name + "' has invalid graph node id");
      if (new Set(fn.requiresCapabilities).size !== fn.requiresCapabilities.length) fail("ir verify: duplicate required capability on '" + fn.name + "'");
      if (new Set(fn.grantsCapabilities).size !== fn.grantsCapabilities.length) fail("ir verify: duplicate granted capability on '" + fn.name + "'");
      for (const cap of fn.requiresCapabilities) if (cap !== 'time') fail("ir verify: unsupported required capability '" + cap + "'");
      for (const cap of fn.grantsCapabilities) if (cap !== 'time') fail("ir verify: unsupported granted capability '" + cap + "'");
      if (fn.name !== 'main' && fn.grantsCapabilities.length) fail("ir verify: only main may grant ambient capability in V0");
    }

    for (const fn of this.functions) {
      const defined = new Set();
      const valueTypes = new Map();
      const localNames = new Set();
      const parameterNames = new Set();
      for (let i = 0; i < fn.parameters.length; i += 1) {
        const parameter = fn.parameters[i];
        if (parameter.index !== i || parameter.value !== '%arg' + i) fail("ir verify: function '" + fn.name + "' has non-canonical parameter identity");
        if (!isBootstrapParameterType(parameter.type) && !recordByName.has(parameter.type)) fail("ir verify: function '" + fn.name + "' has unsupported parameter type");
        if (!parameter.name) fail("ir verify: function '" + fn.name + "' has unnamed parameter");
        if (parameterNames.has(parameter.name)) fail("ir verify: duplicate parameter name '" + parameter.name + "' in '" + fn.name + "'");
        parameterNames.add(parameter.name);
        if (!Number.isInteger(parameter.graphNode) || parameter.graphNode < 0 || parameter.graphNode > 0xFFFFFFFF) fail("ir verify: parameter '" + parameter.name + "' has invalid graph node id");
        if (defined.has(parameter.value)) fail("ir verify: duplicate SSA parameter '" + parameter.value + "'");
        defined.add(parameter.value);
        valueTypes.set(parameter.value, parameter.type);
      }

      let nextValue = 0;
      let regionBegins = 0;
      let regionEnds = 0;
      let returns = 0;

      for (let index = 0; index < fn.instructions.length; index += 1) {
        const inst = fn.instructions[index];
        if (inst.index !== index) fail("ir verify: function '" + fn.name + "' has non-canonical instruction index");
        if (!Number.isInteger(inst.graphNode) || inst.graphNode < 0 || inst.graphNode > 0xFFFFFFFF) fail("ir verify: instruction has invalid graph node id in '" + fn.name + "'");

        const args = Array.isArray(inst.args) ? inst.args : [];
        if (inst.op !== 'phi.i32' && inst.op !== 'phi.record') {
          for (const arg of args) {
            if (typeof arg === 'string' && arg.startsWith('%') && !defined.has(arg)) {
              fail("ir verify: use-before-definition '" + arg + "' in function '" + fn.name + "'");
            }
          }
        }

        if (inst.result != null) {
          const expected = '%v' + nextValue;
          if (inst.result !== expected) fail("ir verify: non-canonical SSA result '" + inst.result + "', expected '" + expected + "'");
          if (defined.has(inst.result)) fail("ir verify: duplicate SSA result '" + inst.result + "'");
          if (!isBootstrapValueType(inst.type) && !recordByName.has(inst.type)) fail("ir verify: SSA result '" + inst.result + "' has unsupported value type");
          defined.add(inst.result);
          valueTypes.set(inst.result, inst.type);
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
          case 'block.begin':
            if (inst.result != null || args.length || !inst.label) fail("ir verify: malformed block.begin in '" + fn.name + "'");
            break;
          case 'br':
            if (inst.result != null || args.length || !inst.target) fail("ir verify: malformed br in '" + fn.name + "'");
            break;
          case 'br.cmp.eq':
          case 'br.cmp.ne':
          case 'br.cmp.lt':
          case 'br.cmp.le':
          case 'br.cmp.gt':
          case 'br.cmp.ge':
            if (inst.result != null || args.length !== 2 || !inst.thenLabel || !inst.elseLabel) fail("ir verify: malformed comparison branch in '" + fn.name + "'");
            if (!isBootstrapScalarType(valueTypes.get(args[0])) || valueTypes.get(args[0]) !== valueTypes.get(args[1])) {
              fail("ir verify: comparison branch operands must have the same scalar type in '" + fn.name + "'");
            }
            break;
          case 'phi.i32':
            if (inst.result == null || inst.type !== 'i32' || args.length < 2) fail("ir verify: malformed phi.i32 in '" + fn.name + "'");
            break;
          case 'phi.record':
            if (inst.result == null || !recordByName.has(inst.type) || args.length < 2) fail("ir verify: malformed phi.record in '" + fn.name + "'");
            break;
          case 'const.i32':
            if (inst.type !== 'i32' || args.length || !Number.isInteger(inst.value) || inst.value < -2147483648 || inst.value > 2147483647) {
              fail("ir verify: invalid i32 constant in '" + fn.name + "'");
            }
            break;
          case 'const.u8':
            if (inst.type !== 'u8' || args.length || !Number.isInteger(inst.value) || inst.value < 0 || inst.value > 255) {
              fail("ir verify: invalid u8 constant in '" + fn.name + "'");
            }
            break;
          case 'copy.i32':
            if (inst.type !== 'i32' || args.length !== 1 || inst.result == null || valueTypes.get(args[0]) !== 'i32') fail("ir verify: malformed copy.i32 in '" + fn.name + "'");
            break;
          case 'copy.u8':
            if (inst.type !== 'u8' || args.length !== 1 || inst.result == null || valueTypes.get(args[0]) !== 'u8') fail("ir verify: malformed copy.u8 in '" + fn.name + "'");
            break;
          case 'zext.u8.i32':
            if (inst.type !== 'i32' || args.length !== 1 || inst.result == null || valueTypes.get(args[0]) !== 'u8') {
              fail("ir verify: malformed zext.u8.i32 in '" + fn.name + "'");
            }
            break;
          case 'copy.slice.u8':
            if (inst.type !== SEMNEXIS_SLICE_U8_TYPE || args.length !== 1 || inst.result == null ||
                valueTypes.get(args[0]) !== SEMNEXIS_SLICE_U8_TYPE) {
              fail("ir verify: malformed copy.slice.u8 in '" + fn.name + "'");
            }
            break;
          case 'copy.arena':
            if (inst.type !== SEMNEXIS_ARENA_TYPE || args.length !== 1 || inst.result == null ||
                valueTypes.get(args[0]) !== SEMNEXIS_ARENA_TYPE) {
              fail("ir verify: malformed copy.arena in '" + fn.name + "'");
            }
            break;
          case 'slice.len':
            if (inst.type !== 'i32' || args.length !== 1 || inst.result == null ||
                valueTypes.get(args[0]) !== SEMNEXIS_SLICE_U8_TYPE) {
              fail("ir verify: malformed slice.len in '" + fn.name + "'");
            }
            break;
          case 'slice.get.u8':
            if (inst.type !== 'u8' || args.length !== 2 || inst.result == null ||
                valueTypes.get(args[0]) !== SEMNEXIS_SLICE_U8_TYPE || valueTypes.get(args[1]) !== 'i32') {
              fail("ir verify: malformed slice.get.u8 in '" + fn.name + "'");
            }
            break;
          case 'arena.len':
            if (inst.type !== 'i32' || args.length !== 1 || inst.result == null ||
                valueTypes.get(args[0]) !== SEMNEXIS_ARENA_TYPE) {
              fail("ir verify: malformed arena.len in '" + fn.name + "'");
            }
            break;
          case 'arena.load.record': {
            if (!recordByName.has(inst.type) || args.length !== 2 || inst.result == null ||
                valueTypes.get(args[0]) !== SEMNEXIS_ARENA_TYPE || valueTypes.get(args[1]) !== 'i32' ||
                inst.effect !== 'state') {
              fail("ir verify: malformed arena.load.record in '" + fn.name + "'");
            }
            break;
          }
          case 'arena.store.record': {
            if (inst.type !== 'i32' || args.length !== 3 || inst.result == null ||
                valueTypes.get(args[0]) !== SEMNEXIS_ARENA_TYPE || valueTypes.get(args[1]) !== 'i32' ||
                !recordByName.has(inst.recordType) || valueTypes.get(args[2]) !== inst.recordType || inst.effect !== 'state') {
              fail("ir verify: malformed arena.store.record in '" + fn.name + "'");
            }
            break;
          }
          case 'record.make': {
            const record = recordByName.get(inst.type);
            if (!record || inst.result == null || args.length !== record.fields.length) {
              fail("ir verify: malformed record.make in '" + fn.name + "'");
            }
            for (let fieldIndex = 0; fieldIndex < record.fields.length; fieldIndex += 1) {
              if (valueTypes.get(args[fieldIndex]) !== record.fields[fieldIndex].type) {
                fail("ir verify: record.make field type mismatch for '" + inst.type + "'");
              }
            }
            break;
          }
          case 'copy.record':
            if (!recordByName.has(inst.type) || inst.result == null || args.length !== 1 || valueTypes.get(args[0]) !== inst.type) {
              fail("ir verify: malformed copy.record in '" + fn.name + "'");
            }
            break;
          case 'record.get': {
            if (inst.result == null || args.length !== 1 || !Number.isInteger(inst.fieldIndex)) {
              fail("ir verify: malformed record.get in '" + fn.name + "'");
            }
            const recordType = valueTypes.get(args[0]);
            const record = recordByName.get(recordType);
            if (!record || inst.fieldIndex < 0 || inst.fieldIndex >= record.fields.length) {
              fail("ir verify: record.get field index out of range in '" + fn.name + "'");
            }
            if (record.fields[inst.fieldIndex].type !== inst.type) {
              fail("ir verify: record.get result type mismatch in '" + fn.name + "'");
            }
            break;
          }
          case 'i32.add.checked':
          case 'i32.sub.checked':
          case 'i32.mul.checked':
          case 'i32.div.checked':
            if (inst.type !== 'i32' || args.length !== 2 || inst.result == null ||
                valueTypes.get(args[0]) !== 'i32' || valueTypes.get(args[1]) !== 'i32') {
              fail("ir verify: malformed checked arithmetic in '" + fn.name + "'");
            }
            break;
          case 'call': {
            if (!inst.target || !byName.has(inst.target) || inst.result == null) fail("ir verify: unresolved call in '" + fn.name + "'");
            const targetFn = byName.get(inst.target);
            if (args.length !== targetFn.parameters.length) fail("ir verify: call arity mismatch for '" + inst.target + "'");
            if (targetFn.returnType !== inst.type) fail("ir verify: call result type mismatch for '" + inst.target + "'");
            for (let argIndex = 0; argIndex < args.length; argIndex += 1) {
              if (valueTypes.get(args[argIndex]) !== targetFn.parameters[argIndex].type) {
                fail("ir verify: call argument type mismatch for '" + inst.target + "'");
              }
            }
            break;
          }
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
            if (fn.returnType !== 'i32' || inst.type !== 'i32' || inst.result != null || args.length !== 1 ||
                valueTypes.get(args[0]) !== 'i32' || index !== fn.instructions.length - 1) {
              fail("ir verify: malformed ret.i32 in '" + fn.name + "'");
            }
            returns += 1;
            break;
          case 'ret.u8':
            if (fn.returnType !== 'u8' || inst.type !== 'u8' || inst.result != null || args.length !== 1 ||
                valueTypes.get(args[0]) !== 'u8' || index !== fn.instructions.length - 1) {
              fail("ir verify: malformed ret.u8 in '" + fn.name + "'");
            }
            returns += 1;
            break;
          case 'ret.record':
            if (!recordByName.has(fn.returnType) || inst.type !== fn.returnType || inst.result != null || args.length !== 1 ||
                valueTypes.get(args[0]) !== fn.returnType || index !== fn.instructions.length - 1) {
              fail("ir verify: malformed ret.record in '" + fn.name + "'");
            }
            returns += 1;
            break;
          default:
            fail("ir verify: unsupported operation '" + inst.op + "'");
        }
      }

      for (const inst of fn.instructions) {
        if (inst.op !== 'phi.i32' && inst.op !== 'phi.record') continue;
        const expectedType = inst.op === 'phi.i32' ? 'i32' : inst.type;
        for (const arg of inst.args || []) {
          if (valueTypes.get(arg) !== expectedType) {
            fail("ir verify: " + inst.op + " incoming value type mismatch in '" + fn.name + "'");
          }
        }
      }

      if (regionBegins !== 1 || regionEnds !== 1 || returns !== 1) {
        fail("ir verify: function '" + fn.name + "' must have one region begin/end and one return");
      }
      verifyNativeIRControlFlow(fn);
    }
    verifyNativeIREffectsAndCapabilities(this.functions);
  }
  dump(maxChars) {
    const limit = maxChars == null ? Infinity : maxChars;
    const out = [];
    let chars = 0;
    const push = line => {
      chars += line.length + 1;
      if (chars > limit) fail('IR dump exceeds output budget');
      out.push(line);
    };
    push(SEMNEXIS_NATIVE_IR_SCHEMA);
    for (const record of this.recordTypes) {
      push('record ' + record.name + ' fields=' + record.fields.map(function(field) { return field.name + ':' + field.type; }).join(',') + ' abi=flat_words_v0');
    }
    for (const fn of this.functions) {
      push(
        'function ' + fn.name +
        ' graph=' + fn.graphNode +
        ' return=' + fn.returnType +
        ' effect=' + fn.effect +
        ' region=' + fn.region +
        ' requires=' + (fn.requiresCapabilities.length ? fn.requiresCapabilities.join(',') : '-') +
        ' grants=' + (fn.grantsCapabilities.length ? fn.grantsCapabilities.join(',') : '-')
      );
      for (const parameter of fn.parameters) {
        push(
          'param ' + parameter.index + ' ' + parameter.value + ':' + parameter.type +
          ' name=' + parameter.name + ' graph=' + parameter.graphNode
        );
      }
      for (const inst of fn.instructions) push(dumpNativeIRInstruction(inst));
      push('endfunction ' + fn.name);
    }
    return out.join('\n') + '\n';
  }
}

function dumpNativeIRInstruction(inst) {
  let body = 'inst ' + inst.index + ' ';
  const result = inst.result != null ? inst.result + ':' + inst.type + ' = ' : '';
  if (inst.op === 'region.begin' || inst.op === 'region.end') {
    body += inst.op + ' region=' + inst.region;
  } else if (inst.op === 'block.begin') {
    body += 'block.begin ' + inst.label;
  } else if (inst.op === 'br') {
    body += 'br ' + inst.target;
  } else if (inst.op.indexOf('br.cmp.') === 0) {
    body += inst.op + ' ' + inst.args[0] + ' ' + inst.args[1] + ' then=' + inst.thenLabel + ' else=' + inst.elseLabel;
  } else if (inst.op === 'phi.i32' || inst.op === 'phi.record') {
    body += result + inst.op + ' ' + inst.incoming.map(function(row) { return row.label + ':' + row.value; }).join(' ');
  } else if (inst.op === 'const.i32' || inst.op === 'const.u8') {
    body += result + inst.op + ' ' + inst.value;
  } else if (inst.op === 'copy.i32' || inst.op === 'copy.u8' || inst.op === 'copy.slice.u8' || inst.op === 'copy.arena' || inst.op === 'zext.u8.i32') {
    body += result + inst.op + ' ' + inst.args[0];
  } else if (inst.op === 'slice.len') {
    body += result + 'slice.len ' + inst.args[0];
  } else if (inst.op === 'slice.get.u8') {
    body += result + 'slice.get.u8 ' + inst.args[0] + ' ' + inst.args[1];
  } else if (inst.op === 'arena.len') {
    body += result + 'arena.len ' + inst.args[0];
  } else if (inst.op === 'arena.load.record') {
    body += result + 'arena.load.record ' + inst.args[0] + ' ' + inst.args[1] + ' record=' + inst.type + ' effect=' + inst.effect;
  } else if (inst.op === 'arena.store.record') {
    body += result + 'arena.store.record ' + inst.args[0] + ' ' + inst.args[1] + ' ' + inst.args[2] + ' record=' + inst.recordType + ' effect=' + inst.effect;
  } else if (inst.op === 'record.make') {
    body += result + 'record.make ' + inst.type;
    for (const arg of inst.args) body += ' ' + arg;
  } else if (inst.op === 'copy.record') {
    body += result + 'copy.record ' + inst.args[0];
  } else if (inst.op === 'record.get') {
    body += result + 'record.get ' + inst.args[0] + ' field=' + inst.fieldIndex;
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
  } else if (inst.op === 'ret.i32' || inst.op === 'ret.u8' || inst.op === 'ret.record') {
    body += inst.op + ' ' + inst.args[0];
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

function beginIRBlock(ctx, label, graphNode) {
  emitIR(ctx, 'block.begin', {
    label:label,
    args:[],
    graphNode:graphNode
  });
  ctx.currentBlock = label;
}

function lowerGraphComparisonForBranch(graph, nodeId, ctx) {
  const node = graph.nodes[nodeId];
  if (!node || node.kind !== NodeKind.Binary) fail('ir lower: if condition must be a comparison node');
  const operation = graph.attribute(node.id, 'operation');
  const opMap = {
    EqEq:'br.cmp.eq',
    BangEq:'br.cmp.ne',
    Less:'br.cmp.lt',
    LessEq:'br.cmp.le',
    Greater:'br.cmp.gt',
    GreaterEq:'br.cmp.ge'
  };
  if (!opMap[operation]) fail("ir lower: unsupported comparison operation '" + operation + "'");
  const left = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'lhs'), ctx);
  const right = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'rhs'), ctx);
  return {op:opMap[operation], left:left, right:right, graphNode:node.id};
}

function lowerGraphExpressionToIR(graph, nodeId, ctx) {
  if (ctx.expressionValues.has(nodeId)) return ctx.expressionValues.get(nodeId);
  const node = graph.nodes[nodeId];
  if (!node || !isExpressionKind(node.kind)) fail('ir lower: expected expression graph node');

  let value;
  if (node.kind === NodeKind.Constant) {
    const raw = Number(graph.attribute(node.id, 'value'));
    const valueType = graphTypeName(graph, node.id);
    if (!isBootstrapScalarType(valueType)) fail('ir lower: constant has unsupported scalar type');
    value = newIRValue(ctx);
    emitIR(ctx, valueType === 'u8' ? 'const.u8' : 'const.i32', {
      result:value,
      type:valueType,
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
    } else if (target.kind === NodeKind.LoopState) {
      if (!ctx.loopStateValues.has(targetId)) fail("ir lower: loop state '" + target.name + "' used outside active loop scope");
      source = ctx.loopStateValues.get(targetId);
    } else {
      fail('ir lower: NameRef resolved to unsupported symbol');
    }
    if (!source) fail("ir lower: unresolved SSA source for '" + node.name + "'");
    const valueType = graphTypeName(graph, node.id);
    const typeNode = graph.singleEdgeTarget(node.id, 'has_type');
    const recordType = graph.attribute(typeNode, 'kind') === 'record';
    if (!isBootstrapValueType(valueType) && !recordType) fail('ir lower: name reference has unsupported value type');
    value = newIRValue(ctx);
    const copyOp = recordType ? 'copy.record' :
      (valueType === 'u8' ? 'copy.u8' :
      (valueType === SEMNEXIS_SLICE_U8_TYPE ? 'copy.slice.u8' :
      (valueType === SEMNEXIS_ARENA_TYPE ? 'copy.arena' : 'copy.i32')));
    emitIR(ctx, copyOp, {
      result:value,
      type:valueType,
      args:[source],
      graphNode:node.id
    });
  } else if (node.kind === NodeKind.Record) {
    const typeName = graphTypeName(graph, node.id);
    const typeNode = graph.singleEdgeTarget(node.id, 'has_type');
    const fieldCount = Number(graph.attribute(typeNode, 'field_count'));
    const args = [];
    for (let i = 0; i < fieldCount; i += 1) {
      args.push(lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'field' + i), ctx));
    }
    value = newIRValue(ctx);
    emitIR(ctx, 'record.make', {
      result:value,
      type:typeName,
      args:args,
      graphNode:node.id
    });
  } else if (node.kind === NodeKind.Field) {
    const base = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'base'), ctx);
    const fieldIndex = Number(graph.attribute(node.id, 'field_index'));
    value = newIRValue(ctx);
    emitIR(ctx, 'record.get', {
      result:value,
      type:graphTypeName(graph, node.id),
      args:[base],
      fieldIndex:fieldIndex,
      graphNode:node.id
    });
  } else if (node.kind === NodeKind.Convert) {
    const conversion = graph.attribute(node.id, 'conversion');
    if (conversion !== 'u8_to_i32') fail("ir lower: unsupported conversion '" + conversion + "'");
    const operand = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'operand'), ctx);
    value = newIRValue(ctx);
    emitIR(ctx, 'zext.u8.i32', {
      result:value,
      type:'i32',
      args:[operand],
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
    if (!opMap[operation]) fail("ir lower: comparison value may only appear as an if condition, got '" + operation + "'");
    value = newIRValue(ctx);
    emitIR(ctx, opMap[operation], {
      result:value,
      type:graphTypeName(graph, node.id),
      args:[left, right],
      graphNode:node.id
    });
  } else if (node.kind === NodeKind.Conditional) {
    if (!ctx.controlFlow) fail('ir lower: conditional encountered without control-flow mode');
    const conditionNode = graph.singleEdgeTarget(node.id, 'condition');
    const condition = lowerGraphComparisonForBranch(graph, conditionNode, ctx);
    const branchId = ctx.nextConditional;
    ctx.nextConditional += 1;
    const thenLabel = 'if' + branchId + '.then';
    const elseLabel = 'if' + branchId + '.else';
    const mergeLabel = 'if' + branchId + '.merge';

    emitIR(ctx, condition.op, {
      args:[condition.left, condition.right],
      thenLabel:thenLabel,
      elseLabel:elseLabel,
      graphNode:condition.graphNode
    });

    beginIRBlock(ctx, thenLabel, node.id);
    const thenValue = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'then_value'), ctx);
    const thenPredecessor = ctx.currentBlock;
    emitIR(ctx, 'br', {args:[], target:mergeLabel, graphNode:node.id});

    beginIRBlock(ctx, elseLabel, node.id);
    const elseValue = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'else_value'), ctx);
    const elsePredecessor = ctx.currentBlock;
    emitIR(ctx, 'br', {args:[], target:mergeLabel, graphNode:node.id});

    beginIRBlock(ctx, mergeLabel, node.id);
    value = newIRValue(ctx);
    const resultType = graphTypeName(graph, node.id);
    const resultTypeNode = graph.singleEdgeTarget(node.id, 'has_type');
    const phiOp = resultType === 'i32' ? 'phi.i32' :
      (graph.attribute(resultTypeNode, 'kind') === 'record' ? 'phi.record' : null);
    if (!phiOp) fail("ir lower: unsupported conditional merge type '" + resultType + "'");
    emitIR(ctx, phiOp, {
      result:value,
      type:resultType,
      args:[thenValue, elseValue],
      incoming:[
        {label:thenPredecessor, value:thenValue},
        {label:elsePredecessor, value:elseValue}
      ],
      graphNode:node.id
    });
  } else if (node.kind === NodeKind.Loop) {
    if (!ctx.controlFlow) fail('ir lower: loop encountered without control-flow mode');
    const stateNodes = graph.edgesFrom(node.id, 'carries_state').map((edge) => graph.nodes[edge.to]);
    if (!stateNodes.length) fail('ir lower: loop has no carried state');

    const initialValues = stateNodes.map((state) => {
      return lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(state.id, 'initialized_by'), ctx);
    });
    const preheader = ctx.currentBlock;
    if (!preheader) fail('ir lower: loop has no preheader block');

    const loopId = ctx.nextLoop;
    ctx.nextLoop += 1;
    const headerLabel = 'loop' + loopId + '.header';
    const bodyLabel = 'loop' + loopId + '.body';
    const exitLabel = 'loop' + loopId + '.exit';
    emitIR(ctx, 'br', {args:[], target:headerLabel, graphNode:node.id});

    beginIRBlock(ctx, headerLabel, node.id);
    const previousLoopStates = ctx.loopStateValues;
    const activeLoopStates = new Map(previousLoopStates);
    const phis = [];
    for (let i = 0; i < stateNodes.length; i += 1) {
      const state = stateNodes[i];
      const stateType = graphTypeName(graph, state.id);
      const stateTypeNode = graph.singleEdgeTarget(state.id, 'has_type');
      const phiOp = stateType === 'i32' ? 'phi.i32' :
        (graph.attribute(stateTypeNode, 'kind') === 'record' ? 'phi.record' : null);
      if (!phiOp) fail("ir lower: unsupported loop-carried state type '" + stateType + "'");
      const phiValue = newIRValue(ctx);
      const phi = emitIR(ctx, phiOp, {
        result:phiValue,
        type:stateType,
        args:[initialValues[i]],
        incoming:[{label:preheader, value:initialValues[i]}],
        graphNode:state.id
      });
      phis.push(phi);
      activeLoopStates.set(state.id, phiValue);
    }
    ctx.loopStateValues = activeLoopStates;

    const conditionNode = graph.singleEdgeTarget(node.id, 'condition');
    const condition = lowerGraphComparisonForBranch(graph, conditionNode, ctx);
    emitIR(ctx, condition.op, {
      args:[condition.left, condition.right],
      thenLabel:bodyLabel,
      elseLabel:exitLabel,
      graphNode:condition.graphNode
    });

    beginIRBlock(ctx, bodyLabel, node.id);
    const nextValues = stateNodes.map((state) => {
      return lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(state.id, 'next_value'), ctx);
    });
    const backedge = ctx.currentBlock;
    emitIR(ctx, 'br', {args:[], target:headerLabel, graphNode:node.id});
    for (let i = 0; i < phis.length; i += 1) {
      phis[i].args.push(nextValues[i]);
      phis[i].incoming.push({label:backedge, value:nextValues[i]});
    }

    beginIRBlock(ctx, exitLabel, node.id);
    value = lowerGraphExpressionToIR(graph, graph.singleEdgeTarget(node.id, 'yield_value'), ctx);
    ctx.loopStateValues = previousLoopStates;
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
    } else if (target.kind === NodeKind.Intrinsic && target.name === 'slice_len') {
      emitIR(ctx, 'slice.len', {
        result:value,
        type:'i32',
        args:args,
        graphNode:node.id
      });
    } else if (target.kind === NodeKind.Intrinsic && target.name === 'slice_get') {
      emitIR(ctx, 'slice.get.u8', {
        result:value,
        type:'u8',
        args:args,
        graphNode:node.id
      });
    } else if (target.kind === NodeKind.Intrinsic && target.name === 'arena_len') {
      emitIR(ctx, 'arena.len', {
        result:value,
        type:'i32',
        args:args,
        graphNode:node.id
      });
    } else if (target.kind === NodeKind.Intrinsic && graph.attribute(target.id, 'intrinsic_base') === 'arena_load') {
      emitIR(ctx, 'arena.load.record', {
        result:value,
        type:graphTypeName(graph, node.id),
        args:args,
        effect:'state',
        graphNode:node.id
      });
    } else if (target.kind === NodeKind.Intrinsic && target.name === 'arena_store') {
      emitIR(ctx, 'arena.store.record', {
        result:value,
        type:'i32',
        args:args,
        recordType:graphTypeName(graph, argEdges[2].to),
        effect:'state',
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

  for (const typeNode of graph.nodes) {
    if (typeNode.kind !== NodeKind.Type || graph.attribute(typeNode.id, 'kind') !== 'record') continue;
    const fieldCount = Number(graph.attribute(typeNode.id, 'field_count'));
    const fields = [];
    for (let i = 0; i < fieldCount; i += 1) {
      fields.push({
        index:i,
        name:graph.attribute(typeNode.id, 'field' + i + '_name'),
        type:graph.attribute(typeNode.id, 'field' + i + '_type')
      });
    }
    ir.addRecordType({name:typeNode.name, fields:fields, abi:'flat_words_v0'});
  }

  for (const fnNode of graph.nodes) {
    if (fnNode.kind !== NodeKind.Function) continue;
    const fn = ir.addFunction(makeIRFunction(graph, fnNode));
    const controlFlow = graph.edgesFrom(fnNode.id, 'contains_expr')
      .some((edge) => graph.nodes[edge.to].kind === NodeKind.Conditional || graph.nodes[edge.to].kind === NodeKind.Loop);
    const ctx = {
      fn:fn,
      nextValue:0,
      nextConditional:0,
      nextLoop:0,
      currentBlock:null,
      controlFlow:controlFlow,
      parameterValues:new Map(),
      localValues:new Map(),
      loopStateValues:new Map(),
      expressionValues:new Map()
    };
    for (const parameter of fn.parameters) ctx.parameterValues.set(parameter.graphNode, parameter.value);

    const regionNode = graph.singleEdgeTarget(fnNode.id, 'executes_in');
    emitIR(ctx, 'region.begin', {
      region:fn.region,
      args:[],
      graphNode:regionNode
    });
    if (controlFlow) beginIRBlock(ctx, 'entry', regionNode);

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
    const returnOp = ir.recordTypes.some((record) => record.name === fn.returnType)
      ? 'ret.record'
      : (fn.returnType === 'u8' ? 'ret.u8' : 'ret.i32');
    emitIR(ctx, returnOp, {
      type:fn.returnType,
      args:[returnValue],
      graphNode:returnNode.id
    });
  }

  ir.verify();
  return ir;
}


const NATIVE_IR_BINARY_MAGIC_V0 = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x30,0x00,0x00]);
const NATIVE_IR_BINARY_MAGIC_V1 = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x31,0x00,0x00]);
const NATIVE_IR_BINARY_MAGIC_V2 = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x32,0x00,0x00]);
const NATIVE_IR_BINARY_MAGIC_V3 = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x33,0x00,0x00]);
const NATIVE_IR_BINARY_MAGIC_V4 = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x34,0x00,0x00]);
const NATIVE_IR_BINARY_MAGIC_V5 = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x35,0x00,0x00]);
const NATIVE_IR_BINARY_MAGIC_V6 = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x36,0x00,0x00]);
const NATIVE_IR_BINARY_MAGIC_V7 = Object.freeze([0x53,0x4e,0x49,0x52,0x56,0x37,0x00,0x00]);
const NATIVE_IR_BINARY_VERSION_V0 = 0;
const NATIVE_IR_BINARY_VERSION_V1 = 1;
const NATIVE_IR_BINARY_VERSION_V2 = 2;
const NATIVE_IR_BINARY_VERSION_V3 = 3;
const NATIVE_IR_BINARY_VERSION_V4 = 4;
const NATIVE_IR_BINARY_VERSION_V5 = 5;
const NATIVE_IR_BINARY_VERSION_V6 = 6;
const NATIVE_IR_BINARY_VERSION_V7 = 7;
const MAX_NATIVE_IR_BINARY_BYTES = 4 * 1024 * 1024;
const MAX_NATIVE_IR_FUNCTIONS = 1024;
const MAX_NATIVE_IR_RECORD_TYPES = 252;
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
  u8(value) {
    if (this.bytes.length >= MAX_NATIVE_IR_BINARY_BYTES) fail('ir binary: payload too large');
    this.bytes.push(value & 0xFF);
  }
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
    else if (Array.isArray(bytes)) {
      if (bytes.length > MAX_NATIVE_IR_BINARY_BYTES) fail('ir binary: payload too large');
      this.bytes = Uint8Array.from(bytes);
    } else if (bytes instanceof ArrayBuffer) {
      if (bytes.byteLength > MAX_NATIVE_IR_BINARY_BYTES) fail('ir binary: payload too large');
      this.bytes = new Uint8Array(bytes);
    } else fail('ir binary: expected bytes');
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

const NATIVE_IR_OPCODE_V0 = Object.freeze({
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
  'ret.i32':12,
  'block.begin':13,
  'br':14,
  'br.cmp.eq':15,
  'br.cmp.ne':16,
  'br.cmp.lt':17,
  'br.cmp.le':18,
  'br.cmp.gt':19,
  'br.cmp.ge':20,
  'phi.i32':21
});

const NATIVE_IR_OPCODE_V1 = Object.freeze(Object.assign({}, NATIVE_IR_OPCODE_V0, {
  'const.u8':22,
  'copy.u8':23,
  'ret.u8':24
}));

const NATIVE_IR_OPCODE_V2 = Object.freeze(Object.assign({}, NATIVE_IR_OPCODE_V1, {
  'copy.slice.u8':25,
  'slice.len':26,
  'slice.get.u8':27
}));

const NATIVE_IR_OPCODE_V3 = Object.freeze(Object.assign({}, NATIVE_IR_OPCODE_V2, {
  'record.make':28,
  'copy.record':29,
  'ret.record':30
}));

const NATIVE_IR_OPCODE_V4 = Object.freeze(Object.assign({}, NATIVE_IR_OPCODE_V3, {
  'record.get':31
}));

const NATIVE_IR_OPCODE_V5 = Object.freeze(Object.assign({}, NATIVE_IR_OPCODE_V4, {
  'phi.record':32
}));

const NATIVE_IR_OPCODE_V6 = Object.freeze(Object.assign({}, NATIVE_IR_OPCODE_V5, {
  'zext.u8.i32':33
}));

const NATIVE_IR_OPCODE_V7 = Object.freeze(Object.assign({}, NATIVE_IR_OPCODE_V6, {
  'copy.arena':34,
  'arena.len':35,
  'arena.store.record':36,
  'arena.load.record':37
}));

function nativeIROpcodeNames(table) {
  return Object.freeze(Object.keys(table).reduce(function(out, name) {
    out[table[name]] = name;
    return out;
  }, {}));
}

const NATIVE_IR_OPCODE_NAME_V0 = nativeIROpcodeNames(NATIVE_IR_OPCODE_V0);
const NATIVE_IR_OPCODE_NAME_V1 = nativeIROpcodeNames(NATIVE_IR_OPCODE_V1);
const NATIVE_IR_OPCODE_NAME_V2 = nativeIROpcodeNames(NATIVE_IR_OPCODE_V2);
const NATIVE_IR_OPCODE_NAME_V3 = nativeIROpcodeNames(NATIVE_IR_OPCODE_V3);
const NATIVE_IR_OPCODE_NAME_V4 = nativeIROpcodeNames(NATIVE_IR_OPCODE_V4);
const NATIVE_IR_OPCODE_NAME_V5 = nativeIROpcodeNames(NATIVE_IR_OPCODE_V5);
const NATIVE_IR_OPCODE_NAME_V6 = nativeIROpcodeNames(NATIVE_IR_OPCODE_V6);
const NATIVE_IR_OPCODE_NAME_V7 = nativeIROpcodeNames(NATIVE_IR_OPCODE_V7);

function nativeIRTypeCode(type, version, recordIndexByName) {
  if (type == null) return 0;
  if (type === 'i32') return 1;
  if (version >= 1 && type === 'u8') return 2;
  if (version >= 2 && type === SEMNEXIS_SLICE_U8_TYPE) return 3;
  if (version >= 7 && type === SEMNEXIS_ARENA_TYPE) return 255;
  if (version >= 3 && recordIndexByName && recordIndexByName.has(type)) {
    const index = recordIndexByName.get(type);
    const limit = version >= 7 ? 251 : MAX_NATIVE_IR_RECORD_TYPES;
    if (!Number.isInteger(index) || index < 0 || index >= limit) fail('ir binary: record type index out of range');
    return 4 + index;
  }
  fail("ir binary: unsupported type '" + type + "' for version " + version);
}

function nativeIRTypeName(code, version, recordNames) {
  if (code === 0) return null;
  if (code === 1) return 'i32';
  if (version >= 1 && code === 2) return 'u8';
  if (version >= 2 && code === 3) return SEMNEXIS_SLICE_U8_TYPE;
  if (version >= 7 && code === 255) return SEMNEXIS_ARENA_TYPE;
  if (version >= 3 && code >= 4) {
    const index = code - 4;
    if (!recordNames || index < 0 || index >= recordNames.length) fail('ir binary: unknown record type code ' + code);
    return recordNames[index];
  }
  fail('ir binary: unknown type code ' + code + ' for version ' + version);
}

function nativeIREffectCode(effect, version) {
  if (effect === 'pure') return 0;
  if (effect === 'time') return 1;
  if (version >= 7 && effect === 'state') return 2;
  if (version >= 7 && effect === 'time_state') return 3;
  fail("ir binary: unsupported effect '" + effect + "' for version " + version);
}

function nativeIREffectName(code, version) {
  if (code === 0) return 'pure';
  if (code === 1) return 'time';
  if (version >= 7 && code === 2) return 'state';
  if (version >= 7 && code === 3) return 'time_state';
  fail('ir binary: unknown effect code ' + code + ' for version ' + version);
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

function nativeIRRequiredVersion(ir) {
  let required = ir.recordTypes && ir.recordTypes.length ? 3 : 0;
  for (const fn of ir.functions) {
    if (fn.effect === 'state' || fn.effect === 'time_state') required = Math.max(required, 7);
    if (fn.returnType === 'u8') required = Math.max(required, 1);
    for (const parameter of fn.parameters) {
      if (parameter.type === SEMNEXIS_ARENA_TYPE) required = Math.max(required, 7);
      else if (parameter.type === SEMNEXIS_SLICE_U8_TYPE) required = Math.max(required, 2);
      else if (parameter.type === 'u8') required = Math.max(required, 1);
    }
    for (const inst of fn.instructions) {
      if (inst.type === SEMNEXIS_ARENA_TYPE || inst.op === 'copy.arena' || inst.op === 'arena.len' || inst.op === 'arena.load.record' || inst.op === 'arena.store.record') required = Math.max(required, 7);
      else if (inst.op === 'zext.u8.i32') required = Math.max(required, 6);
      else if (inst.op === 'phi.record') required = Math.max(required, 5);
      else if (inst.op === 'record.get') required = Math.max(required, 4);
      else if (inst.type === SEMNEXIS_SLICE_U8_TYPE || inst.op === 'copy.slice.u8' ||
          inst.op === 'slice.len' || inst.op === 'slice.get.u8') required = Math.max(required, 2);
      else if (inst.type === 'u8' || inst.op === 'const.u8' || inst.op === 'copy.u8' || inst.op === 'ret.u8') required = Math.max(required, 1);
    }
  }
  return required;
}

function encodeNativeIRVersion(ir, version) {
  ir.verify();
  if (version !== 0 && version !== 1 && version !== 2 && version !== 3 && version !== 4 && version !== 5 && version !== 6 && version !== 7) fail('ir binary: unsupported encoder version');
  const requiredVersion = nativeIRRequiredVersion(ir);
  if (version < requiredVersion) {
    if (requiredVersion === 1) fail('ir binary: SNIRV0 cannot encode u8 values');
    if (requiredVersion === 2) fail('ir binary: SNIRV' + version + ' cannot encode Slice<u8> values');
    if (requiredVersion === 3) fail('ir binary: SNIRV' + version + ' cannot encode record values');
    if (requiredVersion === 4) fail('ir binary: SNIRV' + version + ' cannot encode record field projection');
    if (requiredVersion === 5) fail('ir binary: SNIRV' + version + ' cannot encode record phi values');
    if (requiredVersion === 6) fail('ir binary: SNIRV' + version + ' cannot encode u8-to-i32 widening');
    fail('ir binary: SNIRV' + version + ' cannot encode Arena/state values');
  }
  if (ir.functions.length > MAX_NATIVE_IR_FUNCTIONS) fail('ir binary: too many functions');
  const recordTypeLimit = version >= 7 ? 251 : MAX_NATIVE_IR_RECORD_TYPES;
  if (ir.recordTypes.length > recordTypeLimit) fail('ir binary: too many record types');
  const writer = new NativeIRBinaryWriter();
  const magic = version === 0 ? NATIVE_IR_BINARY_MAGIC_V0 :
    (version === 1 ? NATIVE_IR_BINARY_MAGIC_V1 : (version === 2 ? NATIVE_IR_BINARY_MAGIC_V2 :
    (version === 3 ? NATIVE_IR_BINARY_MAGIC_V3 : (version === 4 ? NATIVE_IR_BINARY_MAGIC_V4 :
    (version === 5 ? NATIVE_IR_BINARY_MAGIC_V5 : (version === 6 ? NATIVE_IR_BINARY_MAGIC_V6 : NATIVE_IR_BINARY_MAGIC_V7))))));
  const opcodeTable = version === 0 ? NATIVE_IR_OPCODE_V0 :
    (version === 1 ? NATIVE_IR_OPCODE_V1 : (version === 2 ? NATIVE_IR_OPCODE_V2 :
    (version === 3 ? NATIVE_IR_OPCODE_V3 : (version === 4 ? NATIVE_IR_OPCODE_V4 :
    (version === 5 ? NATIVE_IR_OPCODE_V5 : (version === 6 ? NATIVE_IR_OPCODE_V6 : NATIVE_IR_OPCODE_V7))))));
  const recordIndexByName = new Map(ir.recordTypes.map((record, index) => [record.name, index]));
  for (const byte of magic) writer.u8(byte);
  writer.u16(version);
  writer.u16(0);
  if (version >= 3) {
    writer.u16(ir.recordTypes.length);
    for (const record of ir.recordTypes) {
      writer.string(record.name);
      writer.u8(record.fields.length);
      for (const field of record.fields) {
        writer.string(field.name);
        writer.u8(nativeIRTypeCode(field.type, 1));
      }
    }
  }
  writer.u32(ir.functions.length);

  for (const fn of ir.functions) {
    if (fn.parameters.length > MAX_NATIVE_IR_PARAMETERS) fail("ir binary: too many parameters in '" + fn.name + "'");
    if (fn.instructions.length > MAX_NATIVE_IR_INSTRUCTIONS) fail("ir binary: too many instructions in '" + fn.name + "'");
    writer.string(fn.name);
    writer.u32(fn.graphNode);
    writer.u8(nativeIRTypeCode(fn.returnType, version, recordIndexByName));
    writer.u8(nativeIREffectCode(fn.effect, version));
    let capabilityFlags = 0;
    if (fn.requiresCapabilities.includes('time')) capabilityFlags |= 1;
    if (fn.grantsCapabilities.includes('time')) capabilityFlags |= 2;
    writer.u8(capabilityFlags);
    writer.string(fn.region);
    writer.u16(fn.parameters.length);
    writer.u32(fn.instructions.length);

    for (const parameter of fn.parameters) {
      writer.u16(parameter.index);
      writer.u8(nativeIRTypeCode(parameter.type, version, recordIndexByName));
      writer.string(parameter.name);
      writer.u32(parameter.graphNode);
    }

    for (const inst of fn.instructions) {
      const opcode = opcodeTable[inst.op];
      if (!opcode) fail("ir binary: unsupported operation '" + inst.op + "'");
      writer.u32(inst.index);
      writer.u8(opcode);
      writer.u32(inst.graphNode);
      writer.u32(inst.result == null ? 0xFFFFFFFF : nativeIRValueRef(inst.result));
      writer.u8(nativeIRTypeCode(inst.type, version, recordIndexByName));
      const args = Array.isArray(inst.args) ? inst.args : [];
      if (args.length > 255) fail('ir binary: too many instruction operands');
      writer.u8(args.length);
      for (const arg of args) writer.u32(nativeIRValueRef(arg));

      if (inst.op === 'const.i32') writer.i32(inst.value);
      else if (inst.op === 'const.u8') writer.u8(inst.value);
      else if (inst.op === 'call') writer.string(inst.target);
      else if (inst.op === 'local.bind') writer.string(inst.name);
      else if (inst.op === 'block.begin') writer.string(inst.label);
      else if (inst.op === 'br') writer.string(inst.target);
      else if (inst.op.indexOf('br.cmp.') === 0) {
        writer.string(inst.thenLabel);
        writer.string(inst.elseLabel);
      } else if (inst.op === 'phi.i32' || inst.op === 'phi.record') {
        if (!Array.isArray(inst.incoming) || inst.incoming.length > 255) fail('ir binary: invalid phi incoming list');
        writer.u8(inst.incoming.length);
        for (const incoming of inst.incoming) {
          writer.string(incoming.label);
          writer.u32(nativeIRValueRef(incoming.value));
        }
      } else if (inst.op === 'record.get') {
        if (!Number.isInteger(inst.fieldIndex) || inst.fieldIndex < 0 || inst.fieldIndex > 255) fail('ir binary: invalid record field index');
        writer.u8(inst.fieldIndex);
      } else if (inst.op === 'arena.store.record') {
        writer.u8(nativeIRTypeCode(inst.recordType, version, recordIndexByName));
      }
    }
  }
  return writer.finish();
}

function encodeNativeIRV0(ir) { return encodeNativeIRVersion(ir, 0); }
function encodeNativeIRV1(ir) { return encodeNativeIRVersion(ir, 1); }
function encodeNativeIRV2(ir) { return encodeNativeIRVersion(ir, 2); }
function encodeNativeIRV3(ir) { return encodeNativeIRVersion(ir, 3); }
function encodeNativeIRV4(ir) { return encodeNativeIRVersion(ir, 4); }
function encodeNativeIRV5(ir) { return encodeNativeIRVersion(ir, 5); }
function encodeNativeIRV6(ir) { return encodeNativeIRVersion(ir, 6); }
function encodeNativeIRV7(ir) { return encodeNativeIRVersion(ir, 7); }
function encodeNativeIR(ir) { return encodeNativeIRVersion(ir, nativeIRRequiredVersion(ir)); }

function decodeNativeIRVersion(bytes, version) {
  if (version !== 0 && version !== 1 && version !== 2 && version !== 3 && version !== 4 && version !== 5 && version !== 6 && version !== 7) fail('ir binary: unsupported decoder version');
  const reader = new NativeIRBinaryReader(bytes);
  const magic = version === 0 ? NATIVE_IR_BINARY_MAGIC_V0 :
    (version === 1 ? NATIVE_IR_BINARY_MAGIC_V1 : (version === 2 ? NATIVE_IR_BINARY_MAGIC_V2 :
    (version === 3 ? NATIVE_IR_BINARY_MAGIC_V3 : (version === 4 ? NATIVE_IR_BINARY_MAGIC_V4 :
    (version === 5 ? NATIVE_IR_BINARY_MAGIC_V5 : (version === 6 ? NATIVE_IR_BINARY_MAGIC_V6 : NATIVE_IR_BINARY_MAGIC_V7))))));
  const opcodeNames = version === 0 ? NATIVE_IR_OPCODE_NAME_V0 :
    (version === 1 ? NATIVE_IR_OPCODE_NAME_V1 : (version === 2 ? NATIVE_IR_OPCODE_NAME_V2 :
    (version === 3 ? NATIVE_IR_OPCODE_NAME_V3 : (version === 4 ? NATIVE_IR_OPCODE_NAME_V4 :
    (version === 5 ? NATIVE_IR_OPCODE_NAME_V5 : (version === 6 ? NATIVE_IR_OPCODE_NAME_V6 : NATIVE_IR_OPCODE_NAME_V7))))));
  for (const expected of magic) {
    if (reader.u8() !== expected) fail('ir binary: magic mismatch');
  }
  if (reader.u16() !== version) fail('ir binary: unsupported version');
  if (reader.u16() !== 0) fail('ir binary: nonzero reserved flags');

  const ir = new NativeIRModule();
  const recordNames = [];
  if (version >= 3) {
    const recordCount = reader.u16();
    const recordTypeLimit = version >= 7 ? 251 : MAX_NATIVE_IR_RECORD_TYPES;
    if (recordCount > recordTypeLimit) fail('ir binary: too many record types');
    const seenRecordNames = new Set();
    for (let r = 0; r < recordCount; r += 1) {
      const recordName = reader.string();
      if (!recordName || seenRecordNames.has(recordName)) fail('ir binary: duplicate or empty record type name');
      seenRecordNames.add(recordName);
      const fieldCount = reader.u8();
      if (fieldCount < 1 || fieldCount > MAX_SEMNEXIS_FLAT_RECORD_WORDS) fail('ir binary: invalid record field count');
      const fields = [];
      const seenFieldNames = new Set();
      for (let fieldIndex = 0; fieldIndex < fieldCount; fieldIndex += 1) {
        const fieldName = reader.string();
        if (!fieldName || seenFieldNames.has(fieldName)) fail('ir binary: duplicate or empty record field name');
        seenFieldNames.add(fieldName);
        const fieldType = nativeIRTypeName(reader.u8(), 1, []);
        if (!isBootstrapScalarType(fieldType)) fail('ir binary: record fields must be scalar');
        fields.push({index:fieldIndex, name:fieldName, type:fieldType});
      }
      recordNames.push(recordName);
      ir.addRecordType({name:recordName, fields:fields, abi:'flat_words_v0'});
    }
  }

  const functionCount = reader.u32();
  if (!functionCount || functionCount > MAX_NATIVE_IR_FUNCTIONS) fail('ir binary: invalid function count');
  for (let f = 0; f < functionCount; f += 1) {
    const name = reader.string();
    const graphNode = reader.u32();
    const returnType = nativeIRTypeName(reader.u8(), version, recordNames);
    const effect = nativeIREffectName(reader.u8(), version);
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
      const type = nativeIRTypeName(reader.u8(), version, recordNames);
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
      const op = opcodeNames[opcode];
      if (!op) fail('ir binary: unknown opcode ' + opcode);
      const graphNode = reader.u32();
      const resultRef = reader.u32();
      const type = nativeIRTypeName(reader.u8(), version, recordNames);
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
      else if (op === 'const.u8') inst.value = reader.u8();
      else if (op === 'call') inst.target = reader.string();
      else if (op === 'intrinsic.clock') {
        inst.effect = 'time';
        inst.capability = 'time';
      } else if (op === 'local.bind') inst.name = reader.string();
      else if (op === 'block.begin') inst.label = reader.string();
      else if (op === 'br') inst.target = reader.string();
      else if (op.indexOf('br.cmp.') === 0) {
        inst.thenLabel = reader.string();
        inst.elseLabel = reader.string();
      } else if (op === 'phi.i32' || op === 'phi.record') {
        const incomingCount = reader.u8();
        inst.incoming = [];
        for (let p = 0; p < incomingCount; p += 1) {
          inst.incoming.push({label:reader.string(), value:nativeIRValueName(reader.u32())});
        }
      } else if (op === 'record.get') {
        inst.fieldIndex = reader.u8();
      } else if (op === 'arena.load.record') {
        inst.effect = 'state';
      } else if (op === 'arena.store.record') {
        inst.recordType = nativeIRTypeName(reader.u8(), version, recordNames);
        inst.effect = 'state';
      }
      fn.instructions.push(inst);
    }
  }

  if (!reader.done()) fail('ir binary: trailing bytes');
  ir.verify();
  return ir;
}

function decodeNativeIRV0(bytes) { return decodeNativeIRVersion(bytes, 0); }
function decodeNativeIRV1(bytes) { return decodeNativeIRVersion(bytes, 1); }
function decodeNativeIRV2(bytes) { return decodeNativeIRVersion(bytes, 2); }
function decodeNativeIRV3(bytes) { return decodeNativeIRVersion(bytes, 3); }
function decodeNativeIRV4(bytes) { return decodeNativeIRVersion(bytes, 4); }
function decodeNativeIRV5(bytes) { return decodeNativeIRVersion(bytes, 5); }
function decodeNativeIRV6(bytes) { return decodeNativeIRVersion(bytes, 6); }
function decodeNativeIRV7(bytes) { return decodeNativeIRVersion(bytes, 7); }

function decodeNativeIR(bytes) {
  let view;
  if (bytes instanceof Uint8Array) view = bytes;
  else if (Array.isArray(bytes)) view = Uint8Array.from(bytes);
  else if (bytes instanceof ArrayBuffer) view = new Uint8Array(bytes);
  else fail('ir binary: expected bytes');
  if (view.length < 10) fail('ir binary: truncated payload');
  const isV0 = NATIVE_IR_BINARY_MAGIC_V0.every((value, index) => view[index] === value);
  const isV1 = NATIVE_IR_BINARY_MAGIC_V1.every((value, index) => view[index] === value);
  const isV2 = NATIVE_IR_BINARY_MAGIC_V2.every((value, index) => view[index] === value);
  const isV3 = NATIVE_IR_BINARY_MAGIC_V3.every((value, index) => view[index] === value);
  const isV4 = NATIVE_IR_BINARY_MAGIC_V4.every((value, index) => view[index] === value);
  const isV5 = NATIVE_IR_BINARY_MAGIC_V5.every((value, index) => view[index] === value);
  const isV6 = NATIVE_IR_BINARY_MAGIC_V6.every((value, index) => view[index] === value);
  const isV7 = NATIVE_IR_BINARY_MAGIC_V7.every((value, index) => view[index] === value);
  if (isV0) return decodeNativeIRV0(view);
  if (isV1) return decodeNativeIRV1(view);
  if (isV2) return decodeNativeIRV2(view);
  if (isV3) return decodeNativeIRV3(view);
  if (isV4) return decodeNativeIRV4(view);
  if (isV5) return decodeNativeIRV5(view);
  if (isV6) return decodeNativeIRV6(view);
  if (isV7) return decodeNativeIRV7(view);
  fail('ir binary: unknown magic');
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
const ARM32_RUNTIME_MAX_CALL_DEPTH = 256;
const ARM32_RUNTIME_REGISTER_ARGUMENT_WORDS = 4;
const ARM32_RUNTIME_MAX_ARGUMENT_WORDS = 32;
const ARM32_RUNTIME_MAX_SSA_VALUES = 1000;
const ARM32_RUNTIME_TRAP_EXIT_CODE = 125;
const MAX_ARM32_RUNTIME_ELF_BYTES = 1024 * 1024;

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

function arm32LdrImm(rd, rn, offset) {
  if (!Number.isInteger(rd) || rd < 0 || rd > 15 || !Number.isInteger(rn) || rn < 0 || rn > 15) {
    fail('arm32 runtime: invalid LDR register');
  }
  if (!Number.isInteger(offset) || offset < 0 || offset > 4095) fail('arm32 runtime: LDR offset out of range');
  return (0xE5900000 | (rn << 16) | (rd << 12) | offset) >>> 0;
}

function arm32StrImm(rd, rn, offset) {
  if (!Number.isInteger(rd) || rd < 0 || rd > 15 || !Number.isInteger(rn) || rn < 0 || rn > 15) {
    fail('arm32 runtime: invalid STR register');
  }
  if (!Number.isInteger(offset) || offset < 0 || offset > 4095) fail('arm32 runtime: STR offset out of range');
  return (0xE5800000 | (rn << 16) | (rd << 12) | offset) >>> 0;
}

function arm32LoadStackWord(words, rd, offset) {
  if (!Number.isInteger(offset) || offset < 0 || offset > 0x7fffffff) {
    fail('arm32 runtime: extended stack load offset out of range');
  }
  if (offset <= 4095) {
    words.push(arm32LdrSp(rd, offset));
    return;
  }
  arm32LoadI32(words, 12, offset);
  words.push(0xE08DC00C);
  words.push(arm32LdrImm(rd, 12, 0));
}

function arm32LslImm(rd, rm, shift) {
  if (!Number.isInteger(rd) || rd < 0 || rd > 15 || !Number.isInteger(rm) || rm < 0 || rm > 15 ||
      !Number.isInteger(shift) || shift < 0 || shift > 31) {
    fail('arm32 runtime: invalid LSL operands');
  }
  return (0xE1A00000 | (rd << 12) | (shift << 7) | rm) >>> 0;
}

function arm32LdrbReg(rd, rn, rm) {
  if (!Number.isInteger(rd) || rd < 0 || rd > 15 || !Number.isInteger(rn) || rn < 0 || rn > 15 ||
      !Number.isInteger(rm) || rm < 0 || rm > 15) {
    fail('arm32 runtime: invalid LDRB register');
  }
  return (0xE7D00000 | (rn << 16) | (rd << 12) | rm) >>> 0;
}

function arm32BranchWord(conditionBase, fromAddress, targetAddress) {
  if ((fromAddress & 3) !== 0 || (targetAddress & 3) !== 0) fail('arm32 runtime: unaligned branch address');
  const delta = targetAddress - (fromAddress + 8);
  if ((delta & 3) !== 0) fail('arm32 runtime: branch delta is not word-aligned');
  const words = delta / 4;
  if (words < -0x800000 || words > 0x7fffff) fail('arm32 runtime: branch target out of range');
  return (conditionBase | (words & 0x00ffffff)) >>> 0;
}

function arm32RuntimeParameterWords(fn, recordByName) {
  let words = 0;
  for (const parameter of fn.parameters) {
    const record = recordByName && recordByName.get(parameter.type);
    words += record ? record.fields.length : 1;
  }
  return words;
}

function arm32RuntimeAllowedFunction(fn, recordByName) {
  if ((fn.effect !== 'pure' && fn.effect !== 'state') || fn.requiresCapabilities.length || fn.grantsCapabilities.length) {
    fail("arm32 runtime: unsupported effect/capability function '" + fn.name + "'");
  }
  if (arm32RuntimeParameterWords(fn, recordByName) > ARM32_RUNTIME_MAX_ARGUMENT_WORDS) {
    fail("arm32 runtime: function '" + fn.name + "' exceeds " + ARM32_RUNTIME_MAX_ARGUMENT_WORDS + " argument words");
  }
}

function findRecursiveRuntimeFunctions(ir) {
  const edges = new Map();
  for (const fn of ir.functions) edges.set(fn.name, []);
  for (const fn of ir.functions) {
    for (const inst of fn.instructions) if (inst.op === 'call') edges.get(fn.name).push(inst.target);
  }

  const recursive = new Set();
  for (const fn of ir.functions) {
    const start = fn.name;
    const pending = (edges.get(start) || []).slice();
    const visited = new Set();
    while (pending.length) {
      const target = pending.pop();
      if (target === start) {
        recursive.add(start);
        break;
      }
      if (visited.has(target)) continue;
      visited.add(target);
      for (const next of (edges.get(target) || [])) pending.push(next);
    }
  }
  return recursive;
}
const ARM32_RUNTIME_VALUE_REGS = Object.freeze([4,5,6,7]);
const ARM32_RUNTIME_PUSH_MASK = 0xE92D48F0;
const ARM32_RUNTIME_POP_MASK = 0xE8BD88F0;

function arm32MovReg(rd, rm) {
  if (!Number.isInteger(rd) || rd < 0 || rd > 15 || !Number.isInteger(rm) || rm < 0 || rm > 15) {
    fail('arm32 runtime: invalid MOV register');
  }
  return (0xE1A00000 | (rd << 12) | rm) >>> 0;
}

function arm32Adds(rd, rn, rm) {
  return (0xE0900000 | (rn << 16) | (rd << 12) | rm) >>> 0;
}

function arm32Subs(rd, rn, rm) {
  return (0xE0500000 | (rn << 16) | (rd << 12) | rm) >>> 0;
}

function arm32RuntimeAggregateValues(fn, recordByName) {
  const values = new Set();
  if (!recordByName) return values;
  for (const parameter of fn.parameters) if (recordByName.has(parameter.type)) values.add(parameter.value);
  for (const inst of fn.instructions) if (inst.result != null && recordByName.has(inst.type)) values.add(inst.result);
  return values;
}

function analyzeArm32RuntimeLiveness(fn, recordByName) {
  const definitions = new Map();
  const lastUse = new Map();
  const aggregateValues = arm32RuntimeAggregateValues(fn, recordByName);

  for (const parameter of fn.parameters) {
    if (aggregateValues.has(parameter.value)) continue;
    definitions.set(parameter.value, -1);
    lastUse.set(parameter.value, -1);
  }
  for (const inst of fn.instructions) {
    if (inst.result != null && !aggregateValues.has(inst.result)) {
      if (definitions.has(inst.result)) fail("arm32 runtime: duplicate liveness definition '" + inst.result + "'");
      definitions.set(inst.result, inst.index);
      lastUse.set(inst.result, inst.index);
    }
    for (const arg of inst.args || []) {
      if (aggregateValues.has(arg)) continue;
      if (!definitions.has(arg)) fail("arm32 runtime: liveness saw unknown value '" + arg + "'");
      if (inst.index > lastUse.get(arg)) lastUse.set(arg, inst.index);
    }
  }

  const intervals = Array.from(definitions.keys()).map((value) => ({
    value:value,
    start:definitions.get(value),
    end:lastUse.get(value)
  })).sort((a,b) => a.start - b.start || a.end - b.end || (a.value < b.value ? -1 : (a.value > b.value ? 1 : 0)));

  const active = [];
  const locations = new Map();
  const free = ARM32_RUNTIME_VALUE_REGS.slice();
  let spillCount = 0;

  const releaseExpired = (start) => {
    for (let i = active.length - 1; i >= 0; i -= 1) {
      if (active[i].end < start) {
        free.push(active[i].reg);
        active.splice(i, 1);
      }
    }
    free.sort((a,b) => a-b);
  };

  for (const interval of intervals) {
    releaseExpired(interval.start);
    if (free.length) {
      const reg = free.shift();
      locations.set(interval.value, {kind:'reg', reg:reg});
      active.push({value:interval.value, end:interval.end, reg:reg});
      active.sort((a,b) => a.end - b.end || a.reg - b.reg);
    } else {
      locations.set(interval.value, {kind:'spill', slot:spillCount});
      spillCount += 1;
    }
  }

  if (locations.size > ARM32_RUNTIME_MAX_SSA_VALUES) {
    fail("arm32 runtime: function '" + fn.name + "' has too many SSA values");
  }
  const rawBytes = spillCount * 4;
  const frameBytes = rawBytes === 0 ? 0 : Math.ceil(rawBytes / 8) * 8;
  if (frameBytes > 4096) fail("arm32 runtime: function '" + fn.name + "' frame exceeds V0 stack-offset limit");

  return {
    locations:locations,
    intervals:intervals,
    registerValueCount:Array.from(locations.values()).filter((location) => location.kind === 'reg').length,
    spillCount:spillCount,
    frameBytes:frameBytes
  };
}

function allocateArm32ControlFlowSpills(fn, recordByName) {
  const locations = new Map();
  const aggregateValues = arm32RuntimeAggregateValues(fn, recordByName);
  let spillCount = 0;
  const add = (value) => {
    if (aggregateValues.has(value) || locations.has(value)) return;
    locations.set(value, {kind:'spill', slot:spillCount});
    spillCount += 1;
  };
  for (const parameter of fn.parameters) add(parameter.value);
  for (const inst of fn.instructions) if (inst.result != null) add(inst.result);
  if (locations.size > ARM32_RUNTIME_MAX_SSA_VALUES) {
    fail("arm32 runtime: function '" + fn.name + "' has too many SSA values");
  }
  const rawBytes = spillCount * 4;
  const frameBytes = rawBytes === 0 ? 0 : Math.ceil(rawBytes / 8) * 8;
  if (frameBytes > 4096) fail("arm32 runtime: control-flow frame exceeds V0 stack-offset limit");
  return {
    locations:locations,
    intervals:[],
    registerValueCount:0,
    spillCount:spillCount,
    frameBytes:frameBytes
  };
}

function collectArm32PhiByBlock(fn) {
  const map = new Map();
  let current = null;
  for (const inst of fn.instructions) {
    if (inst.op === 'block.begin') {
      current = inst.label;
      if (!map.has(current)) map.set(current, []);
      continue;
    }
    if (inst.op === 'phi.i32' || inst.op === 'phi.record') {
      if (!current) fail("arm32 runtime: phi outside block in '" + fn.name + "'");
      map.get(current).push(inst);
    }
  }
  return map;
}

function arm32EmitPhiCopies(words, allocation, aggregate, recordByName, phiByBlock, targetLabel, predecessorLabel) {
  const phis = phiByBlock.get(targetLabel) || [];
  const moves = [];

  for (const phi of phis) {
    const incoming = phi.incoming.find((row) => row.label === predecessorLabel);
    if (!incoming) fail("arm32 runtime: phi in '" + targetLabel + "' has no incoming edge from '" + predecessorLabel + "'");

    if (phi.op === 'phi.record') {
      const record = recordByName.get(phi.type);
      const sourceRow = aggregate.slots.get(incoming.value);
      const destinationRow = aggregate.slots.get(phi.result);
      if (!record || !sourceRow || !destinationRow ||
          sourceRow.type !== phi.type || destinationRow.type !== phi.type ||
          sourceRow.fieldCount !== record.fields.length || destinationRow.fieldCount !== record.fields.length) {
        fail("arm32 runtime: malformed aggregate phi storage for '" + phi.type + "'");
      }
      for (let fieldIndex = 0; fieldIndex < record.fields.length; fieldIndex += 1) {
        const sourceSlot = sourceRow.baseSlot + fieldIndex;
        const destinationSlot = destinationRow.baseSlot + fieldIndex;
        if (sourceSlot === destinationSlot) continue;
        moves.push({
          source:{kind:'spill', slot:sourceSlot},
          destination:{kind:'spill', slot:destinationSlot}
        });
      }
      continue;
    }

    const source = arm32RuntimeLocation(allocation, incoming.value);
    const destination = arm32RuntimeLocation(allocation, phi.result);
    if (source.kind !== 'spill' || destination.kind !== 'spill') {
      fail('arm32 runtime: CFG phi copies require spill locations in V0');
    }
    if (source.slot === destination.slot) continue;
    moves.push({
      source:{kind:'spill', slot:source.slot},
      destination:{kind:'spill', slot:destination.slot}
    });
  }

  const sameSource = (source, slot) => source.kind === 'spill' && source.slot === slot;
  const emitMove = (move) => {
    const destinationOffset = move.destination.slot * 4;
    if (destinationOffset > 4095) fail('arm32 runtime: phi destination spill offset exceeds encoding limit');
    if (move.source.kind === 'scratch') {
      words.push(arm32StrSp(12, destinationOffset));
      return;
    }
    const sourceOffset = move.source.slot * 4;
    if (sourceOffset > 4095) fail('arm32 runtime: phi source spill offset exceeds encoding limit');
    words.push(arm32LdrSp(0, sourceOffset));
    words.push(arm32StrSp(0, destinationOffset));
  };

  while (moves.length) {
    let safeIndex = -1;
    for (let i = 0; i < moves.length; i += 1) {
      const destinationSlot = moves[i].destination.slot;
      const destinationStillNeeded = moves.some((other, index) =>
        index !== i && sameSource(other.source, destinationSlot)
      );
      if (!destinationStillNeeded) {
        safeIndex = i;
        break;
      }
    }

    if (safeIndex >= 0) {
      const move = moves.splice(safeIndex, 1)[0];
      emitMove(move);
      continue;
    }

    const cycleBreak = moves[0];
    const savedSlot = cycleBreak.destination.slot;
    const savedOffset = savedSlot * 4;
    if (savedOffset > 4095) fail('arm32 runtime: phi cycle spill offset exceeds encoding limit');
    words.push(arm32LdrSp(12, savedOffset));
    for (const move of moves) {
      if (sameSource(move.source, savedSlot)) move.source = {kind:'scratch'};
    }
  }
}

function arm32Cmp(rn, rm) {
  return (0xE1500000 | (rn << 16) | rm) >>> 0;
}

function arm32ComparisonPatchKind(op) {
  if (op === 'br.cmp.eq') return 'beq';
  if (op === 'br.cmp.ne') return 'bne';
  if (op === 'br.cmp.lt') return 'blt';
  if (op === 'br.cmp.le') return 'ble';
  if (op === 'br.cmp.gt') return 'bgt';
  if (op === 'br.cmp.ge') return 'bge';
  fail("arm32 runtime: unsupported comparison branch '" + op + "'");
}

function arm32RuntimeLocation(allocation, value) {
  const location = allocation.locations.get(value);
  if (!location) fail("arm32 runtime: missing allocation for '" + value + "'");
  return location;
}

function arm32RuntimeSpillOffset(allocation, value) {
  const location = arm32RuntimeLocation(allocation, value);
  if (location.kind !== 'spill') fail("arm32 runtime: value '" + value + "' is not spilled");
  const offset = location.slot * 4;
  if (offset > 4095) fail('arm32 runtime: spill offset exceeds encoding limit');
  return offset;
}

function arm32ReadValue(words, allocation, value, scratchReg) {
  const location = arm32RuntimeLocation(allocation, value);
  if (location.kind === 'reg') return location.reg;
  words.push(arm32LdrSp(scratchReg, arm32RuntimeSpillOffset(allocation, value)));
  return scratchReg;
}

function arm32ReadValueWithSpBias(words, allocation, value, scratchReg, spBias) {
  const location = arm32RuntimeLocation(allocation, value);
  if (location.kind === 'reg') return location.reg;
  arm32LoadStackWord(words, scratchReg, arm32RuntimeSpillOffset(allocation, value) + spBias);
  return scratchReg;
}

function arm32MoveValueToReg(words, allocation, value, targetReg) {
  const sourceReg = arm32ReadValue(words, allocation, value, targetReg);
  if (sourceReg !== targetReg) words.push(arm32MovReg(targetReg, sourceReg));
}

function arm32WriteValue(words, allocation, value, sourceReg) {
  const location = arm32RuntimeLocation(allocation, value);
  if (location.kind === 'reg') {
    if (location.reg !== sourceReg) words.push(arm32MovReg(location.reg, sourceReg));
  } else {
    words.push(arm32StrSp(sourceReg, arm32RuntimeSpillOffset(allocation, value)));
  }
}

function buildArm32RuntimeDivHelperV0() {
  const words = [];
  const patches = [];
  words.push(0xE3510000);
  patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
  words.push(0);
  words.push(0xE0203001);
  words.push(0xE1A02FC0);
  words.push(0xE0200002);
  words.push(0xE0400002);
  words.push(0xE1A02FC1);
  words.push(0xE0211002);
  words.push(0xE0411002);
  words.push(0xE3A02000);
  words.push(0xE3A0C000);

  for (let i = 0; i < 32; i += 1) {
    words.push(0xE1B00080);
    words.push(0xE0ACC00C);
    words.push(0xE15C0001);
    words.push(0x204CC001);
    words.push(0xE0A22002);
  }

  words.push(0xE3130102);
  const negativePatch = {wordIndex:words.length, kind:'bne', targetWordIndex:null};
  patches.push(negativePatch);
  words.push(0);
  words.push(0xE3120102);
  patches.push({wordIndex:words.length, kind:'bne', target:'$trap'});
  words.push(0);
  words.push(0xE1A00002);
  words.push(0xE12FFF1E);
  negativePatch.targetWordIndex = words.length;
  words.push(0xE2620000);
  words.push(0xE12FFF1E);

  return {words:words, patches:patches};
}

function allocateArm32RecordSlots(fn, recordByName, baseSlotCount) {
  const slots = new Map();
  let nextSlot = baseSlotCount;
  const allocate = (value, type) => {
    if (slots.has(value) || !recordByName.has(type)) return;
    const record = recordByName.get(type);
    slots.set(value, {
      type:type,
      baseSlot:nextSlot,
      fieldCount:record.fields.length
    });
    nextSlot += record.fields.length;
  };
  for (const parameter of fn.parameters) allocate(parameter.value, parameter.type);
  for (const inst of fn.instructions) {
    if (inst.result == null) continue;
    allocate(inst.result, inst.type);
  }
  const frameBytesRaw = nextSlot * 4;
  const frameBytes = frameBytesRaw === 0 ? 0 : Math.ceil(frameBytesRaw / 8) * 8;
  if (frameBytes > 4096) fail("arm32 runtime: function '" + fn.name + "' aggregate frame exceeds V0 stack-offset limit");
  return {
    slots:slots,
    totalSlotCount:nextSlot,
    recordWords:nextSlot - baseSlotCount,
    frameBytes:frameBytes
  };
}

function arm32RecordFieldOffset(recordSlots, value, fieldIndex) {
  const row = recordSlots.get(value);
  if (!row) fail("arm32 runtime: record value '" + value + "' has no aggregate storage");
  if (!Number.isInteger(fieldIndex) || fieldIndex < 0 || fieldIndex >= row.fieldCount) {
    fail("arm32 runtime: record field index out of range for '" + value + "'");
  }
  const offset = (row.baseSlot + fieldIndex) * 4;
  if (offset > 4095) fail('arm32 runtime: record spill offset exceeds encoding limit');
  return offset;
}

function compileArm32RuntimeFunctionV0(fn, recordByName, boundedRecursion) {
  arm32RuntimeAllowedFunction(fn, recordByName);
  const controlFlow = fn.instructions.some((inst) => inst.op === 'block.begin');
  const allocation = controlFlow ? allocateArm32ControlFlowSpills(fn, recordByName) : analyzeArm32RuntimeLiveness(fn, recordByName);
  const aggregate = allocateArm32RecordSlots(fn, recordByName, allocation.spillCount);
  const frameBytes = aggregate.frameBytes;
  const allocatorName = controlFlow ? 'cfg-spill-v0' : 'linear-scan-r4-r7-v0';
  const phiByBlock = controlFlow ? collectArm32PhiByBlock(fn) : new Map();
  const words = [];
  const patches = [];
  const blockLabels = new Map();
  let currentBlock = null;

  words.push(ARM32_RUNTIME_PUSH_MASK);
  if (boundedRecursion) {
    arm32LoadI32(words, 12, 1);
    words.push(arm32Adds(11, 11, 12));
    arm32LoadI32(words, 12, ARM32_RUNTIME_MAX_CALL_DEPTH);
    words.push(arm32Cmp(11, 12));
    patches.push({wordIndex:words.length, kind:'bgt', target:'$trap'});
    words.push(0);
  }
  if (frameBytes > 0) {
    arm32LoadI32(words, 12, frameBytes);
    words.push(0xE04DD00C);
  }

  let parameterWord = 0;
  const incomingArgumentWord = () => {
    if (parameterWord < ARM32_RUNTIME_REGISTER_ARGUMENT_WORDS) return parameterWord;
    const stackOffset = frameBytes + 24 + (parameterWord - ARM32_RUNTIME_REGISTER_ARGUMENT_WORDS) * 4;
    arm32LoadStackWord(words, 12, stackOffset);
    return 12;
  };
  for (const parameter of fn.parameters) {
    const recordParameter = recordByName.get(parameter.type);
    if (recordParameter) {
      for (let fieldIndex = 0; fieldIndex < recordParameter.fields.length; fieldIndex += 1) {
        const sourceReg = incomingArgumentWord();
        words.push(arm32StrSp(
          sourceReg,
          arm32RecordFieldOffset(aggregate.slots, parameter.value, fieldIndex)
        ));
        parameterWord += 1;
      }
    } else {
      const sourceReg = incomingArgumentWord();
      arm32WriteValue(words, allocation, parameter.value, sourceReg);
      parameterWord += 1;
    }
  }

  for (const inst of fn.instructions) {
    switch (inst.op) {
      case 'region.begin':
      case 'region.end':
      case 'local.bind':
        break;

      case 'block.begin':
        if (blockLabels.has(inst.label)) fail("arm32 runtime: duplicate block label '" + inst.label + "'");
        blockLabels.set(inst.label, words.length);
        currentBlock = inst.label;
        break;

      case 'phi.i32':
      case 'phi.record':
        if (!controlFlow) fail('arm32 runtime: phi encountered without CFG lowering');
        break;

      case 'br':
        if (!controlFlow || !currentBlock) fail('arm32 runtime: branch encountered outside CFG block');
        arm32EmitPhiCopies(words, allocation, aggregate, recordByName, phiByBlock, inst.target, currentBlock);
        patches.push({wordIndex:words.length, kind:'b', target:'$block:' + inst.target});
        words.push(0);
        break;

      case 'br.cmp.eq':
      case 'br.cmp.ne':
      case 'br.cmp.lt':
      case 'br.cmp.le':
      case 'br.cmp.gt':
      case 'br.cmp.ge':
        if (!controlFlow || !currentBlock) fail('arm32 runtime: conditional branch encountered outside CFG block');
        if ((phiByBlock.get(inst.thenLabel) || []).length || (phiByBlock.get(inst.elseLabel) || []).length) {
          fail('arm32 runtime: conditional edge directly into phi block is not supported in CFG V0');
        }
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        arm32MoveValueToReg(words, allocation, inst.args[1], 1);
        words.push(arm32Cmp(0, 1));
        patches.push({wordIndex:words.length, kind:arm32ComparisonPatchKind(inst.op), target:'$block:' + inst.thenLabel});
        words.push(0);
        patches.push({wordIndex:words.length, kind:'b', target:'$block:' + inst.elseLabel});
        words.push(0);
        break;

      case 'const.i32':
      case 'const.u8': {
        const location = arm32RuntimeLocation(allocation, inst.result);
        const target = location.kind === 'reg' ? location.reg : 0;
        arm32LoadI32(words, target, inst.value);
        if (location.kind === 'spill') {
          words.push(arm32StrSp(target, arm32RuntimeSpillOffset(allocation, inst.result)));
        }
        break;
      }

      case 'copy.i32':
      case 'copy.u8':
      case 'copy.slice.u8':
      case 'copy.arena':
      case 'zext.u8.i32': {
        const sourceReg = arm32ReadValue(words, allocation, inst.args[0], 0);
        arm32WriteValue(words, allocation, inst.result, sourceReg);
        break;
      }

      case 'slice.len':
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        arm32LoadI32(words, 3, 0);
        words.push(arm32Cmp(0, 3));
        patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
        words.push(0);
        words.push(arm32LdrImm(1, 0, 4));
        words.push(arm32Cmp(1, 3));
        patches.push({wordIndex:words.length, kind:'blt', target:'$trap'});
        words.push(0);
        arm32WriteValue(words, allocation, inst.result, 1);
        break;

      case 'slice.get.u8':
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        arm32MoveValueToReg(words, allocation, inst.args[1], 1);
        arm32LoadI32(words, 3, 0);
        words.push(arm32Cmp(0, 3));
        patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
        words.push(0);
        words.push(arm32Cmp(1, 3));
        patches.push({wordIndex:words.length, kind:'blt', target:'$trap'});
        words.push(0);
        words.push(arm32LdrImm(2, 0, 4));
        words.push(arm32Cmp(2, 3));
        patches.push({wordIndex:words.length, kind:'blt', target:'$trap'});
        words.push(0);
        words.push(arm32Cmp(1, 2));
        patches.push({wordIndex:words.length, kind:'bge', target:'$trap'});
        words.push(0);
        words.push(arm32LdrImm(2, 0, 0));
        words.push(arm32Cmp(2, 3));
        patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
        words.push(0);
        words.push(arm32LdrbReg(2, 2, 1));
        arm32WriteValue(words, allocation, inst.result, 2);
        break;

      case 'arena.len':
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        arm32LoadI32(words, 3, 0);
        words.push(arm32Cmp(0, 3));
        patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
        words.push(0);
        words.push(arm32LdrImm(1, 0, 4));
        words.push(arm32Cmp(1, 3));
        patches.push({wordIndex:words.length, kind:'blt', target:'$trap'});
        words.push(0);
        arm32WriteValue(words, allocation, inst.result, 1);
        break;

      case 'arena.load.record': {
        const record = recordByName.get(inst.type);
        if (!record) fail("arm32 runtime: unknown arena record type '" + inst.type + "'");
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        arm32MoveValueToReg(words, allocation, inst.args[1], 1);
        arm32LoadI32(words, 3, 0);
        words.push(arm32Cmp(0, 3));
        patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
        words.push(0);
        words.push(arm32Cmp(1, 3));
        patches.push({wordIndex:words.length, kind:'blt', target:'$trap'});
        words.push(0);
        words.push(arm32LdrImm(2, 0, 4));
        words.push(arm32Cmp(2, 3));
        patches.push({wordIndex:words.length, kind:'blt', target:'$trap'});
        words.push(0);
        words.push(arm32Cmp(1, 2));
        patches.push({wordIndex:words.length, kind:'bge', target:'$trap'});
        words.push(0);
        words.push(arm32LdrImm(2, 0, 0));
        words.push(arm32Cmp(2, 3));
        patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
        words.push(0);
        words.push(arm32LslImm(3, 1, 4));
        words.push(arm32Adds(2, 2, 3));
        for (let fieldIndex = 0; fieldIndex < record.fields.length; fieldIndex += 1) {
          words.push(arm32LdrImm(3, 2, fieldIndex * 4));
          words.push(arm32StrSp(3, arm32RecordFieldOffset(aggregate.slots, inst.result, fieldIndex)));
        }
        break;
      }

      case 'arena.store.record': {
        const record = recordByName.get(inst.recordType);
        if (!record) fail("arm32 runtime: unknown arena record type '" + inst.recordType + "'");
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        arm32MoveValueToReg(words, allocation, inst.args[1], 1);
        arm32LoadI32(words, 3, 0);
        words.push(arm32Cmp(0, 3));
        patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
        words.push(0);
        words.push(arm32Cmp(1, 3));
        patches.push({wordIndex:words.length, kind:'blt', target:'$trap'});
        words.push(0);
        words.push(arm32LdrImm(2, 0, 4));
        words.push(arm32Cmp(2, 3));
        patches.push({wordIndex:words.length, kind:'blt', target:'$trap'});
        words.push(0);
        words.push(arm32Cmp(1, 2));
        patches.push({wordIndex:words.length, kind:'bge', target:'$trap'});
        words.push(0);
        words.push(arm32LdrImm(2, 0, 0));
        words.push(arm32Cmp(2, 3));
        patches.push({wordIndex:words.length, kind:'beq', target:'$trap'});
        words.push(0);
        words.push(arm32LslImm(3, 1, 4));
        words.push(arm32Adds(2, 2, 3));
        for (let fieldIndex = 0; fieldIndex < MAX_SEMNEXIS_FLAT_RECORD_WORDS; fieldIndex += 1) {
          if (fieldIndex < record.fields.length) {
            words.push(arm32LdrSp(3, arm32RecordFieldOffset(aggregate.slots, inst.args[2], fieldIndex)));
          } else {
            arm32LoadI32(words, 3, 0);
          }
          words.push(arm32StrImm(3, 2, fieldIndex * 4));
        }
        arm32WriteValue(words, allocation, inst.result, 1);
        break;
      }

      case 'record.make': {
        const record = recordByName.get(inst.type);
        if (!record || inst.args.length !== record.fields.length) fail("arm32 runtime: malformed record.make for '" + inst.type + "'");
        for (let i = 0; i < record.fields.length; i += 1) {
          const sourceReg = arm32ReadValue(words, allocation, inst.args[i], 0);
          words.push(arm32StrSp(sourceReg, arm32RecordFieldOffset(aggregate.slots, inst.result, i)));
        }
        break;
      }

      case 'copy.record': {
        const record = recordByName.get(inst.type);
        if (!record) fail("arm32 runtime: unknown record type '" + inst.type + "'");
        for (let i = 0; i < record.fields.length; i += 1) {
          words.push(arm32LdrSp(0, arm32RecordFieldOffset(aggregate.slots, inst.args[0], i)));
          words.push(arm32StrSp(0, arm32RecordFieldOffset(aggregate.slots, inst.result, i)));
        }
        break;
      }

      case 'record.get':
        words.push(arm32LdrSp(0, arm32RecordFieldOffset(aggregate.slots, inst.args[0], inst.fieldIndex)));
        arm32WriteValue(words, allocation, inst.result, 0);
        break;

      case 'i32.add.checked':
      case 'i32.sub.checked': {
        const leftReg = arm32ReadValue(words, allocation, inst.args[0], 0);
        const rightScratch = leftReg === 1 ? 0 : 1;
        const rightReg = arm32ReadValue(words, allocation, inst.args[1], rightScratch);
        const resultLocation = arm32RuntimeLocation(allocation, inst.result);
        const resultReg = resultLocation.kind === 'reg' ? resultLocation.reg : 2;
        words.push(inst.op === 'i32.add.checked'
          ? arm32Adds(resultReg, leftReg, rightReg)
          : arm32Subs(resultReg, leftReg, rightReg));
        patches.push({wordIndex:words.length, kind:'bvs', target:'$trap'});
        words.push(0);
        if (resultLocation.kind === 'spill') {
          words.push(arm32StrSp(resultReg, arm32RuntimeSpillOffset(allocation, inst.result)));
        }
        break;
      }

      case 'i32.mul.checked':
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        arm32MoveValueToReg(words, allocation, inst.args[1], 1);
        words.push(0xE0C32190);
        words.push(0xE1A0CFC2);
        words.push(0xE153000C);
        patches.push({wordIndex:words.length, kind:'bne', target:'$trap'});
        words.push(0);
        arm32WriteValue(words, allocation, inst.result, 2);
        break;

      case 'i32.div.checked':
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        arm32MoveValueToReg(words, allocation, inst.args[1], 1);
        patches.push({wordIndex:words.length, kind:'bl', target:'$div'});
        words.push(0);
        arm32WriteValue(words, allocation, inst.result, 0);
        break;

      case 'call': {
        const argumentWords = [];
        for (const arg of inst.args) {
          const aggregateArg = aggregate.slots.get(arg);
          if (aggregateArg) {
            const recordArg = recordByName.get(aggregateArg.type);
            if (!recordArg) fail("arm32 runtime: unknown record call argument type '" + aggregateArg.type + "'");
            for (let fieldIndex = 0; fieldIndex < recordArg.fields.length; fieldIndex += 1) {
              argumentWords.push({kind:'record', value:arg, fieldIndex:fieldIndex});
            }
          } else {
            argumentWords.push({kind:'scalar', value:arg});
          }
        }
        if (argumentWords.length > ARM32_RUNTIME_MAX_ARGUMENT_WORDS) {
          fail("arm32 runtime: call to '" + inst.target + "' exceeds " + ARM32_RUNTIME_MAX_ARGUMENT_WORDS + " argument words");
        }

        const stackWordCount = Math.max(0, argumentWords.length - ARM32_RUNTIME_REGISTER_ARGUMENT_WORDS);
        const rawStackArgumentBytes = stackWordCount * 4;
        const stackArgumentBytes = rawStackArgumentBytes === 0 ? 0 : Math.ceil(rawStackArgumentBytes / 8) * 8;
        if (stackArgumentBytes > 0) {
          arm32LoadI32(words, 12, stackArgumentBytes);
          words.push(0xE04DD00C);
        }

        const loadArgumentWord = (word, targetReg) => {
          if (word.kind === 'record') {
            arm32LoadStackWord(
              words,
              targetReg,
              arm32RecordFieldOffset(aggregate.slots, word.value, word.fieldIndex) + stackArgumentBytes
            );
            return;
          }
          const sourceReg = arm32ReadValueWithSpBias(words, allocation, word.value, targetReg, stackArgumentBytes);
          if (sourceReg !== targetReg) words.push(arm32MovReg(targetReg, sourceReg));
        };

        for (let wordIndex = ARM32_RUNTIME_REGISTER_ARGUMENT_WORDS; wordIndex < argumentWords.length; wordIndex += 1) {
          loadArgumentWord(argumentWords[wordIndex], 12);
          words.push(arm32StrSp(12, (wordIndex - ARM32_RUNTIME_REGISTER_ARGUMENT_WORDS) * 4));
        }
        const registerWordCount = Math.min(argumentWords.length, ARM32_RUNTIME_REGISTER_ARGUMENT_WORDS);
        for (let wordIndex = 0; wordIndex < registerWordCount; wordIndex += 1) {
          loadArgumentWord(argumentWords[wordIndex], wordIndex);
        }

        patches.push({wordIndex:words.length, kind:'bl', target:inst.target});
        words.push(0);
        if (stackArgumentBytes > 0) {
          arm32LoadI32(words, 12, stackArgumentBytes);
          words.push(0xE08DD00C);
        }
        const recordResult = recordByName.get(inst.type);
        if (recordResult) {
          if (recordResult.fields.length > 4) fail("arm32 runtime: record call result exceeds r0-r3 ABI for '" + inst.type + "'");
          for (let i = 0; i < recordResult.fields.length; i += 1) {
            words.push(arm32StrSp(i, arm32RecordFieldOffset(aggregate.slots, inst.result, i)));
          }
        } else {
          arm32WriteValue(words, allocation, inst.result, 0);
        }
        break;
      }

      case 'ret.i32':
      case 'ret.u8':
        arm32MoveValueToReg(words, allocation, inst.args[0], 0);
        if (frameBytes > 0) {
          arm32LoadI32(words, 12, frameBytes);
          words.push(0xE08DD00C);
        }
        words.push(ARM32_RUNTIME_POP_MASK);
        break;

      case 'ret.record': {
        const record = recordByName.get(inst.type);
        if (!record) fail("arm32 runtime: unknown record return type '" + inst.type + "'");
        if (record.fields.length > 4) fail("arm32 runtime: record return exceeds r0-r3 ABI for '" + inst.type + "'");
        for (let i = 0; i < record.fields.length; i += 1) {
          words.push(arm32LdrSp(i, arm32RecordFieldOffset(aggregate.slots, inst.args[0], i)));
        }
        if (frameBytes > 0) {
          arm32LoadI32(words, 12, frameBytes);
          words.push(0xE08DD00C);
        }
        words.push(ARM32_RUNTIME_POP_MASK);
        break;
      }

      case 'intrinsic.clock':
        fail('arm32 runtime: clock requires runtime capability lowering');
        break;

      default:
        fail("arm32 runtime: unsupported IR operation '" + inst.op + "'");
    }
  }

  if (!words.length || words[words.length - 1] !== ARM32_RUNTIME_POP_MASK) {
    fail("arm32 runtime: function '" + fn.name + "' has no canonical return epilogue");
  }

  return {
    name:fn.name,
    frameBytes:frameBytes,
    slotCount:aggregate.totalSlotCount,
    spillSlots:allocation.spillCount,
    registerValues:allocation.registerValueCount,
    allocator:allocatorName,
    parameterCount:fn.parameters.length,
    boundedRecursion:boundedRecursion === true,
    blockCount:blockLabels.size,
    blockLabels:blockLabels,
    words:words,
    patches:patches
  };
}
function arm32RuntimeBranchBase(kind) {
  if (kind === 'bl') return 0xEB000000;
  if (kind === 'b') return 0xEA000000;
  if (kind === 'bvs') return 0x6A000000;
  if (kind === 'bne') return 0x1A000000;
  if (kind === 'beq') return 0x0A000000;
  if (kind === 'blt') return 0xBA000000;
  if (kind === 'ble') return 0xDA000000;
  if (kind === 'bgt') return 0xCA000000;
  if (kind === 'bge') return 0xAA000000;
  fail("arm32 runtime: unsupported branch patch kind '" + kind + "'");
}

function arm32DecodeBranchTarget(word, fromAddress) {
  let imm24 = word & 0x00ffffff;
  if (imm24 & 0x00800000) imm24 |= 0xff000000;
  return (fromAddress + 8 + (imm24 | 0) * 4) >>> 0;
}

function emitArm32RuntimeElfV0(ir) {
  ir.verify();
  if (!ir.functions.length || ir.functions.length > ARM32_RUNTIME_MAX_FUNCTIONS) fail('arm32 runtime: invalid function count');
  const recordByName = new Map(ir.recordTypes.map((record) => [record.name, record]));
  const main = ir.functions.find((fn) => fn.name === 'main');
  if (!main) fail("arm32 runtime: entry function 'main' is required");
  if (main.parameters.length !== 0) fail("arm32 runtime: entry function 'main' must have zero parameters");
  if (recordByName.has(main.returnType)) fail("arm32 runtime: entry function 'main' cannot return a record");
  for (const fn of ir.functions) arm32RuntimeAllowedFunction(fn, recordByName);

  const recursiveFunctions = findRecursiveRuntimeFunctions(ir);
  const compiled = ir.functions.map((fn) => compileArm32RuntimeFunctionV0(fn, recordByName, recursiveFunctions.has(fn.name)));
  const allocatorKinds = Array.from(new Set(compiled.map((fn) => fn.allocator)));
  const allocatorSummary = allocatorKinds.length === 1 ? allocatorKinds[0] : 'mixed-v0';
  const needsDivisionHelper = compiled.some((fn) => fn.patches.some((patch) => patch.target === '$div'));
  const divisionHelper = needsDivisionHelper ? buildArm32RuntimeDivHelperV0() : null;
  const startWords = [];
  if (recursiveFunctions.size) {
    startWords.push(arm32Movw(11, 0), arm32Movt(11, 0));
  }
  const startCallWordIndex = startWords.length;
  startWords.push(0, 0xE3A07001, 0xEF000000);
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
      spillSlots:fn.spillSlots,
      registerValues:fn.registerValues,
      allocator:fn.allocator,
      parameterCount:fn.parameterCount,
      boundedRecursion:fn.boundedRecursion,
      blockCount:fn.blockCount,
      blocks:Array.from(fn.blockLabels.entries()).map(function(row) {
        return {
          label:row[0],
          wordIndex:row[1],
          address:address + row[1] * 4,
          fileOffset:cursor + row[1] * 4
        };
      })
    });
    cursor += fn.words.length * 4;
  }

  const divisionHelperOffset = divisionHelper ? cursor : null;
  const divisionHelperAddress = divisionHelper ? ARM32_ELF_BASE_VADDR + cursor : null;
  if (divisionHelper) cursor += divisionHelper.words.length * 4;

  const trapOffset = cursor;
  const trapAddress = ARM32_ELF_BASE_VADDR + trapOffset;
  cursor += trapWords.length * 4;
  const totalBytes = cursor;
  if (totalBytes > MAX_ARM32_RUNTIME_ELF_BYTES) fail('arm32 runtime: generated ELF exceeds artifact budget');

  const entry = ARM32_ELF_BASE_VADDR + ARM32_ELF_CODE_OFFSET;
  startWords[startCallWordIndex] = arm32BranchWord(0xEB000000, entry + startCallWordIndex * 4, functionAddresses.get('main'));

  for (let f = 0; f < compiled.length; f += 1) {
    const fn = compiled[f];
    const meta = functionMeta[f];
    for (const patch of fn.patches) {
      const from = meta.address + patch.wordIndex * 4;
      let target;
      if (patch.target === '$trap') target = trapAddress;
      else if (patch.target === '$div') target = divisionHelperAddress;
      else if (patch.target.indexOf('$block:') === 0) {
        const label = patch.target.slice('$block:'.length);
        const wordIndex = fn.blockLabels.get(label);
        if (wordIndex == null) fail("arm32 runtime: unknown local block '" + label + "' in function '" + fn.name + "'");
        target = meta.address + wordIndex * 4;
      } else target = functionAddresses.get(patch.target);
      if (target == null) fail("arm32 runtime: missing patch target '" + patch.target + "'");
      fn.words[patch.wordIndex] = arm32BranchWord(arm32RuntimeBranchBase(patch.kind), from, target);
    }
  }

  if (divisionHelper) {
    for (const patch of divisionHelper.patches) {
      const from = divisionHelperAddress + patch.wordIndex * 4;
      const target = patch.target === '$trap'
        ? trapAddress
        : divisionHelperAddress + patch.targetWordIndex * 4;
      divisionHelper.words[patch.wordIndex] = arm32BranchWord(
        arm32RuntimeBranchBase(patch.kind),
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
  if (divisionHelper) {
    for (const word of divisionHelper.words) {
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
    recursiveFunctions:Array.from(recursiveFunctions).sort(),
    maxRecursiveCallDepth:recursiveFunctions.size ? ARM32_RUNTIME_MAX_CALL_DEPTH : 0,
    allocator:allocatorSummary,
    controlFlowLowered:compiled.some((fn) => fn.blockCount > 0),
    divisionHelperAddress:divisionHelperAddress,
    divisionHelperBytes:divisionHelper ? divisionHelper.words.length * 4 : 0,
    trapAddress:trapAddress,
    trapExitCode:ARM32_RUNTIME_TRAP_EXIT_CODE,
    constantEvaluated:false,
    runtimeLowered:true,
    checkedArithmetic:['add','sub','mul','div'],
    executionPolicy:'generated-artifact-not-executed-from-riftfs'
  });
  verifyArm32RuntimeElfStructureV0(artifact);
  return artifact;
}

function verifyArm32RuntimeElfStructureV0(artifact) {
  if (!artifact || artifact.schema !== SEMNEXIS_ARM32_RUNTIME_ELF_SCHEMA) fail('arm32 runtime verify: schema mismatch');
  const bytes = artifact.bytes;
  if (!(bytes instanceof Uint8Array) || bytes.length < ARM32_ELF_CODE_OFFSET + 20) fail('arm32 runtime verify: image too small');
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

  if (!Array.isArray(artifact.recursiveFunctions)) fail('arm32 runtime verify: recursive function metadata missing');
  const recursiveSet = new Set(artifact.recursiveFunctions);
  if (recursiveSet.size !== artifact.recursiveFunctions.length) fail('arm32 runtime verify: duplicate recursive function metadata');
  const hasRecursion = recursiveSet.size > 0;
  if (artifact.maxRecursiveCallDepth !== (hasRecursion ? ARM32_RUNTIME_MAX_CALL_DEPTH : 0)) {
    fail('arm32 runtime verify: recursive call-depth limit metadata mismatch');
  }

  const start = ARM32_ELF_CODE_OFFSET;
  const startCallOffset = hasRecursion ? 8 : 0;
  if (hasRecursion && (readU32LE(bytes, start) !== arm32Movw(11, 0) ||
      readU32LE(bytes, start + 4) !== arm32Movt(11, 0))) {
    fail('arm32 runtime verify: entry call-depth initialization mismatch');
  }
  const startBl = readU32LE(bytes, start + startCallOffset);
  if (((startBl & 0xFF000000) >>> 0) !== 0xEB000000) fail('arm32 runtime verify: entry does not BL main');
  if (readU32LE(bytes, start + startCallOffset + 4) !== 0xE3A07001 ||
      readU32LE(bytes, start + startCallOffset + 8) !== 0xEF000000) {
    fail('arm32 runtime verify: entry exit sequence mismatch');
  }
  const minimumFunctionOffset = ARM32_ELF_CODE_OFFSET + startCallOffset + 12;

  if (artifact.allocator !== 'linear-scan-r4-r7-v0' && artifact.allocator !== 'cfg-spill-v0' && artifact.allocator !== 'mixed-v0') {
    fail('arm32 runtime verify: allocator marker mismatch');
  }

  const seen = new Set();
  for (const fn of artifact.functions) {
    if (!fn.name || seen.has(fn.name)) fail('arm32 runtime verify: duplicate function metadata');
    seen.add(fn.name);
    if ((fn.address & 3) !== 0 || (fn.fileOffset & 3) !== 0 || (fn.bytes & 3) !== 0 || fn.bytes < 8) {
      fail("arm32 runtime verify: malformed function layout for '" + fn.name + "'");
    }
    if (fn.fileOffset < minimumFunctionOffset || fn.fileOffset + fn.bytes > bytes.length) {
      fail("arm32 runtime verify: function range escapes image for '" + fn.name + "'");
    }
    if (fn.address !== ARM32_ELF_BASE_VADDR + fn.fileOffset) fail("arm32 runtime verify: function address mismatch for '" + fn.name + "'");
    if ((fn.frameBytes & 7) !== 0) fail("arm32 runtime verify: unaligned frame for '" + fn.name + "'");
    if (!Number.isInteger(fn.spillSlots) || fn.spillSlots < 0 ||
        !Number.isInteger(fn.slotCount) || fn.slotCount < fn.spillSlots ||
        !Number.isInteger(fn.registerValues) || fn.registerValues < 0 ||
        (fn.allocator !== 'linear-scan-r4-r7-v0' && fn.allocator !== 'cfg-spill-v0') ||
        (artifact.allocator !== 'mixed-v0' && fn.allocator !== artifact.allocator) ||
        typeof fn.boundedRecursion !== 'boolean' || fn.boundedRecursion !== recursiveSet.has(fn.name) ||
        !Number.isInteger(fn.blockCount) || fn.blockCount < 0 || !Array.isArray(fn.blocks) || fn.blocks.length !== fn.blockCount) {
      fail("arm32 runtime verify: allocator metadata mismatch for '" + fn.name + "'");
    }
    const blockNames = new Set();
    for (const block of fn.blocks) {
      if (!block.label || blockNames.has(block.label) || !Number.isInteger(block.wordIndex) || block.wordIndex < 0) {
        fail("arm32 runtime verify: malformed block metadata for '" + fn.name + "'");
      }
      blockNames.add(block.label);
      if (block.address !== fn.address + block.wordIndex * 4 || block.fileOffset !== fn.fileOffset + block.wordIndex * 4 ||
          block.fileOffset < fn.fileOffset || block.fileOffset >= fn.fileOffset + fn.bytes) {
        fail("arm32 runtime verify: block address mismatch for '" + fn.name + "'");
      }
    }
    if (fn.allocator === 'cfg-spill-v0' && (fn.registerValues !== 0 || fn.blockCount === 0)) fail("arm32 runtime verify: CFG spill allocator metadata mismatch in '" + fn.name + "'");
    if (fn.allocator === 'linear-scan-r4-r7-v0' && fn.blockCount !== 0) fail("arm32 runtime verify: linear allocator used on CFG function '" + fn.name + "'");
    const expectedFrame = fn.slotCount === 0 ? 0 : Math.ceil((fn.slotCount * 4) / 8) * 8;
    if (fn.frameBytes !== expectedFrame) fail("arm32 runtime verify: frame/slot metadata mismatch for '" + fn.name + "'");
    if (readU32LE(bytes, fn.fileOffset) !== ARM32_RUNTIME_PUSH_MASK) fail("arm32 runtime verify: prologue mismatch for '" + fn.name + "'");
    if (fn.boundedRecursion) {
      const guardWords = [
        arm32Movw(12, 1),
        arm32Movt(12, 0),
        arm32Adds(11, 11, 12),
        arm32Movw(12, ARM32_RUNTIME_MAX_CALL_DEPTH & 0xffff),
        arm32Movt(12, (ARM32_RUNTIME_MAX_CALL_DEPTH >>> 16) & 0xffff),
        arm32Cmp(11, 12)
      ];
      if (fn.bytes < 36) fail("arm32 runtime verify: recursion guard is truncated in '" + fn.name + "'");
      for (let guardIndex = 0; guardIndex < guardWords.length; guardIndex += 1) {
        if (readU32LE(bytes, fn.fileOffset + 4 + guardIndex * 4) !== guardWords[guardIndex]) {
          fail("arm32 runtime verify: recursion guard mismatch in '" + fn.name + "'");
        }
      }
      const guardBranchOffset = fn.fileOffset + 28;
      const guardBranch = readU32LE(bytes, guardBranchOffset);
      if (((guardBranch & 0xFF000000) >>> 0) !== 0xCA000000 ||
          arm32DecodeBranchTarget(guardBranch, fn.address + 28) !== artifact.trapAddress) {
        fail("arm32 runtime verify: recursion depth trap branch mismatch in '" + fn.name + "'");
      }
    }
    if (readU32LE(bytes, fn.fileOffset + fn.bytes - 4) !== ARM32_RUNTIME_POP_MASK) fail("arm32 runtime verify: epilogue mismatch for '" + fn.name + "'");
  }
  if (!seen.has('main')) fail('arm32 runtime verify: main metadata missing');

  if (artifact.divisionHelperAddress == null) {
    if (artifact.divisionHelperBytes !== 0) fail('arm32 runtime verify: division helper metadata mismatch');
  } else {
    if ((artifact.divisionHelperAddress & 3) !== 0 ||
        !Number.isInteger(artifact.divisionHelperBytes) ||
        artifact.divisionHelperBytes <= 0 ||
        (artifact.divisionHelperBytes & 3) !== 0) {
      fail('arm32 runtime verify: division helper layout mismatch');
    }
    const divOffset = artifact.divisionHelperAddress - ARM32_ELF_BASE_VADDR;
    if (divOffset < ARM32_ELF_CODE_OFFSET || divOffset + artifact.divisionHelperBytes > bytes.length) {
      fail('arm32 runtime verify: division helper escapes image');
    }
    if (readU32LE(bytes, divOffset) !== 0xE3510000) fail('arm32 runtime verify: division helper zero-divisor check missing');
    const zeroBranch = readU32LE(bytes, divOffset + 4);
    if (((zeroBranch & 0xFF000000) >>> 0) !== 0x0A000000 ||
        arm32DecodeBranchTarget(zeroBranch, artifact.divisionHelperAddress + 4) !== artifact.trapAddress) {
      fail('arm32 runtime verify: division-by-zero trap branch mismatch');
    }
  }

  const trapOffset = artifact.trapAddress - ARM32_ELF_BASE_VADDR;
  if (trapOffset < ARM32_ELF_CODE_OFFSET || trapOffset + 16 !== bytes.length) fail('arm32 runtime verify: trap layout mismatch');
  if (readU32LE(bytes, trapOffset) !== arm32Movw(0, artifact.trapExitCode) ||
      readU32LE(bytes, trapOffset + 4) !== arm32Movt(0, 0) ||
      readU32LE(bytes, trapOffset + 8) !== 0xE3A07001 ||
      readU32LE(bytes, trapOffset + 12) !== 0xEF000000) {
    fail('arm32 runtime verify: overflow trap mismatch');
  }

  if (!Array.isArray(artifact.checkedArithmetic) ||
      artifact.checkedArithmetic.join(',') !== 'add,sub,mul,div') {
    fail('arm32 runtime verify: checked arithmetic coverage mismatch');
  }
  const hasControlFlow = artifact.functions.some((fn) => fn.blockCount > 0);
  if (artifact.controlFlowLowered !== hasControlFlow) fail('arm32 runtime verify: control-flow marker mismatch');
  if (artifact.constantEvaluated !== false || artifact.runtimeLowered !== true) fail('arm32 runtime verify: backend mode markers mismatch');
  return true;
}

function verifyArm32RuntimeElfV0(artifact, ir) {
  if (!ir || typeof ir.verify !== 'function') fail('arm32 runtime verify: source IR is required for canonical verification');
  ir.verify();
  verifyArm32RuntimeElfStructureV0(artifact);
  const expected = emitArm32RuntimeElfV0(ir);
  if (artifact.byteLength !== expected.byteLength || artifact.bytes.length !== expected.bytes.length) {
    fail('arm32 runtime verify: machine image length differs from canonical IR lowering');
  }
  for (let i = 0; i < expected.bytes.length; i += 1) {
    if (artifact.bytes[i] !== expected.bytes[i]) {
      fail('arm32 runtime verify: machine image differs from canonical IR lowering at byte ' + i);
    }
  }

  const scalarFields = [
    'entry','allocator','controlFlowLowered','divisionHelperAddress','divisionHelperBytes',
    'trapAddress','trapExitCode','constantEvaluated','runtimeLowered','executionPolicy'
  ];
  for (const field of scalarFields) {
    if (artifact[field] !== expected[field]) fail("arm32 runtime verify: metadata field '" + field + "' differs from canonical lowering");
  }
  if (!Array.isArray(artifact.checkedArithmetic) ||
      artifact.checkedArithmetic.join(',') !== expected.checkedArithmetic.join(',')) {
    fail('arm32 runtime verify: checked arithmetic metadata differs from canonical lowering');
  }
  if (!Array.isArray(artifact.functions) || artifact.functions.length !== expected.functions.length) {
    fail('arm32 runtime verify: function metadata count differs from canonical lowering');
  }
  for (let i = 0; i < expected.functions.length; i += 1) {
    const actualFn = artifact.functions[i], expectedFn = expected.functions[i];
    for (const field of ['name','address','fileOffset','bytes','frameBytes','slotCount','spillSlots','registerValues','allocator','parameterCount','blockCount']) {
      if (actualFn[field] !== expectedFn[field]) fail("arm32 runtime verify: function metadata field '" + field + "' differs from canonical lowering");
    }
    if (!Array.isArray(actualFn.blocks) || actualFn.blocks.length !== expectedFn.blocks.length) {
      fail('arm32 runtime verify: block metadata count differs from canonical lowering');
    }
    for (let b = 0; b < expectedFn.blocks.length; b += 1) {
      const actualBlock = actualFn.blocks[b], expectedBlock = expectedFn.blocks[b];
      for (const field of ['label','wordIndex','address','fileOffset']) {
        if (actualBlock[field] !== expectedBlock[field]) fail("arm32 runtime verify: block metadata field '" + field + "' differs from canonical lowering");
      }
    }
  }
  return true;
}

const SEMNEXIS_SLICE_U8_TYPE = 'Slice<u8>';
const SEMNEXIS_ARENA_TYPE = 'Arena';

function isBootstrapScalarType(type) {
  return type === 'i32' || type === 'u8';
}

function isBootstrapValueType(type) {
  return isBootstrapScalarType(type) || type === SEMNEXIS_SLICE_U8_TYPE || type === SEMNEXIS_ARENA_TYPE;
}

function isBootstrapParameterType(type) {
  return isBootstrapValueType(type);
}

function requireTypeNode(types, type) {
  if (!types.has(type)) fail("type: unknown type '" + type + "'");
  return types.get(type);
}

function typeNodeIsRecord(graph, types, type) {
  if (!types.has(type)) return false;
  const node = types.get(type);
  return graph.attribute(node, 'kind') === 'record';
}

function recordTypeFields(graph, types, type) {
  if (!typeNodeIsRecord(graph, types, type)) fail("type: '" + type + "' is not a record type");
  const node = types.get(type);
  const count = Number(graph.attribute(node, 'field_count'));
  if (!Number.isInteger(count) || count < 1 || count > MAX_SEMNEXIS_FLAT_RECORD_WORDS) {
    fail("type: record '" + type + "' has invalid field metadata");
  }
  const fields = [];
  for (let i = 0; i < count; i += 1) {
    const name = graph.attribute(node, 'field' + i + '_name');
    const fieldType = graph.attribute(node, 'field' + i + '_type');
    if (!name || !isBootstrapScalarType(fieldType)) fail("type: record '" + type + "' has invalid field metadata");
    fields.push({name:name, type:fieldType});
  }
  return fields;
}

function attachExpression(graph, ownerFunction, expressionNode) {
  graph.addEdge(ownerFunction, expressionNode, 'contains_expr');
}

function coerceGraphValue(value, expectedType, graph, ownerFunction, types) {
  if (!expectedType || value.type === expectedType) return value;
  if (value.type === 'u8' && expectedType === 'i32') {
    const node = graph.addNode(NodeKind.Convert, 'u8_to_i32');
    graph.addAttribute(node, 'conversion', 'u8_to_i32');
    graph.addEdge(node, value.node, 'operand');
    graph.addEdge(node, requireTypeNode(types, 'i32'), 'has_type');
    attachExpression(graph, ownerFunction, node);
    return {node:node, type:'i32'};
  }
  return value;
}

function lowerExpr(expr, graph, ownerFunction, symbols, functions, intrinsics, types, expectedType) {
  if (expr.kind === 'Integer') {
    let literalType = 'i32';
    if (expectedType === 'u8') {
      if (expr.integer < 0 || expr.integer > 255) fail('type: integer literal is outside u8 range');
      literalType = 'u8';
    }
    const node = graph.addNode(NodeKind.Constant, 'integer');
    graph.addAttribute(node, 'value', String(expr.integer));
    if (literalType === 'u8') graph.addAttribute(node, 'literal_type', 'u8');
    graph.addEdge(node, requireTypeNode(types, literalType), 'has_type');
    attachExpression(graph, ownerFunction, node);
    return {node:node, type:literalType};
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

  if (expr.kind === 'RecordLiteral') {
    const fields = recordTypeFields(graph, types, expr.typeName);
    const literalFields = new Map(expr.fields.map((field) => [field.name, field.value]));
    if (literalFields.size !== fields.length) fail("type: record literal '" + expr.typeName + "' must initialize every field exactly once");
    const node = graph.addNode(NodeKind.Record, expr.typeName);
    graph.addAttribute(node, 'record_type', expr.typeName);
    graph.addAttribute(node, 'field_count', String(fields.length));
    graph.addEdge(node, requireTypeNode(types, expr.typeName), 'has_type');
    attachExpression(graph, ownerFunction, node);
    for (let i = 0; i < fields.length; i += 1) {
      const field = fields[i];
      if (!literalFields.has(field.name)) fail("type: record literal '" + expr.typeName + "' is missing field '" + field.name + "'");
      let value = lowerExpr(literalFields.get(field.name), graph, ownerFunction, symbols, functions, intrinsics, types, field.type);
      value = coerceGraphValue(value, field.type, graph, ownerFunction, types);
      if (value.type !== field.type) fail("type: record field '" + expr.typeName + '.' + field.name + "' requires " + field.type + ', got ' + value.type);
      graph.addAttribute(node, 'field' + i + '_name', field.name);
      graph.addEdge(node, value.node, 'field' + i);
    }
    for (const literalField of expr.fields) {
      if (!fields.some((field) => field.name === literalField.name)) {
        fail("type: record literal '" + expr.typeName + "' has unknown field '" + literalField.name + "'");
      }
    }
    return {node:node, type:expr.typeName};
  }

  if (expr.kind === 'FieldAccess') {
    const base = lowerExpr(expr.base, graph, ownerFunction, symbols, functions, intrinsics, types);
    if (!typeNodeIsRecord(graph, types, base.type)) fail("type: field access base must be a flat record, got '" + base.type + "'");
    const fields = recordTypeFields(graph, types, base.type);
    const fieldIndex = fields.findIndex((field) => field.name === expr.field);
    if (fieldIndex < 0) fail("type: record '" + base.type + "' has no field '" + expr.field + "'");
    const field = fields[fieldIndex];
    const node = graph.addNode(NodeKind.Field, expr.field);
    graph.addAttribute(node, 'record_type', base.type);
    graph.addAttribute(node, 'field_index', String(fieldIndex));
    graph.addAttribute(node, 'field_name', field.name);
    graph.addEdge(node, base.node, 'base');
    graph.addEdge(node, requireTypeNode(types, field.type), 'has_type');
    attachExpression(graph, ownerFunction, node);
    return {node:node, type:field.type};
  }

  if (expr.kind === 'Binary' || expr.kind === 'Compare') {
    const comparison = expr.kind === 'Compare';
    let left;
    let right;
    if (comparison && expr.left.kind === 'Integer' && expr.right.kind !== 'Integer') {
      right = lowerExpr(expr.right, graph, ownerFunction, symbols, functions, intrinsics, types);
      left = lowerExpr(expr.left, graph, ownerFunction, symbols, functions, intrinsics, types, right.type);
    } else {
      left = lowerExpr(expr.left, graph, ownerFunction, symbols, functions, intrinsics, types);
      right = lowerExpr(expr.right, graph, ownerFunction, symbols, functions, intrinsics, types,
        comparison && expr.right.kind === 'Integer' ? left.type : undefined);
    }
    if (comparison) {
      if (left.type !== right.type &&
          ((left.type === 'u8' && right.type === 'i32') || (left.type === 'i32' && right.type === 'u8'))) {
        left = coerceGraphValue(left, 'i32', graph, ownerFunction, types);
        right = coerceGraphValue(right, 'i32', graph, ownerFunction, types);
      }
      if (!isBootstrapScalarType(left.type) || left.type !== right.type) {
        fail('type: comparison operands must have the same scalar type');
      }
    } else {
      left = coerceGraphValue(left, 'i32', graph, ownerFunction, types);
      right = coerceGraphValue(right, 'i32', graph, ownerFunction, types);
      if (left.type !== 'i32' || right.type !== 'i32') fail('type: checked arithmetic currently requires i32 operands');
    }
    const resultType = comparison ? 'bool' : 'i32';
    if (comparison && !types.has('bool')) fail('type: internal bool type is unavailable');
    const node = graph.addNode(NodeKind.Binary, expr.op);
    graph.addAttribute(node, 'operation', expr.op);
    if (comparison) graph.addAttribute(node, 'semantic_class', 'comparison');
    if (left.type === 'u8') graph.addAttribute(node, 'operand_type', 'u8');
    graph.addEdge(node, left.node, 'lhs');
    graph.addEdge(node, right.node, 'rhs');
    graph.addEdge(node, requireTypeNode(types, resultType), 'has_type');
    attachExpression(graph, ownerFunction, node);
    return {node:node, type:resultType};
  }

  if (expr.kind === 'IfExpr') {
    const condition = lowerExpr(expr.condition, graph, ownerFunction, symbols, functions, intrinsics, types);
    if (condition.type !== 'bool') fail('type: if condition must be a comparison yielding bool');
    const thenValue = lowerExpr(expr.thenExpr, graph, ownerFunction, symbols, functions, intrinsics, types);
    const elseValue = lowerExpr(expr.elseExpr, graph, ownerFunction, symbols, functions, intrinsics, types);
    if (thenValue.type !== elseValue.type) fail('type: if branches must produce the same type');
    if (thenValue.type !== 'i32' && !typeNodeIsRecord(graph, types, thenValue.type)) {
      fail('type: bootstrap if expressions currently produce i32 or flat records only');
    }
    const node = graph.addNode(NodeKind.Conditional, 'if');
    graph.addAttribute(node, 'merge', 'phi');
    graph.addEdge(node, condition.node, 'condition');
    graph.addEdge(node, thenValue.node, 'then_value');
    graph.addEdge(node, elseValue.node, 'else_value');
    graph.addEdge(node, requireTypeNode(types, thenValue.type), 'has_type');
    attachExpression(graph, ownerFunction, node);
    return {node:node, type:thenValue.type};
  }

  if (expr.kind === 'LoopExpr') {
    if (expr.nextValues.length !== expr.states.length) {
      fail('loop: next value count must match carried state count');
    }
    const node = graph.addNode(NodeKind.Loop, 'loop');
    graph.addAttribute(node, 'state_count', String(expr.states.length));
    graph.addAttribute(node, 'merge', 'header_phi');
    attachExpression(graph, ownerFunction, node);

    const stateRows = [];
    const names = new Set();
    for (const state of expr.states) {
      if (names.has(state.name)) fail("loop: duplicate carried state '" + state.name + "'");
      if (symbols.has(state.name)) fail("loop: carried state '" + state.name + "' shadows an existing symbol; bootstrap loops require unique state names");
      names.add(state.name);
      const initializer = lowerExpr(state.initializer, graph, ownerFunction, symbols, functions, intrinsics, types);
      if (initializer.type !== 'i32' && !typeNodeIsRecord(graph, types, initializer.type)) {
        fail("loop: initializer for '" + state.name + "' must be i32 or a flat record");
      }
      const stateNode = graph.addNode(NodeKind.LoopState, state.name);
      graph.addAttribute(stateNode, 'symbol', 'fnnode::' + ownerFunction + '::loop::' + node + '::state::' + state.name);
      graph.addAttribute(stateNode, 'update', 'simultaneous');
      graph.addEdge(node, stateNode, 'carries_state');
      graph.addEdge(stateNode, requireTypeNode(types, initializer.type), 'has_type');
      graph.addEdge(stateNode, initializer.node, 'initialized_by');
      stateRows.push({decl:state, node:stateNode, type:initializer.type});
    }

    const loopSymbols = new Map(symbols);
    for (const state of stateRows) loopSymbols.set(state.decl.name, {node:state.node, type:state.type});

    const condition = lowerExpr(expr.condition, graph, ownerFunction, loopSymbols, functions, intrinsics, types);
    if (condition.type !== 'bool') fail('loop: while condition must be a comparison yielding bool');
    graph.addEdge(node, condition.node, 'condition');

    for (let i = 0; i < stateRows.length; i += 1) {
      let nextValue = lowerExpr(expr.nextValues[i], graph, ownerFunction, loopSymbols, functions, intrinsics, types, stateRows[i].type);
      nextValue = coerceGraphValue(nextValue, stateRows[i].type, graph, ownerFunction, types);
      if (nextValue.type !== stateRows[i].type) {
        fail("loop: next value for '" + stateRows[i].decl.name + "' must remain " + stateRows[i].type + ', got ' + nextValue.type);
      }
      graph.addEdge(stateRows[i].node, nextValue.node, 'next_value');
    }

    let yieldValue = lowerExpr(expr.yieldExpr, graph, ownerFunction, loopSymbols, functions, intrinsics, types, expectedType);
    if (expectedType) yieldValue = coerceGraphValue(yieldValue, expectedType, graph, ownerFunction, types);
    if (yieldValue.type !== 'i32' && !typeNodeIsRecord(graph, types, yieldValue.type)) {
      fail('loop: yield value must be i32 or a flat record');
    }
    if (expectedType && yieldValue.type !== expectedType) {
      fail('loop: yield value must match expected type ' + expectedType + ', got ' + yieldValue.type);
    }
    graph.addEdge(node, yieldValue.node, 'yield_value');
    graph.addEdge(node, requireTypeNode(types, yieldValue.type), 'has_type');
    return {node:node, type:yieldValue.type};
  }

  if (expr.kind !== 'Call') fail('compiler: unknown expression kind');

  const callTypeArguments = Array.isArray(expr.typeArguments) ? expr.typeArguments : [];

  if (functions.has(expr.name)) {
    if (callTypeArguments.length) fail("call: function '" + expr.name + "' does not accept type arguments in the bootstrap");
    const functionInfo = functions.get(expr.name);
    const target = functionInfo.decl;
    if (expr.arguments.length !== target.parameters.length) fail("call: '" + expr.name + "' expects " + target.parameters.length + ' argument(s), got ' + expr.arguments.length);
    const node = graph.addNode(NodeKind.Call, expr.name);
    graph.addEdge(node, functionInfo.node, 'calls');
    graph.addEdge(node, requireTypeNode(types, target.returnType), 'has_type');
    attachExpression(graph, ownerFunction, node);
    for (let i = 0; i < expr.arguments.length; i += 1) {
      const expected = target.parameters[i].type;
      let arg = lowerExpr(expr.arguments[i], graph, ownerFunction, symbols, functions, intrinsics, types, expected);
      arg = coerceGraphValue(arg, expected, graph, ownerFunction, types);
      if (arg.type !== expected) fail('type: argument ' + i + " of '" + expr.name + "' requires " + expected + ', got ' + arg.type);
      graph.addEdge(node, arg.node, 'arg' + i);
    }
    return {node:node, type:target.returnType};
  }

  if (intrinsics.has(expr.name)) {
    const intrinsic = intrinsics.get(expr.name);
    let intrinsicNode = intrinsic.node;
    let intrinsicReturnType = intrinsic.returnType;

    if (intrinsic.genericRecordReturn) {
      if (callTypeArguments.length !== intrinsic.typeArity) {
        fail("call: intrinsic '" + expr.name + "' expects " + intrinsic.typeArity + ' type argument(s), got ' + callTypeArguments.length);
      }
      intrinsicReturnType = callTypeArguments[0];
      if (!typeNodeIsRecord(graph, types, intrinsicReturnType)) {
        fail("type: intrinsic '" + expr.name + "' type argument must be a flat record, got '" + intrinsicReturnType + "'");
      }
      if (!intrinsic.specializations.has(intrinsicReturnType)) {
        const specialization = graph.addNode(NodeKind.Intrinsic, expr.name + '<' + intrinsicReturnType + '>');
        graph.addAttribute(specialization, 'intrinsic_base', expr.name);
        graph.addAttribute(specialization, 'record_type', intrinsicReturnType);
        graph.addAttribute(specialization, 'arity', String(intrinsic.arity));
        for (let i = 0; i < intrinsic.arity; i += 1) {
          if (intrinsic.paramTypes[i]) graph.addAttribute(specialization, 'param' + i + '_type', intrinsic.paramTypes[i]);
          else if (intrinsic.paramKinds && intrinsic.paramKinds[i]) graph.addAttribute(specialization, 'param' + i + '_kind', intrinsic.paramKinds[i]);
        }
        graph.addEdge(specialization, requireTypeNode(types, intrinsicReturnType), 'returns_type');
        graph.addEdge(specialization, intrinsic.effectNode, 'has_effect');
        intrinsic.specializations.set(intrinsicReturnType, specialization);
      }
      intrinsicNode = intrinsic.specializations.get(intrinsicReturnType);
    } else if (callTypeArguments.length) {
      fail("call: intrinsic '" + expr.name + "' does not accept type arguments");
    }

    if (expr.arguments.length !== intrinsic.arity) fail("call: intrinsic '" + expr.name + "' expects " + intrinsic.arity + ' argument(s), got ' + expr.arguments.length);
    const node = graph.addNode(NodeKind.Call, expr.name);
    graph.addAttribute(node, 'intrinsic', 'true');
    graph.addEdge(node, intrinsicNode, 'calls');
    graph.addEdge(node, requireTypeNode(types, intrinsicReturnType), 'has_type');
    attachExpression(graph, ownerFunction, node);
    for (let i = 0; i < expr.arguments.length; i += 1) {
      const expected = intrinsic.paramTypes[i];
      const expectedKind = intrinsic.paramKinds && intrinsic.paramKinds[i];
      let arg = lowerExpr(expr.arguments[i], graph, ownerFunction, symbols, functions, intrinsics, types, expected || undefined);
      if (expected) {
        arg = coerceGraphValue(arg, expected, graph, ownerFunction, types);
        if (arg.type !== expected) fail('type: argument ' + i + " of intrinsic '" + expr.name + "' requires " + expected + ', got ' + arg.type);
      } else if (expectedKind === 'flat_record') {
        if (!typeNodeIsRecord(graph, types, arg.type)) fail('type: argument ' + i + " of intrinsic '" + expr.name + "' requires a flat record, got " + arg.type);
      } else fail("type: intrinsic '" + expr.name + "' has unsupported parameter contract at index " + i);
      graph.addEdge(node, arg.node, 'arg' + i);
    }
    return {node:node, type:intrinsicReturnType};
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
  return graph.edgesFrom(intrinsicNode, 'has_effect').some((edge) => {
    const target = graph.nodes[edge.to];
    return target.kind === NodeKind.Effect && (target.name === 'time' || target.name === 'time_state');
  });
}

function intrinsicHasStateEffect(graph, intrinsicNode) {
  return graph.edgesFrom(intrinsicNode, 'has_effect').some((edge) => {
    const target = graph.nodes[edge.to];
    return target.kind === NodeKind.Effect && (target.name === 'state' || target.name === 'time_state');
  });
}

function functionGrantsTime(graph, functionNode) {
  return graph.edgesFrom(functionNode, 'grants_capability').some((edge) =>
    graph.nodes[edge.to].kind === NodeKind.Capability && graph.nodes[edge.to].name === 'time');
}

function buildPlan(graph) {
  const plan = new ExecutionPlan();
  for (const fn of graph.nodes) {
    if (fn.kind !== NodeKind.Function) continue;
    plan.add('enter_function', fn.name);
    const outgoing = graph.edgesFrom(fn.id);

    for (const edge of outgoing) if (edge.relation === 'requires_capability') plan.add('receive_capability', graph.nodes[edge.to].name);
    for (const edge of outgoing) if (edge.relation === 'grants_capability') plan.add('grant_capability', graph.nodes[edge.to].name);
    for (const edge of outgoing) if (edge.relation === 'executes_in') plan.add('create_region', graph.nodes[edge.to].name);

    for (const edge of outgoing) {
      if (edge.relation !== 'contains') continue;
      const child = graph.nodes[edge.to];
      if (child.kind === NodeKind.Parameter) plan.add('bind_parameter', child.name);
    }
    for (const edge of outgoing) {
      if (edge.relation !== 'contains') continue;
      const child = graph.nodes[edge.to];
      if (child.kind === NodeKind.Local) plan.add('initialize_local', child.name);
    }
    for (const edge of outgoing) {
      if (edge.relation !== 'contains_expr') continue;
      const child = graph.nodes[edge.to];
      if (child.kind === NodeKind.Call) {
        const target = graph.singleEdgeTarget(child.id, 'calls');
        plan.add(graph.nodes[target].kind === NodeKind.Intrinsic ? 'invoke_intrinsic' : 'invoke', graph.nodes[target].name);
      } else if (child.kind === NodeKind.Conditional) {
        plan.add('branch_if', 'comparison');
        plan.add('merge_phi', 'i32');
      } else if (child.kind === NodeKind.Loop) {
        plan.add('loop_header', graph.attribute(child.id, 'state_count') + '_state');
        plan.add('loop_backedge', 'simultaneous_next');
        plan.add('loop_yield', 'i32');
      }
    }

    plan.add('return', fn.name);
    for (const edge of outgoing) if (edge.relation === 'executes_in') plan.add('destroy_region', graph.nodes[edge.to].name);
    plan.add('leave_function', fn.name);
  }
  return plan;
}

export function compileSemnexisV0(source) {
  const sourceText = String(source || '');
  if (sourceText.length > MAX_SEMNEXIS_SOURCE_CHARS) fail('compiler: source exceeds compiler budget');
  const lexer = new Lexer(sourceText);
  const tokens = lexer.scan();
  if (tokens.length > MAX_SEMNEXIS_TOKENS) fail('compiler: token count exceeds compiler budget');
  const parser = new Parser(tokens);
  const module = parser.parseModule();
  validateSemnexisAstBudgets(module);
  const graph = new ProgramGraph();
  const moduleNode = graph.addNode(NodeKind.Module, 'root');

  const types = new Map();
  const i32Type = graph.addNode(NodeKind.Type, 'i32');
  graph.addAttribute(i32Type, 'width', '32');
  graph.addAttribute(i32Type, 'signed', 'true');
  types.set('i32', i32Type);
  const usesSliceU8 = module.functions.some(function(fn) {
    return fn.parameters.some(function(parameter) { return parameter.type === SEMNEXIS_SLICE_U8_TYPE; });
  });
  const usesArena = module.functions.some(function(fn) {
    return fn.parameters.some(function(parameter) { return parameter.type === SEMNEXIS_ARENA_TYPE; });
  });
  const usesU8 = usesSliceU8 || module.structs.some(function(structDecl) {
    return structDecl.fields.some(function(field) { return field.type === 'u8'; });
  }) || module.functions.some(function(fn) {
    return fn.returnType === 'u8' || fn.parameters.some(function(parameter) { return parameter.type === 'u8'; });
  });
  if (usesU8) {
    const u8Type = graph.addNode(NodeKind.Type, 'u8');
    graph.addAttribute(u8Type, 'width', '8');
    graph.addAttribute(u8Type, 'signed', 'false');
    graph.addAttribute(u8Type, 'native_register_representation', 'zero_extended_i32');
    types.set('u8', u8Type);
  }
  const recordTypes = new Map();
  for (const structDecl of module.structs) {
    if (types.has(structDecl.name) || recordTypes.has(structDecl.name)) fail("type: duplicate or reserved struct name '" + structDecl.name + "'");
    if (structDecl.fields.length > MAX_SEMNEXIS_FLAT_RECORD_WORDS) {
      fail("type: struct '" + structDecl.name + "' exceeds flat record ABI limit of " + MAX_SEMNEXIS_FLAT_RECORD_WORDS + ' fields');
    }
    for (const field of structDecl.fields) {
      if (!isBootstrapScalarType(field.type)) fail("type: struct field '" + structDecl.name + '.' + field.name + "' must be i32 or u8");
      if (!types.has(field.type)) fail("type: struct field '" + structDecl.name + '.' + field.name + "' uses unavailable type '" + field.type + "'");
    }
    const recordType = graph.addNode(NodeKind.Type, structDecl.name);
    graph.addAttribute(recordType, 'kind', 'record');
    graph.addAttribute(recordType, 'abi', 'flat_words_v0');
    graph.addAttribute(recordType, 'field_count', String(structDecl.fields.length));
    for (let i = 0; i < structDecl.fields.length; i += 1) {
      graph.addAttribute(recordType, 'field' + i + '_name', structDecl.fields[i].name);
      graph.addAttribute(recordType, 'field' + i + '_type', structDecl.fields[i].type);
    }
    types.set(structDecl.name, recordType);
    recordTypes.set(structDecl.name, {decl:structDecl, node:recordType});
  }
  if (usesSliceU8) {
    const sliceType = graph.addNode(NodeKind.Type, SEMNEXIS_SLICE_U8_TYPE);
    graph.addAttribute(sliceType, 'kind', 'borrowed_slice');
    graph.addAttribute(sliceType, 'element', 'u8');
    graph.addAttribute(sliceType, 'mutability', 'read_only');
    graph.addAttribute(sliceType, 'abi', 'descriptor_ptr_v0');
    graph.addAttribute(sliceType, 'descriptor_alignment', '4');
    graph.addAttribute(sliceType, 'descriptor_layout', 'data_ptr@0,length_i32@4');
    graph.addAttribute(sliceType, 'escape', 'parameter_borrow_only');
    types.set(SEMNEXIS_SLICE_U8_TYPE, sliceType);
  }
  if (usesArena) {
    const arenaType = graph.addNode(NodeKind.Type, SEMNEXIS_ARENA_TYPE);
    graph.addAttribute(arenaType, 'kind', 'bounded_mutable_arena');
    graph.addAttribute(arenaType, 'cell_words', String(MAX_SEMNEXIS_FLAT_RECORD_WORDS));
    graph.addAttribute(arenaType, 'cell_bytes', String(MAX_SEMNEXIS_FLAT_RECORD_WORDS * 4));
    graph.addAttribute(arenaType, 'mutability', 'read_write');
    graph.addAttribute(arenaType, 'abi', 'descriptor_ptr_v0');
    graph.addAttribute(arenaType, 'descriptor_alignment', '4');
    graph.addAttribute(arenaType, 'descriptor_layout', 'data_ptr@0,length_i32@4');
    graph.addAttribute(arenaType, 'escape', 'parameter_borrow_only');
    types.set(SEMNEXIS_ARENA_TYPE, arenaType);
  }
  const usesControlFlow = module.functions.some(function(fn) {
    return fn.locals.some(function(local) { return astExpressionUsesControlFlow(local.initializer); }) ||
      astExpressionUsesControlFlow(fn.returnExpr);
  });
  if (usesControlFlow) {
    const boolType = graph.addNode(NodeKind.Type, 'bool');
    graph.addAttribute(boolType, 'width', '1');
    graph.addAttribute(boolType, 'internal_control_flow_only', 'true');
    types.set('bool', boolType);
  }

  const pureEffect = graph.addNode(NodeKind.Effect, 'pure');
  graph.addAttribute(pureEffect, 'observable_effects', 'none');
  const timeEffect = graph.addNode(NodeKind.Effect, 'time');
  graph.addAttribute(timeEffect, 'observable_effects', 'time');
  let stateEffect = null;
  let timeStateEffect = null;
  if (usesArena) {
    stateEffect = graph.addNode(NodeKind.Effect, 'state');
    graph.addAttribute(stateEffect, 'observable_effects', 'explicit_borrowed_state');
    timeStateEffect = graph.addNode(NodeKind.Effect, 'time_state');
    graph.addAttribute(timeStateEffect, 'observable_effects', 'time,explicit_borrowed_state');
  }
  const timeCapability = graph.addNode(NodeKind.Capability, 'time');
  graph.addAttribute(timeCapability, 'authority', 'clock');

  const intrinsics = new Map();
  const clockIntrinsic = graph.addNode(NodeKind.Intrinsic, 'clock');
  graph.addAttribute(clockIntrinsic, 'arity', '0');
  graph.addEdge(clockIntrinsic, i32Type, 'returns_type');
  graph.addEdge(clockIntrinsic, timeEffect, 'has_effect');
  graph.addEdge(clockIntrinsic, timeCapability, 'requires_capability');
  intrinsics.set('clock', {node:clockIntrinsic, returnType:'i32', arity:0, paramTypes:[]});

  if (usesSliceU8) {
    const sliceLenIntrinsic = graph.addNode(NodeKind.Intrinsic, 'slice_len');
    graph.addAttribute(sliceLenIntrinsic, 'arity', '1');
    graph.addAttribute(sliceLenIntrinsic, 'param0_type', SEMNEXIS_SLICE_U8_TYPE);
    graph.addEdge(sliceLenIntrinsic, i32Type, 'returns_type');
    graph.addEdge(sliceLenIntrinsic, pureEffect, 'has_effect');
    intrinsics.set('slice_len', {node:sliceLenIntrinsic, returnType:'i32', arity:1, paramTypes:[SEMNEXIS_SLICE_U8_TYPE], paramKinds:[null]});

    const sliceGetIntrinsic = graph.addNode(NodeKind.Intrinsic, 'slice_get');
    graph.addAttribute(sliceGetIntrinsic, 'arity', '2');
    graph.addAttribute(sliceGetIntrinsic, 'param0_type', SEMNEXIS_SLICE_U8_TYPE);
    graph.addAttribute(sliceGetIntrinsic, 'param1_type', 'i32');
    graph.addEdge(sliceGetIntrinsic, requireTypeNode(types, 'u8'), 'returns_type');
    graph.addEdge(sliceGetIntrinsic, pureEffect, 'has_effect');
    intrinsics.set('slice_get', {node:sliceGetIntrinsic, returnType:'u8', arity:2, paramTypes:[SEMNEXIS_SLICE_U8_TYPE, 'i32'], paramKinds:[null,null]});
  }

  if (usesArena) {
    const arenaLenIntrinsic = graph.addNode(NodeKind.Intrinsic, 'arena_len');
    graph.addAttribute(arenaLenIntrinsic, 'arity', '1');
    graph.addAttribute(arenaLenIntrinsic, 'param0_type', SEMNEXIS_ARENA_TYPE);
    graph.addEdge(arenaLenIntrinsic, i32Type, 'returns_type');
    graph.addEdge(arenaLenIntrinsic, pureEffect, 'has_effect');
    intrinsics.set('arena_len', {node:arenaLenIntrinsic, returnType:'i32', arity:1, paramTypes:[SEMNEXIS_ARENA_TYPE], paramKinds:[null]});

    const arenaStoreIntrinsic = graph.addNode(NodeKind.Intrinsic, 'arena_store');
    graph.addAttribute(arenaStoreIntrinsic, 'arity', '3');
    graph.addAttribute(arenaStoreIntrinsic, 'param0_type', SEMNEXIS_ARENA_TYPE);
    graph.addAttribute(arenaStoreIntrinsic, 'param1_type', 'i32');
    graph.addAttribute(arenaStoreIntrinsic, 'param2_kind', 'flat_record');
    graph.addEdge(arenaStoreIntrinsic, i32Type, 'returns_type');
    graph.addEdge(arenaStoreIntrinsic, stateEffect, 'has_effect');
    intrinsics.set('arena_store', {node:arenaStoreIntrinsic, returnType:'i32', arity:3, paramTypes:[SEMNEXIS_ARENA_TYPE, 'i32', null], paramKinds:[null,null,'flat_record']});

    intrinsics.set('arena_load', {
      node:null,
      returnType:null,
      arity:2,
      paramTypes:[SEMNEXIS_ARENA_TYPE, 'i32'],
      paramKinds:[null,null],
      genericRecordReturn:true,
      typeArity:1,
      effectNode:stateEffect,
      specializations:new Map()
    });
  }

  const functions = new Map();
  for (const fn of module.functions) {
    if (functions.has(fn.name)) fail("resolve: duplicate function '" + fn.name + "'");
    if (intrinsics.has(fn.name)) fail("resolve: function name conflicts with intrinsic '" + fn.name + "'");
    if (!isBootstrapScalarType(fn.returnType) && !typeNodeIsRecord(graph, types, fn.returnType)) {
      fail("type: bootstrap function '" + fn.name + "' must return i32, u8 or a flat record");
    }
    requireTypeNode(types, fn.returnType);
    for (const parameter of fn.parameters) {
      if (!isBootstrapParameterType(parameter.type) && !typeNodeIsRecord(graph, types, parameter.type)) {
        fail("type: bootstrap parameter '" + parameter.name + "' must be i32, u8, Slice<u8> or a flat record");
      }
      requireTypeNode(types, parameter.type);
    }

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
    const hasSliceParameter = fn.parameters.some(function(parameter) { return parameter.type === SEMNEXIS_SLICE_U8_TYPE; });
    graph.addAttribute(regionNode, 'proof', hasSliceParameter ? 'borrowed_slice_parameter_v0' : 'no_reference_values_v0');
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
      if (!isBootstrapValueType(initializer.type) && !typeNodeIsRecord(graph, types, initializer.type)) {
        fail("type: bootstrap local '" + local.name + "' must resolve to a supported value type");
      }
      const localNode = graph.addNode(NodeKind.Local, local.name);
      graph.addAttribute(localNode, 'symbol', 'fn::' + fn.name + '::local::' + local.name);
      graph.addAttribute(localNode, 'type_proof', 'initializer');
      graph.addEdge(fnNode, localNode, 'contains');
      graph.addEdge(localNode, requireTypeNode(types, initializer.type), 'has_type');
      graph.addEdge(localNode, initializer.node, 'initialized_by');
      symbols.set(local.name, {node:localNode, type:initializer.type});
    }

    let returnValue = lowerExpr(fn.returnExpr, graph, fnNode, symbols, functions, intrinsics, types, fn.returnType);
    returnValue = coerceGraphValue(returnValue, fn.returnType, graph, fnNode, types);
    if (returnValue.type !== fn.returnType) fail("type: function '" + fn.name + "' returns " + fn.returnType + ' but expression is ' + returnValue.type);

    const returnNode = graph.addNode(NodeKind.Return, 'return');
    graph.addEdge(fnNode, returnNode, 'contains');
    graph.addEdge(returnNode, returnValue.node, 'returns_value');
    graph.addEdge(returnNode, requireTypeNode(types, fn.returnType), 'has_type');
  }

  const callGraph = buildFunctionCallGraph(graph);
  const directTime = new Map();
  const transitiveTime = new Map();
  const directState = new Map();
  const transitiveState = new Map();
  const requiresTime = new Map();
  const callers = new Map();

  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    directTime.set(fnNode, false);
    transitiveTime.set(fnNode, false);
    directState.set(fnNode, false);
    transitiveState.set(fnNode, false);
    requiresTime.set(fnNode, false);
    callers.set(fnNode, []);
  }

  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    for (const target of callGraph.get(fnNode) || []) {
      if (graph.nodes[target].kind === NodeKind.Intrinsic) {
        if (intrinsicHasTimeEffect(graph, target)) directTime.set(fnNode, true);
        if (intrinsicHasStateEffect(graph, target)) directState.set(fnNode, true);
      } else if (graph.nodes[target].kind === NodeKind.Function) {
        callers.get(target).push(fnNode);
      }
    }
  }

  const effectQueue = [];
  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    if (!directTime.get(fnNode)) continue;
    transitiveTime.set(fnNode, true);
    effectQueue.push(fnNode);
  }
  for (let cursor = 0; cursor < effectQueue.length; cursor += 1) {
    const target = effectQueue[cursor];
    for (const caller of callers.get(target)) {
      if (transitiveTime.get(caller)) continue;
      transitiveTime.set(caller, true);
      effectQueue.push(caller);
    }
  }

  const stateQueue = [];
  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    if (!directState.get(fnNode)) continue;
    transitiveState.set(fnNode, true);
    stateQueue.push(fnNode);
  }
  for (let cursor = 0; cursor < stateQueue.length; cursor += 1) {
    const target = stateQueue[cursor];
    for (const caller of callers.get(target)) {
      if (transitiveState.get(caller)) continue;
      transitiveState.set(caller, true);
      stateQueue.push(caller);
    }
  }

  const requirementQueue = [];
  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    if (!directTime.get(fnNode) || functionGrantsTime(graph, fnNode)) continue;
    requiresTime.set(fnNode, true);
    requirementQueue.push(fnNode);
  }
  for (let cursor = 0; cursor < requirementQueue.length; cursor += 1) {
    const target = requirementQueue[cursor];
    for (const caller of callers.get(target)) {
      if (requiresTime.get(caller) || functionGrantsTime(graph, caller)) continue;
      requiresTime.set(caller, true);
      requirementQueue.push(caller);
    }
  }

  for (const fn of module.functions) {
    const fnNode = functions.get(fn.name).node;
    const hasTime = transitiveTime.get(fnNode);
    const hasState = transitiveState.get(fnNode);
    if (hasTime && hasState) {
      graph.addAttribute(fnNode, 'effect_proof', 'transitive_time_state_graph_v0');
      graph.addEdge(fnNode, timeStateEffect, 'has_effect');
    } else if (hasTime) {
      graph.addAttribute(fnNode, 'effect_proof', 'transitive_time_graph_v0');
      graph.addEdge(fnNode, timeEffect, 'has_effect');
    } else if (hasState) {
      graph.addAttribute(fnNode, 'effect_proof', 'transitive_state_graph_v0');
      graph.addEdge(fnNode, stateEffect, 'has_effect');
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
  const result = {
    schema:'semnexis-bootstrap-compile-result/1',
    language:SEMNEXIS_LANGUAGE,
    compiler:SEMNEXIS_BOOTSTRAP_VERSION,
    host:'quickjs',
    module:module,
    graph:graph,
    plan:plan,
    ir:ir
  };
  Object.defineProperties(result, {
    graphText:{enumerable:true, get:function() { return graph.dump(); }},
    planText:{enumerable:true, get:function() { return plan.dump(); }},
    irText:{enumerable:true, get:function() { return ir.dump(); }}
  });
  return Object.freeze(result);
}

export function encodeSemnexisNativeIRV0(ir) {
  return encodeNativeIRV0(ir);
}

export function encodeSemnexisNativeIRV1(ir) {
  return encodeNativeIRV1(ir);
}

export function encodeSemnexisNativeIRV2(ir) {
  return encodeNativeIRV2(ir);
}

export function encodeSemnexisNativeIRV3(ir) {
  return encodeNativeIRV3(ir);
}

export function encodeSemnexisNativeIRV4(ir) {
  return encodeNativeIRV4(ir);
}

export function encodeSemnexisNativeIRV5(ir) {
  return encodeNativeIRV5(ir);
}

export function encodeSemnexisNativeIRV6(ir) {
  return encodeNativeIRV6(ir);
}

export function encodeSemnexisNativeIRV7(ir) {
  return encodeNativeIRV7(ir);
}

export function encodeSemnexisNativeIR(ir) {
  return encodeNativeIR(ir);
}

export function decodeSemnexisNativeIRV0(bytes) {
  return decodeNativeIRV0(bytes);
}

export function decodeSemnexisNativeIRV1(bytes) {
  return decodeNativeIRV1(bytes);
}

export function decodeSemnexisNativeIRV2(bytes) {
  return decodeNativeIRV2(bytes);
}

export function decodeSemnexisNativeIRV3(bytes) {
  return decodeNativeIRV3(bytes);
}

export function decodeSemnexisNativeIRV4(bytes) {
  return decodeNativeIRV4(bytes);
}

export function decodeSemnexisNativeIRV5(bytes) {
  return decodeNativeIRV5(bytes);
}

export function decodeSemnexisNativeIRV6(bytes) {
  return decodeNativeIRV6(bytes);
}

export function decodeSemnexisNativeIRV7(bytes) {
  return decodeNativeIRV7(bytes);
}

export function decodeSemnexisNativeIR(bytes) {
  return decodeNativeIR(bytes);
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

export function verifySemnexisArm32RuntimeElfV0(artifact, ir) {
  return verifyArm32RuntimeElfV0(artifact, ir);
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
    irBinaryVersion:NATIVE_IR_BINARY_VERSION_V0,
    irBinaryLatestFormat:'SNIRV7',
    irBinaryLatestVersion:NATIVE_IR_BINARY_VERSION_V7,
    irBinaryCompatibility:'frozen-v0-v1-v2-v3-v4-v5-v6-plus-v7-arena-state-reject-unknown-version-flags-opcodes',
    irGraphNodeSemantics:'advisory-correlation-id-v0',
    arm32ElfSchema:SEMNEXIS_ARM32_ELF_SCHEMA,
    arm32RuntimeElfSchema:SEMNEXIS_ARM32_RUNTIME_ELF_SCHEMA,
    encodeIR:encodeNativeIR,
    decodeIR:decodeNativeIR,
    encodeIRV0:encodeNativeIRV0,
    decodeIRV0:decodeNativeIRV0,
    encodeIRV1:encodeNativeIRV1,
    decodeIRV1:decodeNativeIRV1,
    encodeIRV2:encodeNativeIRV2,
    decodeIRV2:decodeNativeIRV2,
    encodeIRV3:encodeNativeIRV3,
    decodeIRV3:decodeNativeIRV3,
    encodeIRV4:encodeNativeIRV4,
    decodeIRV4:decodeNativeIRV4,
    encodeIRV5:encodeNativeIRV5,
    decodeIRV5:decodeNativeIRV5,
    encodeIRV6:encodeNativeIRV6,
    decodeIRV6:decodeNativeIRV6,
    encodeIRV7:encodeNativeIRV7,
    decodeIRV7:decodeNativeIRV7,
    emitArm32Proof:emitArm32ElfProofV0,
    verifyArm32Proof:verifyArm32ElfProofV0,
    emitArm32Runtime:emitArm32RuntimeElfV0,
    verifyArm32Runtime:verifyArm32RuntimeElfV0,
    compile:compileSemnexisV0,
    inspect:inspectSemnexisV0
  });
}
