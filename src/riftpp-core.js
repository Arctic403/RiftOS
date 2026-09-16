import { RIFT_EXEC_FORMAT, RIFT_VM_ABI, prepareRiftExecutable } from './riftvm.js';

export const RIFTPP_CORE_VERSION='0.2.0-bootstrap';
export const RIFTPP_LANGUAGE='riftpp/1';

const MAX_SOURCE_BYTES=256*1024;
const MAX_TOKENS=50000;
const MAX_FUNCTIONS=256;
const MAX_PARAMS=64;
const MAX_LOCALS=512;
const SUPPORTED_TYPES=new Set(['unit','bool','u32','s32','string']);
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
      if(['->','==','!=','<=','>=','+=','-=','*=','/=','%='].includes(two)){this.advance();this.advance();this.token('symbol',two,start,line,column);continue;}
      if('(){}[],.:=+-*/%<>'.includes(ch)){this.advance();this.token('symbol',ch,start,line,column);continue;}
      fail('E0013',`unexpected character: ${JSON.stringify(ch)}`,{start,end:start+1,line,column},'lexical tokenization');
    }
    this.tokens.push(Object.freeze({kind:'eof',value:'<eof>',start:this.pos,end:this.pos,line:this.line,column:this.column}));return Object.freeze(this.tokens);
  }
}

