import { RIFT_EXEC_FORMAT, RIFT_VM_ABI, prepareRiftExecutable } from './riftvm.js';

export const RIFTPP_CORE_VERSION='0.4.0-bootstrap';
export const RIFTPP_LANGUAGE='riftpp/1';

const MAX_SOURCE_BYTES=256*1024;
const MAX_TOKENS=50000;
const MAX_FUNCTIONS=256;
const MAX_PARAMS=64;
const MAX_LOCALS=512;
const MAX_TYPE_ITEMS=64;
const MAX_NAMED_TYPES=256;
const MAX_VEC_CAPACITY=64;
const MAX_TYPE_DEPTH=32;
const MAX_PARSE_DEPTH=128;
const POISON_NAMES=new Set(['__proto__','prototype','constructor']);
const SUPPORTED_PRIMITIVES=new Set(['unit','bool','u32','s32','string']);
const BUILTIN_GENERIC_TYPES=new Set(['Vec','Option','Result']);
const COMPARABLE_PRIMITIVES=new Set(['unit','bool','u32','s32','string']);
const ORDERED_PRIMITIVES=new Set(['u32','s32','string']);
const PRELUDE_NAMES=new Set(['print']);
const KEYWORDS=new Set(['riftpp','module','use','as','const','struct','enum','fn','let','var','if','else','match','for','in','while','loop','return','break','continue','true','false','and','or','not','allow']);
const RESERVED_FUTURE=new Set(['task','brain','agent','swarm','backend','budget','constraint','optimize','require','unsafe','extern','kernel','tensor','model','train']);
const ASSIGNMENT_OPS=new Set(['=','+=','-=','*=','/=','%=']);
const encoder=new TextEncoder();

function spanOf(token){return Object.freeze({start:token.start,end:token.end,line:token.line,column:token.column});}
function diagnostic(code,message,token,rule,help=''){
  return Object.freeze({code,message,span:token?spanOf(token):Object.freeze({start:0,end:0,line:1,column:1}),rule,help});
}
export class RiftCoreCompileError extends Error{
  constructor(diag){super(`${diag.code}: ${diag.message} at ${diag.span.line}:${diag.span.column}`);this.name='RiftCoreCompileError';this.diagnostic=diag;}
}
function fail(code,message,token,rule,help=''){throw new RiftCoreCompileError(diagnostic(code,message,token,rule,help));}

class Lexer{
  constructor(source){this.source=source;this.pos=0;this.line=1;this.column=1;this.tokens=[];}
  peek(offset=0){return this.source[this.pos+offset]||'';}
  advance(){const ch=this.source[this.pos++]||'';if(ch==='\n'){this.line++;this.column=1;}else this.column++;return ch;}
  token(kind,value,start,line,column){this.tokens.push(Object.freeze({kind,value,start,end:this.pos,line,column}));if(this.tokens.length>MAX_TOKENS)fail('E0003',`source exceeds ${MAX_TOKENS} tokens`,this.tokens[this.tokens.length-1],'host bounds');}
  skipWhitespaceAndComments(){
    for(;;){
      while(/\s/.test(this.peek()))this.advance();
      if(this.peek()==='#'||(this.peek()==='/'&&this.peek(1)==='/')){while(this.peek()&&this.peek()!=='\n')this.advance();continue;}
      if(this.peek()==='/'&&this.peek(1)==='*'){
        const start={start:this.pos,end:this.pos+2,line:this.line,column:this.column};this.advance();this.advance();
        while(this.peek()&&!(this.peek()==='*'&&this.peek(1)==='/')){if(this.peek()==='/'&&this.peek(1)==='*')fail('E0004','nested block comments are not allowed',start,'lexical comments');this.advance();}
        if(!this.peek())fail('E0005','unterminated block comment',start,'lexical comments');this.advance();this.advance();continue;
      }
      break;
    }
  }
  lexString(){
    const start=this.pos,line=this.line,column=this.column;this.advance();let value='';
    while(this.peek()&&this.peek()!=='"'){
      let ch=this.advance();if(ch==='\n')fail('E0006','newline in string literal',{start,end:this.pos,line,column},'string literal');
      if(ch==='\\'){
        const esc=this.advance(),map={'\\':'\\','"':'"','n':'\n','r':'\r','t':'\t'};
        if(!(esc in map))fail('E0007',`unsupported string escape: \\${esc}`,{start,end:this.pos,line,column},'string escape','Use \\, \", \\n, \\r, or \\t.');
        value+=map[esc];
      }else value+=ch;
      if(encoder.encode(value).byteLength>65536)fail('E0008','string literal exceeds 65536 UTF-8 bytes',{start,end:this.pos,line,column},'host bounds');
    }
    if(this.peek()!=='"')fail('E0009','unterminated string literal',{start,end:this.pos,line,column},'string literal');this.advance();this.token('string',value,start,line,column);
  }
  lexNumber(){
    const start=this.pos,line=this.line,column=this.column;let text='';
    if(this.peek()==='0'&&(this.peek(1)==='x'||this.peek(1)==='X')){
      text+=this.advance()+this.advance();while(/[0-9a-fA-F_]/.test(this.peek()))text+=this.advance();const digits=text.slice(2);
      if(!/^[0-9a-fA-F](?:[0-9a-fA-F_]*[0-9a-fA-F])?$/.test(digits)||digits.includes('__'))fail('E0010','invalid hexadecimal integer literal',{start,end:this.pos,line,column},'integer literal');
    }else{
      while(/[0-9_]/.test(this.peek()))text+=this.advance();
      if(text.endsWith('_')||text.includes('__'))fail('E0011','invalid underscore placement in integer literal',{start,end:this.pos,line,column},'integer literal');
      if(this.peek()==='.'||this.peek()==='e'||this.peek()==='E')fail('E0012','floating-point literals are not implemented in the bootstrap Core slice',{start,end:this.pos,line,column},'bootstrap feature gate');
    }
    this.token('number',text,start,line,column);
  }
  lexIdent(){const start=this.pos,line=this.line,column=this.column;let text='';while(/[A-Za-z0-9_]/.test(this.peek()))text+=this.advance();this.token(KEYWORDS.has(text)||RESERVED_FUTURE.has(text)?'keyword':'ident',text,start,line,column);}
  run(){
    while(this.pos<this.source.length){
      this.skipWhitespaceAndComments();if(this.pos>=this.source.length)break;
      const start=this.pos,line=this.line,column=this.column,ch=this.peek();
      if(ch==='"'){this.lexString();continue;}if(/[0-9]/.test(ch)){this.lexNumber();continue;}if(/[A-Za-z_]/.test(ch)){this.lexIdent();continue;}
      const two=ch+this.peek(1);
      if(['->','=>','==','!=','<=','>=','+=','-=','*=','/=','%='].includes(two)){this.advance();this.advance();this.token('symbol',two,start,line,column);continue;}
      if('(){}[],.:=+-*/%<>'.includes(ch)){this.advance();this.token('symbol',ch,start,line,column);continue;}
      fail('E0013',`unexpected character: ${JSON.stringify(ch)}`,{start,end:start+1,line,column},'lexical tokenization');
    }
    this.tokens.push(Object.freeze({kind:'eof',value:'<eof>',start:this.pos,end:this.pos,line:this.line,column:this.column}));return Object.freeze(this.tokens);
  }
}