class Parser{
  constructor(tokens){this.tokens=tokens;this.index=0;}
  current(){return this.tokens[this.index];}
  peek(offset=1){return this.tokens[Math.min(this.tokens.length-1,this.index+offset)];}
  previous(){return this.tokens[Math.max(0,this.index-1)];}
  at(value){return this.current().value===value;}
  consume(value){if(this.at(value)){const token=this.current();this.index++;return token;}return null;}
  expect(value,code='E0100'){const token=this.current();if(!this.at(value))fail(code,`expected '${value}', found '${token.value}'`,token,'grammar');this.index++;return token;}
  expectKind(kind,label,code='E0101'){const token=this.current();if(token.kind!==kind)fail(code,`expected ${label}, found '${token.value}'`,token,'grammar');this.index++;return token;}
  node(kind,start,fields={}){const end=this.previous();return Object.freeze({kind,...fields,span:Object.freeze({start:start.start,end:end.end,line:start.line,column:start.column})});}
  modulePath(){const first=this.expectKind('ident','module identifier'),parts=[first.value];while(this.consume('.'))parts.push(this.expectKind('ident','module identifier').value);return parts.join('.');}
  typeRef(){const token=this.current();if(token.kind!=='ident'&&token.kind!=='keyword')fail('E0102','expected type name',token,'type grammar');this.index++;return Object.freeze({kind:'TypeRef',name:token.value,span:spanOf(token)});}
  parseFile(){
    const start=this.expect('riftpp'),version=this.expectKind('number','language version');if(version.value.replaceAll('_','')!=='1')fail('E0103',`unsupported Rift++ version ${version.value}; expected 1`,version,'file header');
    this.expect('module');const moduleName=this.modulePath();
    if(this.at('use'))fail('E0104','use declarations are not implemented in the bootstrap Core slice',this.current(),'bootstrap feature gate');
    const functions=[];
    while(this.current().kind!=='eof'){
      if(!this.at('fn'))fail('E0105',`top-level '${this.current().value}' is not implemented in the bootstrap Core slice`,this.current(),'bootstrap feature gate','This slice currently accepts function declarations only.');
      functions.push(this.fnDecl());if(functions.length>MAX_FUNCTIONS)fail('E0106',`function count exceeds ${MAX_FUNCTIONS}`,this.current(),'host bounds');
    }
    return this.node('File',start,{version:1,module:moduleName,functions:Object.freeze(functions)});
  }
  fnDecl(){
    const start=this.expect('fn'),name=this.expectKind('ident','function name');this.expect('(');const params=[];
    if(!this.at(')'))for(;;){const p=this.expectKind('ident','parameter name');this.expect(':');params.push(Object.freeze({kind:'Param',name:p.value,type:this.typeRef(),span:spanOf(p)}));if(params.length>MAX_PARAMS)fail('E0107',`parameter count exceeds ${MAX_PARAMS}`,p,'host bounds');if(!this.consume(','))break;}
    this.expect(')');let returnType=Object.freeze({kind:'TypeRef',name:'unit',span:spanOf(name)});if(this.consume('->'))returnType=this.typeRef();
    if(this.at('allow'))fail('E0108','function capability clauses are not implemented in the bootstrap Core slice',this.current(),'bootstrap feature gate');
    const body=this.block();return this.node('Function',start,{name:name.value,params:Object.freeze(params),returnType,body});
  }
  block(){const start=this.expect('{'),statements=[];while(!this.at('}')){if(this.current().kind==='eof')fail('E0109','unterminated block',this.current(),'block grammar');statements.push(this.statement());}this.expect('}');return this.node('Block',start,{statements:Object.freeze(statements)});}
  statement(){
    if(this.at('let'))return this.bindingStmt(false);
    if(this.at('var'))return this.bindingStmt(true);
    if(this.at('if'))return this.ifStmt();
    if(this.at('while'))return this.whileStmt();
    if(this.at('return'))return this.returnStmt();
    if(this.at('break')){const start=this.expect('break');return this.node('Break',start);}
    if(this.at('continue')){const start=this.expect('continue');return this.node('Continue',start);}
    if(['match','for','loop'].includes(this.current().value))fail('E0110',`'${this.current().value}' is valid Core syntax but not implemented in the bootstrap slice`,this.current(),'bootstrap feature gate');
    if(this.current().kind==='ident'&&ASSIGNMENT_OPS.has(this.peek().value))return this.assignmentStmt();
    const start=this.current(),expression=this.expression();return this.node('ExprStmt',start,{expression});
  }
  bindingStmt(mutable){const start=this.expect(mutable?'var':'let'),name=this.expectKind('ident','binding name');let type=null;if(this.consume(':'))type=this.typeRef();this.expect('=');const initializer=this.expression();return this.node(mutable?'Var':'Let',start,{name:name.value,type,initializer});}
  assignmentStmt(){const start=this.expectKind('ident','assignment target'),op=this.current();if(!ASSIGNMENT_OPS.has(op.value))fail('E0112',`expected assignment operator, found '${op.value}'`,op,'assignment grammar');this.index++;const expression=this.expression();return this.node('Assign',start,{name:start.value,op:op.value,expression});}
  ifStmt(){const start=this.expect('if'),condition=this.expression(),thenBranch=this.block();let elseBranch=null;if(this.consume('else'))elseBranch=this.at('if')?this.ifStmt():this.block();return this.node('If',start,{condition,thenBranch,elseBranch});}
  whileStmt(){const start=this.expect('while'),condition=this.expression(),body=this.block();return this.node('While',start,{condition,body});}
  returnStmt(){const start=this.expect('return'),expression=this.at('}')?null:this.expression();return this.node('Return',start,{expression});}
  expression(){return this.logicalOr();}
  logicalOr(){let expr=this.logicalAnd();while(this.at('or')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.logicalAnd(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  logicalAnd(){let expr=this.equality();while(this.at('and')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.equality(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  equality(){let expr=this.comparison();while(this.at('==')||this.at('!=')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.comparison(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  comparison(){let expr=this.additive();while(['<','<=','>','>='].includes(this.current().value)){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.additive(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  additive(){let expr=this.multiplicative();while(this.at('+')||this.at('-')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.multiplicative(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  multiplicative(){let expr=this.unary();while(this.at('*')||this.at('/')||this.at('%')){const op=this.current();this.index++;expr=Object.freeze({kind:'Binary',op:op.value,left:expr,right:this.unary(),span:Object.freeze({start:expr.span.start,end:this.previous().end,line:expr.span.line,column:expr.span.column})});}return expr;}
  unary(){if(this.at('-')||this.at('+')||this.at('not')){const op=this.current();this.index++;const expression=this.unary();return Object.freeze({kind:'Unary',op:op.value,expression,span:Object.freeze({start:op.start,end:expression.span.end,line:op.line,column:op.column})});}return this.postfix();}
  postfix(){let expr=this.primary();while(this.consume('(')){const args=[];if(!this.at(')'))for(;;){args.push(this.expression());if(!this.consume(','))break;}const close=this.expect(')');expr=Object.freeze({kind:'Call',callee:expr,args:Object.freeze(args),span:Object.freeze({start:expr.span.start,end:close.end,line:expr.span.line,column:expr.span.column})});}return expr;}
  primary(){
    const token=this.current();
    if(token.kind==='number'){this.index++;return Object.freeze({kind:'IntLiteral',raw:token.value,span:spanOf(token)});}
    if(token.kind==='string'){this.index++;return Object.freeze({kind:'StringLiteral',value:token.value,span:spanOf(token)});}
    if(token.value==='true'||token.value==='false'){this.index++;return Object.freeze({kind:'BoolLiteral',value:token.value==='true',span:spanOf(token)});}
    if(token.kind==='ident'){this.index++;return Object.freeze({kind:'Name',name:token.value,span:spanOf(token)});}
    if(this.consume('(')){const expr=this.expression();this.expect(')');return expr;}
    fail('E0111',`expected expression, found '${token.value}'`,token,'expression grammar');
  }
}

function spanToken(span){return {start:span.start,end:span.end,line:span.line,column:span.column};}
function semanticFail(code,message,node,rule,help=''){fail(code,message,spanToken(node.span),rule,help);}
function ensureTypeSupported(typeRef){if(!SUPPORTED_TYPES.has(typeRef.name))semanticFail('E0200',`type '${typeRef.name}' is valid Core design syntax but not implemented by RiftVM bootstrap`,typeRef,'bootstrap type support',`Supported now: ${[...SUPPORTED_TYPES].join(', ')}.`);return typeRef.name;}
function expectType(actual,expected,node){if(expected&&actual!==expected)semanticFail('E0201',`type mismatch: expected ${expected}, got ${actual}`,node,'type checking');return actual;}
function parseInt(raw,node,type){const clean=raw.replaceAll('_','');let value;try{value=BigInt(clean);}catch{semanticFail('E0202',`invalid integer literal '${raw}'`,node,'numeric semantics');}const bounds=type==='s32'?[-2147483648n,2147483647n]:[0n,4294967295n];if(value<bounds[0]||value>bounds[1])semanticFail('E0203',`${type} literal is out of range`,node,'checked integer semantics');return value.toString();}
function unionFlows(...sets){const out=new Set();for(const set of sets)for(const value of set)out.add(value);return out;}

class Codegen{
  constructor(ast){this.ast=ast;this.constants=[];this.constantMap=new Map();this.signatures=new Map();this.functions=Object.create(null);}
  constant(type,value){const serialized=type==='unit'?'':String(value),key=`${type}:${serialized}`;if(this.constantMap.has(key))return this.constantMap.get(key);const index=this.constants.length;this.constants.push(type==='unit'?{type:'unit'}:{type,value});this.constantMap.set(key,index);return index;}
  collectSignatures(){
    for(const fn of this.ast.functions){
      if(PRELUDE_NAMES.has(fn.name))semanticFail('E0204',`'${fn.name}' is reserved by the bootstrap prelude`,fn,'name resolution');
      if(this.signatures.has(fn.name))semanticFail('E0204',`duplicate function '${fn.name}'`,fn,'name resolution');
      const params=fn.params.map(p=>ensureTypeSupported(p.type)),returnType=ensureTypeSupported(fn.returnType);
      this.signatures.set(fn.name,Object.freeze({params:Object.freeze(params),returnType,node:fn}));
    }
    const main=this.signatures.get('main');if(!main)semanticFail('E0205',"entry function 'main' is required",this.ast,'entrypoint');if(main.params.length!==0||main.returnType!=='unit')semanticFail('E0206',"main must have signature fn main() with unit return",main.node,'entrypoint');
  }
  run(){this.collectSignatures();for(const fn of this.ast.functions)this.compileFunction(fn);const executable={format:RIFT_EXEC_FORMAT,abi:RIFT_VM_ABI,entry:'main',imports:[],constants:this.constants,functions:this.functions,limits:{maxSteps:100000,maxStack:1024,maxCallDepth:32},metadata:{language:RIFTPP_LANGUAGE,module:this.ast.module,compiler:RIFTPP_CORE_VERSION}};prepareRiftExecutable(executable);return executable;}
  compileFunction(fn){
    const sig=this.signatures.get(fn.name),code=[];let nextLocal=0;
    const scopes=[new Map()],loopStack=[];
    const emit=ins=>{code.push(ins);return code.length-1;};
    const patch=(index,target)=>{code[index]={...code[index],target};};
    const anchor=()=>{const target=code.length;emit({op:'const',index:this.constant('unit',null)});emit({op:'pop'});return target;};
    const enterScope=()=>scopes.push(new Map());
    const leaveScope=()=>scopes.pop();
    const resolve=name=>{for(let i=scopes.length-1;i>=0;i--){const value=scopes[i].get(name);if(value)return value;}return null;};
    const declare=(name,type,mutable,node)=>{
      if(PRELUDE_NAMES.has(name))semanticFail('E0216',`'${name}' is reserved by the bootstrap prelude`,node,'name resolution');
      const scope=scopes[scopes.length-1];if(scope.has(name))semanticFail('E0216',`duplicate local '${name}' in the same scope`,node,'name resolution');
      if(nextLocal>=MAX_LOCALS)semanticFail('E0217',`local count exceeds ${MAX_LOCALS}`,node,'host bounds');
      const local=Object.freeze({slot:nextLocal++,type,mutable});scope.set(name,local);return local;
    };
    for(let i=0;i<fn.params.length;i++){
      const p=fn.params[i];if(PRELUDE_NAMES.has(p.name))semanticFail('E0207',`'${p.name}' is reserved by the bootstrap prelude`,p,'name resolution');
      if(scopes[0].has(p.name))semanticFail('E0207',`duplicate parameter '${p.name}'`,p,'name resolution');
      scopes[0].set(p.name,Object.freeze({slot:nextLocal++,type:sig.params[i],mutable:false}));
    }
    const compileExpr=(expr,expected=null)=>{
      if(expr.kind==='IntLiteral'){const type=expected==='s32'?'s32':expected==='u32'?'u32':'u32';if(expected&&!['u32','s32'].includes(expected))semanticFail('E0201',`type mismatch: expected ${expected}, got integer`,expr,'type checking');emit({op:'const',index:this.constant(type,parseInt(expr.raw,expr,type))});return type;}
      if(expr.kind==='StringLiteral'){expectType('string',expected,expr);emit({op:'const',index:this.constant('string',expr.value)});return'string';}
      if(expr.kind==='BoolLiteral'){expectType('bool',expected,expr);emit({op:'const',index:this.constant('bool',expr.value)});return'bool';}
      if(expr.kind==='Name'){const local=resolve(expr.name);if(!local)semanticFail('E0208',`unknown name '${expr.name}'`,expr,'name resolution');expectType(local.type,expected,expr);emit({op:'load',index:local.slot});return local.type;}
      if(expr.kind==='Unary'){
        if(expr.op==='not'){compileExpr(expr.expression,'bool');expectType('bool',expected,expr);emit({op:'not'});return'bool';}
        if(expr.op==='+'){const type=compileExpr(expr.expression,expected);if(!['u32','s32'].includes(type))semanticFail('E0209','unary + requires an integer',expr,'numeric semantics');return type;}
        const type='s32';if(expected&&expected!=='s32')semanticFail('E0210','unary - currently requires s32',expr,'bootstrap numeric support');compileExpr(expr.expression,type);emit({op:'neg'});return type;
      }
      if(expr.kind==='Binary'){
        if(expr.op==='and'){
          expectType('bool',expected,expr);compileExpr(expr.left,'bool');emit({op:'dup'});const falseJump=emit({op:'jump_if_false',target:-1});emit({op:'pop'});compileExpr(expr.right,'bool');const end=anchor();patch(falseJump,end);return'bool';
        }
        if(expr.op==='or'){
          expectType('bool',expected,expr);compileExpr(expr.left,'bool');emit({op:'dup'});const evalRight=emit({op:'jump_if_false',target:-1}),done=emit({op:'jump',target:-1});const rightTarget=code.length;emit({op:'pop'});patch(evalRight,rightTarget);compileExpr(expr.right,'bool');const end=anchor();patch(done,end);return'bool';
        }
        if(['==','!=','<','<=','>','>='].includes(expr.op)){const left=compileExpr(expr.left,null),right=compileExpr(expr.right,left);if(left!==right)semanticFail('E0201',`comparison operands differ: ${left} and ${right}`,expr,'type checking');expectType('bool',expected,expr);emit({op:{'==':'eq','!=':'ne','<':'lt','<=':'le','>':'gt','>=':'ge'}[expr.op]});return'bool';}
        const preferred=expected&&['u32','s32','string'].includes(expected)?expected:null,left=compileExpr(expr.left,preferred),right=compileExpr(expr.right,left);if(left!==right)semanticFail('E0201',`binary operands differ: ${left} and ${right}`,expr,'type checking');if(expr.op==='+'&&left==='string'){emit({op:'concat'});return'string';}if(!['u32','s32'].includes(left))semanticFail('E0211',`operator '${expr.op}' requires integer operands`,expr,'numeric semantics');emit({op:{'+':'add','-':'sub','*':'mul','/':'div','%':'mod'}[expr.op]});return left;
      }
      if(expr.kind==='Call'){
        if(expr.callee.kind!=='Name')semanticFail('E0212','bootstrap calls require a direct function name',expr,'call semantics');const name=expr.callee.name;
        if(name==='print'){if(expr.args.length!==1)semanticFail('E0213','print expects exactly one argument',expr,'bootstrap prelude');compileExpr(expr.args[0],null);emit({op:'print'});emit({op:'const',index:this.constant('unit',null)});expectType('unit',expected,expr);return'unit';}
        const target=this.signatures.get(name);if(!target)semanticFail('E0214',`unknown function '${name}'`,expr,'name resolution');if(expr.args.length!==target.params.length)semanticFail('E0215',`${name} expects ${target.params.length} arguments, got ${expr.args.length}`,expr,'call semantics');for(let i=0;i<expr.args.length;i++)compileExpr(expr.args[i],target.params[i]);emit({op:'call',name,argc:expr.args.length});expectType(target.returnType,expected,expr);return target.returnType;
      }
      semanticFail('E0299',`unsupported expression node '${expr.kind}'`,expr,'compiler invariant');
    };
    const compileBlock=(block,newScope=true)=>{
      if(newScope)enterScope();let flows=new Set(['normal']);
      for(const stmt of block.statements){
        if(!flows.has('normal'))semanticFail('E0227','unreachable statement',stmt,'control-flow analysis');
        const stmtFlow=compileStatement(stmt),next=new Set([...flows].filter(value=>value!=='normal'));for(const value of stmtFlow)next.add(value);flows=next;
      }
      if(newScope)leaveScope();return flows;
    };
    const compileIf=stmt=>{
      compileExpr(stmt.condition,'bool');const falseJump=emit({op:'jump_if_false',target:-1});
      const thenFlow=compileBlock(stmt.thenBranch,true);
      if(!stmt.elseBranch){const end=anchor();patch(falseJump,end);return unionFlows(thenFlow,new Set(['normal']));}
      let doneJump=null;if(thenFlow.has('normal'))doneJump=emit({op:'jump',target:-1});
      const elseTarget=anchor();patch(falseJump,elseTarget);
      const elseFlow=stmt.elseBranch.kind==='If'?compileIf(stmt.elseBranch):compileBlock(stmt.elseBranch,true);
      const end=anchor();if(doneJump!==null)patch(doneJump,end);
      return unionFlows(thenFlow,elseFlow);
    };
    const compileWhile=stmt=>{
      const conditionTarget=code.length;compileExpr(stmt.condition,'bool');const exitJump=emit({op:'jump_if_false',target:-1});
      const loop={breaks:[],continueTarget:conditionTarget};loopStack.push(loop);const bodyFlow=compileBlock(stmt.body,true);loopStack.pop();
      if(bodyFlow.has('normal'))emit({op:'jump',target:conditionTarget});
      const exitTarget=anchor();patch(exitJump,exitTarget);for(const index of loop.breaks)patch(index,exitTarget);
      const out=new Set(['normal']);if(bodyFlow.has('return'))out.add('return');return out;
    };
    const compileAssignment=stmt=>{
      const local=resolve(stmt.name);if(!local)semanticFail('E0221',`unknown assignment target '${stmt.name}'`,stmt,'name resolution');if(!local.mutable)semanticFail('E0222',`cannot assign to immutable binding '${stmt.name}'`,stmt,'mutability');
      if(stmt.op==='='){compileExpr(stmt.expression,local.type);emit({op:'store',index:local.slot});return;}
      emit({op:'load',index:local.slot});compileExpr(stmt.expression,local.type);
      if(stmt.op==='+='&&local.type==='string')emit({op:'concat'});else{if(!['u32','s32'].includes(local.type))semanticFail('E0223',`compound assignment '${stmt.op}' requires integer operands (or string +=)`,stmt,'numeric semantics');emit({op:{'+=':'add','-=':'sub','*=':'mul','/=':'div','%=':'mod'}[stmt.op]});}
      emit({op:'store',index:local.slot});
    };
    const compileStatement=stmt=>{
      if(stmt.kind==='Let'||stmt.kind==='Var'){
        const annotated=stmt.type?ensureTypeSupported(stmt.type):null,type=compileExpr(stmt.initializer,annotated),local=declare(stmt.name,type,stmt.kind==='Var',stmt);emit({op:'store',index:local.slot});return new Set(['normal']);
      }
      if(stmt.kind==='Assign'){compileAssignment(stmt);return new Set(['normal']);}
      if(stmt.kind==='If')return compileIf(stmt);
      if(stmt.kind==='While')return compileWhile(stmt);
      if(stmt.kind==='Break'){
        if(!loopStack.length)semanticFail('E0224','break is only valid inside a loop',stmt,'loop control');const jump=emit({op:'jump',target:-1});loopStack[loopStack.length-1].breaks.push(jump);return new Set(['break']);
      }
      if(stmt.kind==='Continue'){
        if(!loopStack.length)semanticFail('E0225','continue is only valid inside a loop',stmt,'loop control');emit({op:'jump',target:loopStack[loopStack.length-1].continueTarget});return new Set(['continue']);
      }
      if(stmt.kind==='Return'){
        if(stmt.expression){if(sig.returnType==='unit')semanticFail('E0218',`unit function '${fn.name}' cannot return a value`,stmt,'return semantics');compileExpr(stmt.expression,sig.returnType);}else if(sig.returnType!=='unit')semanticFail('E0219',`function '${fn.name}' must return ${sig.returnType}`,stmt,'return semantics');emit({op:'ret'});return new Set(['return']);
      }
      if(stmt.kind==='ExprStmt'){compileExpr(stmt.expression,null);emit({op:'pop'});return new Set(['normal']);}
      semanticFail('E0298',`unsupported statement '${stmt.kind}'`,stmt,'compiler invariant');
    };
    const flows=compileBlock(fn.body,false);
    if(flows.has('break')||flows.has('continue'))semanticFail('E0226',`loop control escaped function '${fn.name}'`,fn,'compiler invariant');
    if(sig.returnType==='unit'){if(flows.has('normal'))emit({op:'ret'});}else if(flows.has('normal'))semanticFail('E0220',`non-unit function '${fn.name}' does not return on every reachable path`,fn,'return completeness');
    if(!code.length)emit({op:'ret'});this.functions[fn.name]={params:fn.params.length,locals:nextLocal,code};
  }
}

export function lexRiftPlusPlusCoreV1(source){const text=String(source??'');if(encoder.encode(text).byteLength>MAX_SOURCE_BYTES)fail('E0001',`source exceeds ${MAX_SOURCE_BYTES} UTF-8 bytes`,{start:0,end:0,line:1,column:1},'host bounds');return new Lexer(text.charCodeAt(0)===0xfeff?text.slice(1):text).run();}
export function parseRiftPlusPlusCoreV1(source){return new Parser(lexRiftPlusPlusCoreV1(source)).parseFile();}
export function compileRiftPlusPlusCoreV1(source){const ast=parseRiftPlusPlusCoreV1(source),executable=new Codegen(ast).run(),executableText=JSON.stringify(executable,null,2)+'\n';return Object.freeze({schema:'riftpp-core-compile-result/1',language:RIFTPP_LANGUAGE,compiler:RIFTPP_CORE_VERSION,module:ast.module,ast,executable,executableText});}
export function inspectRiftPlusPlusCoreV1(source){const result=compileRiftPlusPlusCoreV1(source);return Object.freeze({schema:result.schema,language:result.language,compiler:result.compiler,module:result.module,functions:result.ast.functions.map(fn=>fn.name),bytes:encoder.encode(result.executableText).byteLength,targetFormat:RIFT_EXEC_FORMAT,targetAbi:RIFT_VM_ABI});}

if(typeof globalThis!=='undefined')globalThis.RiftPlusPlusCore=Object.freeze({version:RIFTPP_CORE_VERSION,language:RIFTPP_LANGUAGE,targetFormat:RIFT_EXEC_FORMAT,targetAbi:RIFT_VM_ABI,lex:lexRiftPlusPlusCoreV1,parse:parseRiftPlusPlusCoreV1,compile:compileRiftPlusPlusCoreV1,inspect:inspectRiftPlusPlusCoreV1});