class Parser{
  constructor(tokens){this.tokens=tokens;this.index=0;this.typeDepth=0;this.expressionDepth=0;this.unaryDepth=0;this.patternDepth=0;this.ifDepth=0;this.blockDepth=0;}
  current(){return this.tokens[this.index];}
  peek(offset=1){return this.tokens[Math.min(this.tokens.length-1,this.index+offset)];}
  previous(){return this.tokens[Math.max(0,this.index-1)];}
  at(value){return this.current().value===value;}
  consume(value){if(this.at(value)){const token=this.current();this.index++;return token;}return null;}
  expect(value,code='E0100'){const token=this.current();if(!this.at(value))fail(code,`expected '${value}', found '${token.value}'`,token,'grammar');this.index++;return token;}
  expectKind(kind,label,code='E0101'){const token=this.current();if(token.kind!==kind)fail(code,`expected ${label}, found '${token.value}'`,token,'grammar');this.index++;return token;}
  node(kind,start,fields={}){const end=this.previous();return Object.freeze({kind,...fields,span:Object.freeze({start:start.start,end:end.end,line:start.line,column:start.column})});}
  modulePath(){const first=this.expectKind('ident','module identifier'),parts=[first.value];while(this.consume('.'))parts.push(this.expectKind('ident','module identifier').value);return parts.join('.');}
  typeRef(){
    const token=this.current();if(++this.typeDepth>MAX_TYPE_DEPTH){this.typeDepth--;fail('E0119',`type nesting exceeds ${MAX_TYPE_DEPTH}`,token,'host bounds');}
    try{
      if(token.kind!=='ident'&&token.kind!=='keyword')fail('E0102','expected type name',token,'type grammar');this.index++;const name=token.value,args=[];let capacityRaw=null;
      if(this.consume('<')){
        if(name==='Vec'){args.push(this.typeRef());this.expect(',');capacityRaw=this.expectKind('number','Vec capacity','E0118').value;this.expect('>');}
        else if(name==='Option'){args.push(this.typeRef());this.expect('>');}
        else if(name==='Result'){args.push(this.typeRef());this.expect(',');args.push(this.typeRef());this.expect('>');}
        else fail('E0118',`generic type '${name}' is not implemented in the bootstrap slice`,token,'bootstrap generic types');
        const end=this.previous();return Object.freeze({kind:'TypeRef',name,args:Object.freeze(args),capacityRaw,span:Object.freeze({start:token.start,end:end.end,line:token.line,column:token.column})});
      }
      return Object.freeze({kind:'TypeRef',name,args:Object.freeze(args),capacityRaw,span:spanOf(token)});
    }finally{this.typeDepth--;}
  }
  parseFile(){
    const start=this.expect('riftpp'),version=this.expectKind('number','language version');if(version.value.replaceAll('_','')!=='1')fail('E0103',`unsupported Rift++ version ${version.value}; expected 1`,version,'file header');
    this.expect('module');const moduleName=this.modulePath();
    if(this.at('use'))fail('E0104','use declarations are not implemented in the bootstrap Core slice',this.current(),'bootstrap feature gate');
    const structs=[],enums=[],functions=[];
    while(this.current().kind!=='eof'){
      if(this.at('struct')){structs.push(this.structDecl());continue;}
      if(this.at('enum')){enums.push(this.enumDecl());continue;}
      if(this.at('fn')){functions.push(this.fnDecl());if(functions.length>MAX_FUNCTIONS)fail('E0106',`function count exceeds ${MAX_FUNCTIONS}`,this.current(),'host bounds');continue;}
      if(this.at('const'))fail('E0105','top-level const is valid Core syntax but not implemented in the bootstrap slice',this.current(),'bootstrap feature gate');
      fail('E0105',`top-level '${this.current().value}' is not implemented in the bootstrap Core slice`,this.current(),'bootstrap feature gate');
    }
    return this.node('File',start,{version:1,module:moduleName,structs:Object.freeze(structs),enums:Object.freeze(enums),functions:Object.freeze(functions)});
  }
  structDecl(){
    const start=this.expect('struct'),name=this.expectKind('ident','struct name');this.expect('{');const fields=[];
    while(!this.at('}')){const field=this.expectKind('ident','struct field name');this.expect(':');fields.push(Object.freeze({kind:'StructField',name:field.value,type:this.typeRef(),span:spanOf(field)}));this.consume(',');if(fields.length>MAX_TYPE_ITEMS)fail('E0113',`struct field count exceeds ${MAX_TYPE_ITEMS}`,field,'host bounds');}
    this.expect('}');return this.node('Struct',start,{name:name.value,fields:Object.freeze(fields)});
  }
  enumDecl(){
    const start=this.expect('enum'),name=this.expectKind('ident','enum name');this.expect('{');const cases=[];
    while(!this.at('}')){const item=this.expectKind('ident','enum case name'),types=[];if(this.consume('(')){if(!this.at(')'))for(;;){types.push(this.typeRef());if(types.length>MAX_TYPE_ITEMS)fail('E0114',`enum payload count exceeds ${MAX_TYPE_ITEMS}`,item,'host bounds');if(!this.consume(','))break;}this.expect(')');}cases.push(Object.freeze({kind:'EnumCase',name:item.value,types:Object.freeze(types),span:spanOf(item)}));this.consume(',');if(cases.length>MAX_TYPE_ITEMS)fail('E0115',`enum case count exceeds ${MAX_TYPE_ITEMS}`,item,'host bounds');}
    this.expect('}');return this.node('Enum',start,{name:name.value,cases:Object.freeze(cases)});
  }
  fnDecl(){
    const start=this.expect('fn'),name=this.expectKind('ident','function name');this.expect('(');const params=[];
    if(!this.at(')'))for(;;){const p=this.expectKind('ident','parameter name');this.expect(':');params.push(Object.freeze({kind:'Param',name:p.value,type:this.typeRef(),span:spanOf(p)}));if(params.length>MAX_PARAMS)fail('E0107',`parameter count exceeds ${MAX_PARAMS}`,p,'host bounds');if(!this.consume(','))break;}
    this.expect(')');let returnType=Object.freeze({kind:'TypeRef',name:'unit',span:spanOf(name)});if(this.consume('->'))returnType=this.typeRef();
    if(this.at('allow'))fail('E0108','function capability clauses are not implemented in the bootstrap Core slice',this.current(),'bootstrap feature gate');
    const body=this.block();return this.node('Function',start,{name:name.value,params:Object.freeze(params),returnType,body});
  }
  block(){const token=this.current();if(++this.blockDepth>MAX_PARSE_DEPTH){this.blockDepth--;fail('E0124',`block nesting exceeds ${MAX_PARSE_DEPTH}`,token,'host bounds');}try{const start=this.expect('{'),statements=[];while(!this.at('}')){if(this.current().kind==='eof')fail('E0109','unterminated block',this.current(),'block grammar');statements.push(this.statement());}this.expect('}');return this.node('Block',start,{statements:Object.freeze(statements)});}finally{this.blockDepth--;}}
  statement(){
    if(this.at('let'))return this.bindingStmt(false);
    if(this.at('var'))return this.bindingStmt(true);
    if(this.at('if'))return this.ifStmt();
    if(this.at('match'))return this.matchStmt();
    if(this.at('while'))return this.whileStmt();
    if(this.at('return'))return this.returnStmt();
    if(this.at('break')){const start=this.expect('break');return this.node('Break',start);}
    if(this.at('continue')){const start=this.expect('continue');return this.node('Continue',start);}
    if(['for','loop'].includes(this.current().value))fail('E0110',`'${this.current().value}' is valid Core syntax but not implemented in the bootstrap slice`,this.current(),'bootstrap feature gate');
    if(this.current().kind==='ident'&&ASSIGNMENT_OPS.has(this.peek().value))return this.assignmentStmt();
    const start=this.current(),expression=this.expression();return this.node('ExprStmt',start,{expression});
  }
  bindingStmt(mutable){const start=this.expect(mutable?'var':'let'),name=this.expectKind('ident','binding name');let type=null;if(this.consume(':'))type=this.typeRef();this.expect('=');const initializer=this.expression();return this.node(mutable?'Var':'Let',start,{name:name.value,type,initializer});}
  assignmentStmt(){const start=this.expectKind('ident','assignment target'),op=this.current();if(!ASSIGNMENT_OPS.has(op.value))fail('E0112',`expected assignment operator, found '${op.value}'`,op,'assignment grammar');this.index++;const expression=this.expression();return this.node('Assign',start,{name:start.value,op:op.value,expression});}
  ifStmt(){const token=this.current();if(++this.ifDepth>MAX_PARSE_DEPTH){this.ifDepth--;fail('E0120',`if/else nesting exceeds ${MAX_PARSE_DEPTH}`,token,'host bounds');}try{const start=this.expect('if'),condition=this.expression(),thenBranch=this.block();let elseBranch=null;if(this.consume('else'))elseBranch=this.at('if')?this.ifStmt():this.block();return this.node('If',start,{condition,thenBranch,elseBranch});}finally{this.ifDepth--;}}
  matchStmt(){const start=this.expect('match'),expression=this.expression();this.expect('{');const arms=[];while(!this.at('}')){if(this.current().kind==='eof')fail('E0116','unterminated match',this.current(),'match grammar');arms.push(this.matchArm());}this.expect('}');return this.node('Match',start,{expression,arms:Object.freeze(arms)});}
  matchArm(){const start=this.current(),pattern=this.pattern();let guard=null;if(this.consume('if'))guard=this.expression();this.expect('=>');const body=this.at('{')?this.block():this.expression();return this.node('MatchArm',start,{pattern,guard,body,bodyIsBlock:body.kind==='Block'});}
  pattern(){
    const token=this.current();if(++this.patternDepth>MAX_PARSE_DEPTH){this.patternDepth--;fail('E0121',`pattern nesting exceeds ${MAX_PARSE_DEPTH}`,token,'host bounds');}
    try{
    if(token.kind==='ident'&&token.value==='_'){this.index++;return Object.freeze({kind:'WildcardPattern',span:spanOf(token)});}
    if(token.value==='true'||token.value==='false'){this.index++;return Object.freeze({kind:'BoolPattern',value:token.value==='true',span:spanOf(token)});}
    if(token.kind==='number'){this.index++;return Object.freeze({kind:'IntPattern',raw:token.value,span:spanOf(token)});}
    if(token.kind==='string'){this.index++;return Object.freeze({kind:'StringPattern',value:token.value,span:spanOf(token)});}
    if(token.kind==='ident'){
      this.index++;const parts=[token.value];while(this.consume('.'))parts.push(this.expectKind('ident','pattern path segment').value);
      let hasPayload=false,args=[];if(this.consume('(')){hasPayload=true;if(!this.at(')'))for(;;){args.push(this.pattern());if(!this.consume(','))break;}this.expect(')');}
      if(parts.length>1||hasPayload)return Object.freeze({kind:'EnumPattern',path:parts.join('.'),args:Object.freeze(args),span:Object.freeze({start:token.start,end:this.previous().end,line:token.line,column:token.column})});
      return Object.freeze({kind:'BindingPattern',name:token.value,span:spanOf(token)});
    }
    fail('E0117',`unsupported match pattern '${token.value}'`,token,'match grammar');
    }finally{this.patternDepth--;}
  }
  whileStmt(){const start=this.expect('while'),condition=this.expression(),body=this.block();return this.node('While',start,{condition,body});}
  returnStmt(){const start=this.expect('return'),expression=this.at('}')?null:this.expression();return this.node('Return',start,{expression});}
  expression(){const token=this.current();if(++this.expressionDepth>MAX_PARSE_DEPTH){this.expressionDepth--;fail('E0122',`expression nesting exceeds ${MAX_PARSE_DEPTH}`,token,'host bounds');}try{return this.logicalOr();}finally{this.expressionDepth--;}}
  logicalOr(){let expr=this.logicalAnd();while(this.at('or')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.logicalAnd(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  logicalAnd(){let expr=this.equality();while(this.at('and')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.equality(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  equality(){let expr=this.comparison();while(this.at('==')||this.at('!=')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.comparison(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  comparison(){let expr=this.additive();while(['<','<=','>','>='].includes(this.current().value)){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.additive(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  additive(){let expr=this.multiplicative();while(this.at('+')||this.at('-')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.multiplicative(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  multiplicative(){let expr=this.unary();while(this.at('*')||this.at('/')||this.at('%')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.unary(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  unary(){const token=this.current();if(++this.unaryDepth>MAX_PARSE_DEPTH){this.unaryDepth--;fail('E0123',`unary nesting exceeds ${MAX_PARSE_DEPTH}`,token,'host bounds');}try{if(this.at('-')||this.at('+')||this.at('not')){const op=this.current();this.index++;const expression=this.unary();return Object.freeze({kind:'Unary',op:op.value,expression,span:Object.freeze({start:op.start,end:expression.span.end,line:op.line,column:op.column})});}return this.postfix();}finally{this.unaryDepth--;}}
  postfix(){
    let expr=this.primary();
    for(;;){
      if(this.consume('(')){const args=[];if(!this.at(')'))for(;;){args.push(this.expression());if(!this.consume(','))break;}const close=this.expect(')');expr=Object.freeze({kind:'Call',callee:expr,args:Object.freeze(args),span:Object.freeze({start:expr.span.start,end:close.end,line:expr.span.line,column:expr.span.column})});continue;}
      if(this.consume('.')){const member=this.expectKind('ident','field or enum case');expr=Object.freeze({kind:'Member',object:expr,member:member.value,span:Object.freeze({start:expr.span.start,end:member.end,line:expr.span.line,column:expr.span.column})});continue;}
      break;
    }
    return expr;
  }
  primary(){
    const token=this.current();
    if(token.kind==='number'){this.index++;return Object.freeze({kind:'IntLiteral',raw:token.value,span:spanOf(token)});}
    if(token.kind==='string'){this.index++;return Object.freeze({kind:'StringLiteral',value:token.value,span:spanOf(token)});}
    if(token.value==='true'||token.value==='false'){this.index++;return Object.freeze({kind:'BoolLiteral',value:token.value==='true',span:spanOf(token)});}
    if(this.at('[')){const start=this.expect('['),items=[];if(!this.at(']'))for(;;){items.push(this.expression());if(!this.consume(','))break;if(this.at(']'))break;}const end=this.expect(']');return Object.freeze({kind:'VecLiteral',items:Object.freeze(items),span:Object.freeze({start:start.start,end:end.end,line:start.line,column:start.column})});}
    if(token.kind==='ident'){
      this.index++;const name=Object.freeze({kind:'Name',name:token.value,span:spanOf(token)});
      if(this.at('{')&&this.peek().kind==='ident'&&this.peek(2).value===':')return this.structLiteral(name,token);
      return name;
    }
    if(this.consume('(')){const expr=this.expression();this.expect(')');return expr;}
    fail('E0111',`expected expression, found '${token.value}'`,token,'expression grammar');
  }
  structLiteral(name,start){
    this.expect('{');const fields=[];
    while(!this.at('}')){const field=this.expectKind('ident','struct initializer field');this.expect(':');fields.push(Object.freeze({name:field.value,expression:this.expression(),span:spanOf(field)}));if(!this.consume(','))break;if(this.at('}'))break;}
    this.expect('}');return this.node('StructLiteral',start,{typeName:name.name,fields:Object.freeze(fields)});
  }
}

function spanToken(span){return {start:span.start,end:span.end,line:span.line,column:span.column};}
function semanticFail(code,message,node,rule,help=''){fail(code,message,spanToken(node.span),rule,help);}
function expectType(actual,expected,node){if(expected&&actual!==expected)semanticFail('E0201',`type mismatch: expected ${expected}, got ${actual}`,node,'type checking');return actual;}
function parseInt(raw,node,type){const clean=raw.replaceAll('_','');let value;try{value=BigInt(clean);}catch{semanticFail('E0202',`invalid integer literal '${raw}'`,node,'numeric semantics');}const bounds=type==='s32'?[-2147483648n,2147483647n]:[0n,4294967295n];if(value<bounds[0]||value>bounds[1])semanticFail('E0203',`${type} literal is out of range`,node,'checked integer semantics');return value.toString();}
function unionFlows(...sets){const out=new Set();for(const set of sets)for(const value of set)out.add(value);return out;}

class Codegen{
  constructor(ast){this.ast=ast;this.constants=[];this.constantMap=new Map();this.structs=new Map();this.enums=new Map();this.genericTypes=new Map();this.topNames=new Set();this.signatures=new Map();this.functions=Object.create(null);}
  constant(type,value){const serialized=type==='unit'?'':String(value),key=`${type}:${serialized}`;if(this.constantMap.has(key))return this.constantMap.get(key);const index=this.constants.length;this.constants.push(type==='unit'?{type:'unit'}:{type,value});this.constantMap.set(key,index);return index;}
  internGeneric(kind,args,capacity=null){const key=kind==='Vec'?`Vec<${args[0]},${capacity}>`:`${kind}<${args.join(',')}>`;if(!this.genericTypes.has(key))this.genericTypes.set(key,Object.freeze({kind,args:Object.freeze([...args]),capacity}));return key;}
  genericInfo(type){return this.genericTypes.get(type)||null;}
  enumTypeInfo(type){
    const local=this.enums.get(type);if(local)return{def:local,runtimeName:type,displayName:type};const info=this.genericInfo(type);if(!info)return null;
    if(info.kind==='Option')return{def:{order:['Some','None'],cases:new Map([['Some',{types:Object.freeze([info.args[0]])}],['None',{types:Object.freeze([])}]])},runtimeName:'Option',displayName:'Option'};
    if(info.kind==='Result')return{def:{order:['Ok','Err'],cases:new Map([['Ok',{types:Object.freeze([info.args[0]])}],['Err',{types:Object.freeze([info.args[1]])}]])},runtimeName:'Result',displayName:'Result'};
    return null;
  }
  typeExists(name){return SUPPORTED_PRIMITIVES.has(name)||this.structs.has(name)||this.enums.has(name)||this.genericTypes.has(name);}
  ensureType(typeRef){
    if(typeRef.name==='Vec'){
      if(typeRef.args?.length!==1||typeRef.capacityRaw==null)semanticFail('E0260','Vec requires Vec<T, N>',typeRef,'bounded collection type');
      const item=this.ensureType(typeRef.args[0]),raw=String(typeRef.capacityRaw).replaceAll('_','');if(!/^[1-9][0-9]*$/.test(raw))semanticFail('E0261','Vec capacity must be a positive decimal integer',typeRef,'bounded collection type');const capacity=Number(raw);if(!Number.isInteger(capacity)||capacity<1||capacity>MAX_VEC_CAPACITY)semanticFail('E0262',`Vec capacity must be 1..${MAX_VEC_CAPACITY}`,typeRef,'bounded collection type');return this.internGeneric('Vec',[item],capacity);
    }
    if(typeRef.name==='Option'){if(typeRef.args?.length!==1)semanticFail('E0263','Option requires Option<T>',typeRef,'bootstrap generic type');return this.internGeneric('Option',[this.ensureType(typeRef.args[0])]);}
    if(typeRef.name==='Result'){if(typeRef.args?.length!==2)semanticFail('E0264','Result requires Result<T, E>',typeRef,'bootstrap generic type');return this.internGeneric('Result',[this.ensureType(typeRef.args[0]),this.ensureType(typeRef.args[1])]);}
    if(typeRef.args?.length)semanticFail('E0265',`generic type '${typeRef.name}' is not implemented`,typeRef,'bootstrap generic type');
    if(!this.typeExists(typeRef.name))semanticFail('E0200',`type '${typeRef.name}' is not implemented or declared in this Core module`,typeRef,'bootstrap type support',`Primitive types supported now: ${[...SUPPORTED_PRIMITIVES].join(', ')}; local struct/enum and Vec/Option/Result types are also supported.`);return typeRef.name;
  }
  collectTypes(){
    if(this.ast.structs.length+this.ast.enums.length>MAX_NAMED_TYPES)semanticFail('E0230',`named type count exceeds ${MAX_NAMED_TYPES}`,this.ast,'host bounds');
    const register=(name,node,kind)=>{if(PRELUDE_NAMES.has(name)||SUPPORTED_PRIMITIVES.has(name)||BUILTIN_GENERIC_TYPES.has(name)||POISON_NAMES.has(name)||this.topNames.has(name))semanticFail('E0230',`duplicate or reserved top-level name '${name}'`,node,'type declaration');this.topNames.add(name);if(kind==='struct')this.structs.set(name,{node,fields:new Map(),order:[]});else this.enums.set(name,{node,cases:new Map(),order:[]});};
    for(const decl of this.ast.structs)register(decl.name,decl,'struct');for(const decl of this.ast.enums)register(decl.name,decl,'enum');
    for(const decl of this.ast.structs){const def=this.structs.get(decl.name);if(!decl.fields.length)semanticFail('E0231',`empty struct '${decl.name}' is not implemented in the bootstrap slice`,decl,'bootstrap structured data');for(const field of decl.fields){if(POISON_NAMES.has(field.name))semanticFail('E0232',`reserved field name '${field.name}' in struct ${decl.name}`,field,'struct declaration');if(def.fields.has(field.name))semanticFail('E0232',`duplicate field '${field.name}' in struct ${decl.name}`,field,'struct declaration');const type=this.ensureType(field.type);def.fields.set(field.name,Object.freeze({type,node:field}));def.order.push(field.name);}}
    for(const decl of this.ast.enums){const def=this.enums.get(decl.name);if(!decl.cases.length)semanticFail('E0233',`enum '${decl.name}' must declare at least one case`,decl,'enum declaration');for(const item of decl.cases){if(POISON_NAMES.has(item.name))semanticFail('E0234',`reserved case name '${item.name}' in enum ${decl.name}`,item,'enum declaration');if(def.cases.has(item.name))semanticFail('E0234',`duplicate case '${item.name}' in enum ${decl.name}`,item,'enum declaration');const types=item.types.map(type=>this.ensureType(type));def.cases.set(item.name,Object.freeze({types:Object.freeze(types),node:item}));def.order.push(item.name);}}
  }
  collectSignatures(){
    for(const fn of this.ast.functions){
      if(PRELUDE_NAMES.has(fn.name)||SUPPORTED_PRIMITIVES.has(fn.name)||BUILTIN_GENERIC_TYPES.has(fn.name)||POISON_NAMES.has(fn.name)||this.topNames.has(fn.name))semanticFail('E0204',`duplicate or reserved top-level name '${fn.name}'`,fn,'name resolution');
      if(this.signatures.has(fn.name))semanticFail('E0204',`duplicate function '${fn.name}'`,fn,'name resolution');
      const params=fn.params.map(p=>this.ensureType(p.type)),returnType=this.ensureType(fn.returnType);this.signatures.set(fn.name,Object.freeze({params:Object.freeze(params),returnType,node:fn}));
    }
    const main=this.signatures.get('main');if(!main)semanticFail('E0205',"entry function 'main' is required",this.ast,'entrypoint');if(main.params.length!==0||main.returnType!=='unit')semanticFail('E0206',"main must have signature fn main() with unit return",main.node,'entrypoint');
  }
  run(){this.collectTypes();this.collectSignatures();for(const fn of this.ast.functions)this.compileFunction(fn);const executable={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],constants:this.constants,functions:this.functions,limits:{maxSteps:100000,maxStack:1024,maxCallDepth:32},metadata:{language:RIFTPP_LANGUAGE,module:this.ast.module,compiler:RIFTPP_CORE_VERSION,structs:[...this.structs.keys()],enums:[...this.enums.keys()]}};prepareRiftExecutable(executable);return executable;}
  compileFunction(fn){
    const sig=this.signatures.get(fn.name),code=[];let nextLocal=0,compileExprDepth=0,compileBlockDepth=0;
    const scopes=[new Map()],loopStack=[];
    const emit=ins=>{code.push(ins);return code.length-1;};
    const patch=(index,target)=>{code[index]={...code[index],target};};
    const anchor=()=>{const target=code.length;emit({op:'const',index:this.constant('unit',null)});emit({op:'pop'});return target;};
    const enterScope=()=>scopes.push(new Map());
    const leaveScope=()=>scopes.pop();
    const resolve=name=>{for(let i=scopes.length-1;i>=0;i--){const value=scopes[i].get(name);if(value)return value;}return null;};
    const allocate=(type,node)=>{if(nextLocal>=MAX_LOCALS)semanticFail('E0217',`local count exceeds ${MAX_LOCALS}`,node,'host bounds');return Object.freeze({slot:nextLocal++,type,mutable:false});};
    const declare=(name,type,mutable,node)=>{if(PRELUDE_NAMES.has(name)||SUPPORTED_PRIMITIVES.has(name)||BUILTIN_GENERIC_TYPES.has(name)||this.structs.has(name)||this.enums.has(name)||POISON_NAMES.has(name))semanticFail('E0216',`'${name}' is reserved by the bootstrap prelude/type namespace`,node,'name resolution');const scope=scopes[scopes.length-1];if(scope.has(name))semanticFail('E0216',`duplicate local '${name}' in the same scope`,node,'name resolution');const local=allocate(type,node);const value=Object.freeze({slot:local.slot,type,mutable});scope.set(name,value);return value;};
    for(let i=0;i<fn.params.length;i++){const p=fn.params[i];if(PRELUDE_NAMES.has(p.name)||SUPPORTED_PRIMITIVES.has(p.name)||BUILTIN_GENERIC_TYPES.has(p.name)||this.structs.has(p.name)||this.enums.has(p.name)||POISON_NAMES.has(p.name))semanticFail('E0207',`'${p.name}' is reserved by the bootstrap prelude/type namespace`,p,'name resolution');if(scopes[0].has(p.name))semanticFail('E0207',`duplicate parameter '${p.name}'`,p,'name resolution');scopes[0].set(p.name,Object.freeze({slot:nextLocal++,type:sig.params[i],mutable:false}));}

    const enumConstructor=(callee,args,node,expected)=>{
      if(callee.kind!=='Member'||callee.object.kind!=='Name')return null;const base=callee.object.name;let type=base,info=this.enumTypeInfo(base);
      if(!info&&['Option','Result'].includes(base)){const generic=this.genericInfo(expected);if(!generic||generic.kind!==base)semanticFail('E0266',`${base}.${callee.member} requires an expected ${base}<...> type`,node,'generic enum construction');type=expected;info=this.enumTypeInfo(type);}
      if(!info)return null;const variant=info.def.cases.get(callee.member);if(!variant)semanticFail('E0235',`enum ${type} has no case '${callee.member}'`,callee,'enum construction');if(args.length!==variant.types.length)semanticFail('E0236',`${base}.${callee.member} expects ${variant.types.length} payload value(s), got ${args.length}`,node,'enum construction');for(let i=0;i<args.length;i++)compileExpr(args[i],variant.types[i]);emit({op:'make_enum',name:info.runtimeName,variant:callee.member,argc:args.length});expectType(type,expected,node);return type;
    };
    const compileVecMethod=(expr,expected)=>{
      if(expr.callee.kind!=='Member')return null;const method=expr.callee.member,receiverType=compileExpr(expr.callee.object,null),info=this.genericInfo(receiverType);if(!info||info.kind!=='Vec')semanticFail('E0267',`method '${method}' requires a Vec receiver; got ${receiverType}`,expr,'bounded collection method');const itemType=info.args[0];
      if(method==='len'){if(expr.args.length)semanticFail('E0268','Vec.len expects no arguments',expr,'bounded collection method');emit({op:'vec_len'});expectType('u32',expected,expr);return'u32';}
      if(method==='get'){if(expr.args.length!==1)semanticFail('E0268','Vec.get expects one u32 index',expr,'bounded collection method');compileExpr(expr.args[0],'u32');emit({op:'vec_get'});const type=this.internGeneric('Option',[itemType]);expectType(type,expected,expr);return type;}
      if(method==='push'){if(expr.args.length!==1)semanticFail('E0268','Vec.push expects one item',expr,'bounded collection method');compileExpr(expr.args[0],itemType);emit({op:'vec_push'});const type=this.internGeneric('Result',[receiverType,'string']);expectType(type,expected,expr);return type;}
      if(method==='set'){if(expr.args.length!==2)semanticFail('E0268','Vec.set expects index and item',expr,'bounded collection method');compileExpr(expr.args[0],'u32');compileExpr(expr.args[1],itemType);emit({op:'vec_set'});const type=this.internGeneric('Result',[receiverType,'string']);expectType(type,expected,expr);return type;}
      semanticFail('E0269',`Vec has no bootstrap method '${method}'`,expr,'bounded collection method');
    };
    const compileExpr=(expr,expected=null)=>{
      if(++compileExprDepth>MAX_PARSE_DEPTH){compileExprDepth--;semanticFail('E0272',`compiler expression nesting exceeds ${MAX_PARSE_DEPTH}`,expr,'host bounds');}
      try{
      if(expr.kind==='IntLiteral'){const type=expected==='s32'?'s32':expected==='u32'?'u32':'u32';if(expected&&!['u32','s32'].includes(expected))semanticFail('E0201',`type mismatch: expected ${expected}, got integer`,expr,'type checking');emit({op:'const',index:this.constant(type,parseInt(expr.raw,expr,type))});return type;}
      if(expr.kind==='StringLiteral'){expectType('string',expected,expr);emit({op:'const',index:this.constant('string',expr.value)});return'string';}
      if(expr.kind==='BoolLiteral'){expectType('bool',expected,expr);emit({op:'const',index:this.constant('bool',expr.value)});return'bool';}
      if(expr.kind==='VecLiteral'){const info=this.genericInfo(expected);if(!info||info.kind!=='Vec')semanticFail('E0270','vector literal requires an expected Vec<T, N> type annotation',expr,'bounded collection literal');if(expr.items.length>info.capacity)semanticFail('E0271',`vector literal has ${expr.items.length} item(s), capacity is ${info.capacity}`,expr,'bounded collection literal');for(const item of expr.items)compileExpr(item,info.args[0]);emit({op:'make_vec',capacity:info.capacity,count:expr.items.length});return expected;}
      if(expr.kind==='Name'){const local=resolve(expr.name);if(local){expectType(local.type,expected,expr);emit({op:'load',index:local.slot});return local.type;}if(this.structs.has(expr.name)||this.enums.has(expr.name)||BUILTIN_GENERIC_TYPES.has(expr.name))semanticFail('E0237',`type '${expr.name}' cannot be used as a value without construction`,expr,'structured value construction');semanticFail('E0208',`unknown name '${expr.name}'`,expr,'name resolution');}
      if(expr.kind==='StructLiteral'){
        const def=this.structs.get(expr.typeName);if(!def)semanticFail('E0238',`unknown struct type '${expr.typeName}'`,expr,'struct construction');expectType(expr.typeName,expected,expr);const seen=new Set();for(const field of expr.fields){if(seen.has(field.name))semanticFail('E0239',`duplicate initializer for field '${field.name}'`,field,'struct construction');seen.add(field.name);const declared=def.fields.get(field.name);if(!declared)semanticFail('E0240',`struct ${expr.typeName} has no field '${field.name}'`,field,'struct construction');compileExpr(field.expression,declared.type);}for(const name of def.order)if(!seen.has(name))semanticFail('E0241',`struct ${expr.typeName} is missing field '${name}'`,expr,'struct construction');emit({op:'make_struct',name:expr.typeName,fields:expr.fields.map(field=>field.name)});return expr.typeName;
      }
      if(expr.kind==='Member'){
        if(expr.object.kind==='Name'){
          const base=expr.object.name;if(this.enums.has(base)){const enumDef=this.enums.get(base),variant=enumDef.cases.get(expr.member);if(!variant)semanticFail('E0235',`enum ${base} has no case '${expr.member}'`,expr,'enum construction');if(variant.types.length)semanticFail('E0242',`${base}.${expr.member} carries ${variant.types.length} payload value(s); call the case constructor`,expr,'enum construction');emit({op:'make_enum',name:base,variant:expr.member,argc:0});expectType(base,expected,expr);return base;}
          if(['Option','Result'].includes(base)){const generic=this.genericInfo(expected);if(!generic||generic.kind!==base)semanticFail('E0266',`${base}.${expr.member} requires an expected ${base}<...> type`,expr,'generic enum construction');const info=this.enumTypeInfo(expected),variant=info.def.cases.get(expr.member);if(!variant)semanticFail('E0235',`enum ${expected} has no case '${expr.member}'`,expr,'enum construction');if(variant.types.length)semanticFail('E0242',`${base}.${expr.member} carries ${variant.types.length} payload value(s); call the case constructor`,expr,'enum construction');emit({op:'make_enum',name:info.runtimeName,variant:expr.member,argc:0});return expected;}
        }
        const objectType=compileExpr(expr.object,null),def=this.structs.get(objectType);if(!def)semanticFail('E0243',`field access requires a struct value; got ${objectType}`,expr,'struct field access');const field=def.fields.get(expr.member);if(!field)semanticFail('E0244',`struct ${objectType} has no field '${expr.member}'`,expr,'struct field access');emit({op:'get_field',name:objectType,field:expr.member});expectType(field.type,expected,expr);return field.type;
      }
      if(expr.kind==='Unary'){
        if(expr.op==='not'){compileExpr(expr.expression,'bool');expectType('bool',expected,expr);emit({op:'not'});return'bool';}
        if(expr.op==='+'){const type=compileExpr(expr.expression,expected);if(!['u32','s32'].includes(type))semanticFail('E0209','unary + requires an integer',expr,'numeric semantics');return type;}
        const type='s32';if(expected&&expected!=='s32')semanticFail('E0210','unary - currently requires s32',expr,'bootstrap numeric support');if(expr.expression.kind==='IntLiteral'){const magnitude=BigInt(expr.expression.raw.replaceAll('_',''));if(magnitude===2147483648n){emit({op:'const',index:this.constant('s32','-2147483648')});return type;}}compileExpr(expr.expression,type);emit({op:'neg'});return type;
      }
      if(expr.kind==='Binary'){
        if(expr.op==='and'){expectType('bool',expected,expr);compileExpr(expr.left,'bool');emit({op:'dup'});const falseJump=emit({op:'jump_if_false',target:-1});emit({op:'pop'});compileExpr(expr.right,'bool');const end=anchor();patch(falseJump,end);return'bool';}
        if(expr.op==='or'){expectType('bool',expected,expr);compileExpr(expr.left,'bool');emit({op:'dup'});const evalRight=emit({op:'jump_if_false',target:-1}),done=emit({op:'jump',target:-1});const rightTarget=code.length;emit({op:'pop'});patch(evalRight,rightTarget);compileExpr(expr.right,'bool');const end=anchor();patch(done,end);return'bool';}
        if(expr.op==='=='||expr.op==='!='){const left=compileExpr(expr.left,null),right=compileExpr(expr.right,left);if(left!==right)semanticFail('E0201',`comparison operands differ: ${left} and ${right}`,expr,'type checking');if(!COMPARABLE_PRIMITIVES.has(left))semanticFail('E0245',`equality for composite type ${left} is not defined in the bootstrap slice`,expr,'bootstrap comparison semantics');expectType('bool',expected,expr);emit({op:expr.op==='=='?'eq':'ne'});return'bool';}
        if(['<','<=','>','>='].includes(expr.op)){const left=compileExpr(expr.left,null),right=compileExpr(expr.right,left);if(left!==right)semanticFail('E0201',`comparison operands differ: ${left} and ${right}`,expr,'type checking');if(!ORDERED_PRIMITIVES.has(left))semanticFail('E0246',`ordered comparison requires u32, s32 or string; got ${left}`,expr,'comparison semantics');expectType('bool',expected,expr);emit({op:{'<':'lt','<=':'le','>':'gt','>=':'ge'}[expr.op]});return'bool';}
        const preferred=expected&&['u32','s32','string'].includes(expected)?expected:null,left=compileExpr(expr.left,preferred),right=compileExpr(expr.right,left);if(left!==right)semanticFail('E0201',`binary operands differ: ${left} and ${right}`,expr,'type checking');if(expr.op==='+'&&left==='string'){expectType('string',expected,expr);emit({op:'concat'});return'string';}if(!['u32','s32'].includes(left))semanticFail('E0211',`operator '${expr.op}' requires integer operands`,expr,'numeric semantics');expectType(left,expected,expr);emit({op:{'+':'add','-':'sub','*':'mul','/':'div','%':'mod'}[expr.op]});return left;
      }
      if(expr.kind==='Call'){
        const constructed=enumConstructor(expr.callee,expr.args,expr,expected);if(constructed)return constructed;if(expr.callee.kind==='Member')return compileVecMethod(expr,expected);
        if(expr.callee.kind!=='Name')semanticFail('E0212','bootstrap calls require a direct function name, enum case constructor or Vec method',expr,'call semantics');const name=expr.callee.name;
        if(name==='print'){if(expr.args.length!==1)semanticFail('E0213','print expects exactly one argument',expr,'bootstrap prelude');compileExpr(expr.args[0],null);emit({op:'print'});emit({op:'const',index:this.constant('unit',null)});expectType('unit',expected,expr);return'unit';}
        const target=this.signatures.get(name);if(!target)semanticFail('E0214',`unknown function '${name}'`,expr,'name resolution');if(expr.args.length!==target.params.length)semanticFail('E0215',`${name} expects ${target.params.length} arguments, got ${expr.args.length}`,expr,'call semantics');for(let i=0;i<expr.args.length;i++)compileExpr(expr.args[i],target.params[i]);emit({op:'call',name,argc:expr.args.length});expectType(target.returnType,expected,expr);return target.returnType;
      }
      semanticFail('E0299',`unsupported expression node '${expr.kind}'`,expr,'compiler invariant');
      }finally{compileExprDepth--;}
    };

    const compileBlock=(block,newScope=true)=>{if(++compileBlockDepth>MAX_PARSE_DEPTH){compileBlockDepth--;semanticFail('E0273',`compiler block nesting exceeds ${MAX_PARSE_DEPTH}`,block,'host bounds');}if(newScope)enterScope();try{let flows=new Set(['normal']);for(const stmt of block.statements){if(!flows.has('normal'))semanticFail('E0227','unreachable statement',stmt,'control-flow analysis');const stmtFlow=compileStatement(stmt),next=new Set([...flows].filter(value=>value!=='normal'));for(const value of stmtFlow)next.add(value);flows=next;}return flows;}finally{if(newScope)leaveScope();compileBlockDepth--;}};
    const compileIf=stmt=>{compileExpr(stmt.condition,'bool');const falseJump=emit({op:'jump_if_false',target:-1});const thenFlow=compileBlock(stmt.thenBranch,true);if(!stmt.elseBranch){const end=anchor();patch(falseJump,end);return unionFlows(thenFlow,new Set(['normal']));}let doneJump=null;if(thenFlow.has('normal'))doneJump=emit({op:'jump',target:-1});const elseTarget=anchor();patch(falseJump,elseTarget);const elseFlow=stmt.elseBranch.kind==='If'?compileIf(stmt.elseBranch):compileBlock(stmt.elseBranch,true);const end=anchor();if(doneJump!==null)patch(doneJump,end);return unionFlows(thenFlow,elseFlow);};
    const compileWhile=stmt=>{const conditionTarget=code.length;compileExpr(stmt.condition,'bool');const exitJump=emit({op:'jump_if_false',target:-1});const loop={breaks:[],continueTarget:conditionTarget};loopStack.push(loop);const bodyFlow=compileBlock(stmt.body,true);loopStack.pop();if(bodyFlow.has('normal'))emit({op:'jump',target:conditionTarget});const exitTarget=anchor();patch(exitJump,exitTarget);for(const index of loop.breaks)patch(index,exitTarget);const out=new Set(['normal']);if(bodyFlow.has('return'))out.add('return');return out;};
    const compileAssignment=stmt=>{const local=resolve(stmt.name);if(!local)semanticFail('E0221',`unknown assignment target '${stmt.name}'`,stmt,'name resolution');if(!local.mutable)semanticFail('E0222',`cannot assign to immutable binding '${stmt.name}'`,stmt,'mutability');if(stmt.op==='='){compileExpr(stmt.expression,local.type);emit({op:'store',index:local.slot});return;}emit({op:'load',index:local.slot});compileExpr(stmt.expression,local.type);if(stmt.op==='+='&&local.type==='string')emit({op:'concat'});else{if(!['u32','s32'].includes(local.type))semanticFail('E0223',`compound assignment '${stmt.op}' requires integer operands (or string +=)`,stmt,'numeric semantics');emit({op:{'+=':'add','-=':'sub','*=':'mul','/=':'div','%=':'mod'}[stmt.op]});}emit({op:'store',index:local.slot});};

    const resolveEnumPattern=(pattern,enumType)=>{
      const info=this.enumTypeInfo(enumType);if(!info)semanticFail('E0251',`match currently supports bool or enum values; got ${enumType}`,pattern,'bootstrap match support');const def=info.def,parts=pattern.path.split('.');let variantName;if(parts.length===1)variantName=parts[0];else if(parts.length===2&&parts[0]===info.displayName)variantName=parts[1];else semanticFail('E0247',`enum pattern '${pattern.path}' does not name a case of ${enumType}`,pattern,'match pattern');const variant=def.cases.get(variantName);if(!variant)semanticFail('E0248',`enum ${enumType} has no case '${variantName}'`,pattern,'match pattern');if(pattern.args.length!==variant.types.length)semanticFail('E0249',`${info.displayName}.${variantName} pattern expects ${variant.types.length} payload pattern(s), got ${pattern.args.length}`,pattern,'match pattern');for(const arg of pattern.args)if(!['BindingPattern','WildcardPattern'].includes(arg.kind))semanticFail('E0250','bootstrap enum payload patterns support bindings or _ only',arg,'bootstrap match pattern');return{variantName,variant,runtimeName:info.runtimeName};
    };
    const validateMatch=(stmt,type)=>{
      const enumInfo=this.enumTypeInfo(type),enumDef=enumInfo?.def,covered=new Set();let catchAll=false;
      if(type!=='bool'&&!enumDef)semanticFail('E0251',`match currently supports bool or enum values; got ${type}`,stmt,'bootstrap match support');
      for(const arm of stmt.arms){if(catchAll)semanticFail('E0252','unreachable match arm after unconditional catch-all',arm,'match reachability');const p=arm.pattern,unguarded=!arm.guard;
        if(type!=='bool'&&p.kind==='BindingPattern'&&enumDef.cases.has(p.name)){const variant=enumDef.cases.get(p.name);if(variant.types.length)semanticFail('E0249',`${enumInfo.displayName}.${p.name} pattern expects ${variant.types.length} payload pattern(s)`,p,'match pattern');if(unguarded&&covered.has(p.name))semanticFail('E0254',`duplicate unconditional match arm '${p.name}'`,p,'match reachability');if(unguarded)covered.add(p.name);continue;}
        if(p.kind==='WildcardPattern'||p.kind==='BindingPattern'){if(unguarded)catchAll=true;continue;}
        if(type==='bool'){
          if(p.kind!=='BoolPattern')semanticFail('E0253','bool match arms must use true, false, a binding, or _',p,'match pattern');const key=String(p.value);if(unguarded&&covered.has(key))semanticFail('E0254',`duplicate unconditional match arm '${key}'`,p,'match reachability');if(unguarded)covered.add(key);continue;
        }
        if(p.kind!=='EnumPattern')semanticFail('E0255',`enum ${type} match arms must use enum cases, a binding, or _`,p,'match pattern');const resolved=resolveEnumPattern(p,type);if(unguarded&&covered.has(resolved.variantName))semanticFail('E0254',`duplicate unconditional match arm '${resolved.variantName}'`,p,'match reachability');if(unguarded)covered.add(resolved.variantName);
      }
      if(!catchAll){const missing=type==='bool'?['true','false'].filter(v=>!covered.has(v)):enumDef.order.filter(v=>!covered.has(v));if(missing.length)semanticFail('E0256',`non-exhaustive match on ${type}; missing ${missing.join(', ')}`,stmt,'match exhaustiveness','Add the missing cases or an unconditional _ arm.');}
    };
    const compileMatch=stmt=>{
      const scrutineeType=compileExpr(stmt.expression,null);validateMatch(stmt,scrutineeType);const temp=allocate(scrutineeType,stmt);emit({op:'store',index:temp.slot});const endJumps=[],allFlows=[],enumInfo=this.enumTypeInfo(scrutineeType),enumDef=enumInfo?.def,runtimeName=enumInfo?.runtimeName;
      for(const arm of stmt.arms){const p=arm.pattern,failJumps=[];let resolved=null;const bareEnumCase=Boolean(enumDef&&p.kind==='BindingPattern'&&enumDef.cases.has(p.name)&&enumDef.cases.get(p.name).types.length===0);
        if(bareEnumCase){emit({op:'load',index:temp.slot});emit({op:'enum_is',name:runtimeName,variant:p.name});}
        else if(p.kind==='WildcardPattern'||p.kind==='BindingPattern')emit({op:'const',index:this.constant('bool',true)});
        else if(p.kind==='BoolPattern'){emit({op:'load',index:temp.slot});emit({op:'const',index:this.constant('bool',p.value)});emit({op:'eq'});}
        else if(p.kind==='EnumPattern'){resolved=resolveEnumPattern(p,scrutineeType);emit({op:'load',index:temp.slot});emit({op:'enum_is',name:resolved.runtimeName,variant:resolved.variantName});}
        else semanticFail('E0257','pattern passed validation but has no lowering',p,'compiler invariant');
        failJumps.push(emit({op:'jump_if_false',target:-1}));enterScope();
        if(p.kind==='BindingPattern'&&!bareEnumCase){const local=declare(p.name,scrutineeType,false,p);emit({op:'load',index:temp.slot});emit({op:'store',index:local.slot});}
        if(p.kind==='EnumPattern'){for(let i=0;i<p.args.length;i++){const arg=p.args[i];if(arg.kind==='BindingPattern'){const local=declare(arg.name,resolved.variant.types[i],false,arg);emit({op:'load',index:temp.slot});emit({op:'enum_get',name:resolved.runtimeName,variant:resolved.variantName,index:i});emit({op:'store',index:local.slot});}}}
        if(arm.guard){compileExpr(arm.guard,'bool');failJumps.push(emit({op:'jump_if_false',target:-1}));}
        let flow;if(arm.bodyIsBlock)flow=compileBlock(arm.body,false);else{compileExpr(arm.body,null);emit({op:'pop'});flow=new Set(['normal']);}leaveScope();allFlows.push(flow);if(flow.has('normal'))endJumps.push(emit({op:'jump',target:-1}));const next=anchor();for(const jump of failJumps)patch(jump,next);
      }
      const end=anchor();for(const jump of endJumps)patch(jump,end);return unionFlows(...allFlows);
    };

    const compileStatement=stmt=>{
      if(stmt.kind==='Let'||stmt.kind==='Var'){const annotated=stmt.type?this.ensureType(stmt.type):null,type=compileExpr(stmt.initializer,annotated),local=declare(stmt.name,type,stmt.kind==='Var',stmt);emit({op:'store',index:local.slot});return new Set(['normal']);}
      if(stmt.kind==='Assign'){compileAssignment(stmt);return new Set(['normal']);}
      if(stmt.kind==='If')return compileIf(stmt);
      if(stmt.kind==='Match')return compileMatch(stmt);
      if(stmt.kind==='While')return compileWhile(stmt);
      if(stmt.kind==='Break'){if(!loopStack.length)semanticFail('E0224','break is only valid inside a loop',stmt,'loop control');const jump=emit({op:'jump',target:-1});loopStack[loopStack.length-1].breaks.push(jump);return new Set(['break']);}
      if(stmt.kind==='Continue'){if(!loopStack.length)semanticFail('E0225','continue is only valid inside a loop',stmt,'loop control');emit({op:'jump',target:loopStack[loopStack.length-1].continueTarget});return new Set(['continue']);}
      if(stmt.kind==='Return'){if(stmt.expression){if(sig.returnType==='unit')semanticFail('E0218',`unit function '${fn.name}' cannot return a value`,stmt,'return semantics');compileExpr(stmt.expression,sig.returnType);}else if(sig.returnType!=='unit')semanticFail('E0219',`function '${fn.name}' must return ${sig.returnType}`,stmt,'return semantics');emit({op:'ret'});return new Set(['return']);}
      if(stmt.kind==='ExprStmt'){compileExpr(stmt.expression,null);emit({op:'pop'});return new Set(['normal']);}
      semanticFail('E0298',`unsupported statement '${stmt.kind}'`,stmt,'compiler invariant');
    };
    const flows=compileBlock(fn.body,false);if(flows.has('break')||flows.has('continue'))semanticFail('E0226',`loop control escaped function '${fn.name}'`,fn,'compiler invariant');if(sig.returnType==='unit'){if(flows.has('normal'))emit({op:'ret'});}else if(flows.has('normal'))semanticFail('E0220',`non-unit function '${fn.name}' does not return on every reachable path`,fn,'return completeness');if(!code.length)emit({op:'ret'});this.functions[fn.name]={params:fn.params.length,locals:nextLocal,code};
  }
}

export function lexRiftPlusPlusCoreV1(source){const text=String(source??'');if(encoder.encode(text).byteLength>MAX_SOURCE_BYTES)fail('E0001',`source exceeds ${MAX_SOURCE_BYTES} UTF-8 bytes`,{start:0,end:0,line:1,column:1},'host bounds');return new Lexer(text.charCodeAt(0)===0xfeff?text.slice(1):text).run();}
export function parseRiftPlusPlusCoreV1(source){return new Parser(lexRiftPlusPlusCoreV1(source)).parseFile();}
export function compileRiftPlusPlusCoreV1(source){const ast=parseRiftPlusPlusCoreV1(source),executable=new Codegen(ast).run(),executableText=JSON.stringify(executable,null,2)+'\n';return Object.freeze({schema:'riftpp-core-compile-result/1',language:RIFTPP_LANGUAGE,compiler:RIFTPP_CORE_VERSION,module:ast.module,ast,executable,executableText});}
export function inspectRiftPlusPlusCoreV1(source){const result=compileRiftPlusPlusCoreV1(source);return Object.freeze({schema:result.schema,language:result.language,compiler:result.compiler,module:result.module,structs:result.ast.structs.map(item=>item.name),enums:result.ast.enums.map(item=>item.name),functions:result.ast.functions.map(fn=>fn.name),bytes:encoder.encode(result.executableText).byteLength,targetFormat:RIFT_EXEC_FORMAT,targetAbi:RIFT_VM_ABI});}

if(typeof globalThis!=='undefined')globalThis.RiftPlusPlusCore=Object.freeze({version:RIFTPP_CORE_VERSION,language:RIFTPP_LANGUAGE,targetFormat:RIFT_EXEC_FORMAT,targetAbi:RIFT_VM_ABI,lex:lexRiftPlusPlusCoreV1,parse:parseRiftPlusPlusCoreV1,compile:compileRiftPlusPlusCoreV1,inspect:inspectRiftPlusPlusCoreV1});
