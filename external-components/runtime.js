(() => {
'use strict';
// Standalone RAPP: independently compiles Core and graphical Shell through
// REGISTERED Kotlin Android + generic D8. NEVER stages or activates them.
const PROJECT='/D:/Workspace/RiftOS-main/external-components';
const OUT='/D:/Builds/Components';
const SHA=/^[0-9a-f]{64}$/;
const BASE64='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const BTN={core:101,shell:102,tools:103,clear:104};
const ABI_CLASSES=[
 'RiftCoreComponentV1.class','RiftCoreExecutionViewV1.class',
 'RiftShellGraphicalComponentV1.class','RiftShellPlatformServicesV1.class'
];
const SCHEMA='rift-critical-component-builder-state/1';
function utfBytes(s){const b=[];for(let i=0;i<s.length;i++){let x=s.charCodeAt(i);if(x<128)b.push(x);else if(x<2048)b.push(192|(x>>6),128|(x&63));else if(x>=0xd800&&x<=0xdbff&&i+1<s.length){const y=s.charCodeAt(++i),z=0x10000+((x-0xd800)<<10)+(y-0xdc00);b.push(240|(z>>18),128|((z>>12)&63),128|((z>>6)&63),128|(z&63));}else b.push(224|(x>>12),128|((x>>6)&63),128|(x&63));}return b;}
function utf(b){let t='';for(let i=0;i<b.length;){const x=b[i++];if(x<128)t+=String.fromCharCode(x);else if(x<224)t+=String.fromCharCode(((x&31)<<6)|(b[i++]&63));else if(x<240)t+=String.fromCharCode(((x&15)<<12)|((b[i++]&63)<<6)|(b[i++]&63));else{const z=((x&7)<<18)|((b[i++]&63)<<12)|((b[i++]&63)<<6)|(b[i++]&63);t+=String.fromCodePoint(z);}}return t;}
function enc(b){let t='';for(let i=0;i<b.length;i+=3){const x=(b[i]<<16)|((b[i+1]||0)<<8)|(b[i+2]||0);t+=BASE64[(x>>>18)&63]+BASE64[(x>>>12)&63]+(i+1<b.length?BASE64[(x>>>6)&63]:'=')+(i+2<b.length?BASE64[x&63]:'=');}return t;}
function dec(t){if(!t)return [];if(t.length%4)throw Error('invalid base64 length');const out=[];for(let i=0;i<t.length;i+=4){const a=BASE64.indexOf(t[i]),b=BASE64.indexOf(t[i+1]),c=t[i+2]==='='?0:BASE64.indexOf(t[i+2]),d=t[i+3]==='='?0:BASE64.indexOf(t[i+3]);if(a<0||b<0||c<0||d<0)throw Error('bad base64');const v=(a<<18)|(b<<12)|(c<<6)|d;out.push((v>>>16)&255);if(t[i+2]!=='=')out.push((v>>>8)&255);if(t[i+3]!=='=')out.push(v&255);}return out;}
function fresh(){return {schema:SCHEMA,phase:'idle',mode:'core',status:'Independent Core and Shell DEX source compiler',pending:null,req:1,stubIndex:0,sha:'',logs:['No critical component staging/activation authority is granted.']};}
function load(input){const bytes=dec(input.stateBase64||'');if(!bytes.length)return fresh();const j=JSON.parse(utf(bytes));if(j.schema!==SCHEMA)throw Error('invalid builder state schema');j.logs=Array.isArray(j.logs)?j.logs.slice(-7):[];return j;}
function log(s,t){s.logs.push(String(t).slice(0,205));if(s.logs.length>7)s.logs.shift();}
function config(s){const k=s.mode;if(!['core','shell'].includes(k))throw Error('unknown component');const root='build/riftbuild/'+k;return {kind:k,classes:root+'-classes',dex:root+'-dex',stubs:root+'-stubs',entry:k==='core'?'com.riftos.external.core.IndependentCoreV1':'com.riftos.external.shell.IndependentGraphicalShellV1',source:k==='core'?'IndependentCoreV1.kt':'IndependentGraphicalShellV1.kt',output:OUT+'/'+k+'/'+k+'.dex',manifest:OUT+'/'+k+'/component.json'};}
function node(kind,id,text){return {kind,id,parentId:id===1?0:1,text:String(text||'')};}
function render(s){const nodes=[
node(1,1,''),node(3,2,'RiftOS EXTERNAL COMPONENT BUILDER · independent RAPP'),
node(3,3,'Status: '+s.phase+' · '+s.status),
node(3,4,'Registered Kotlin Android compiler → D8 → Core/Shell DEX'),
node(5,BTN.core,'BUILD INDEPENDENT CORE DEX'),
node(5,BTN.shell,'BUILD INDEPENDENT GRAPHICAL SHELL DEX'),
node(5,BTN.tools,'CHECK REGISTERED COMPILER'),
node(5,BTN.clear,'CLEAR STATUS'),
node(3,5,s.sha?'Last independent '+s.mode+' SHA: '+s.sha:'No DEX built yet'),
node(3,6,'Core is NOT executable yet: RAPP JS engine/capability broker pending.'),
node(3,7,'No activation or admin authority. Separate Core approval/device proof required.')
];let n=200;for(const line of s.logs)nodes.push(node(3,n++,line));return {layout:1,nodes};}
function response(s,effect){const o={schema:'riftos-app-output-json/1',frame:render(s),stateBase64:enc(utfBytes(JSON.stringify(s)))};if(effect)o.effect=effect;return JSON.stringify(o);}
function issue(s,cap,op,txt,then,raw){const id=s.req++;s.pending={id,then};return response(s,{requestId:id,capability:cap,operation:op,token:0,text:txt||'',bytesBase64:raw||''});}
function fail(s,error){s.phase='error';s.pending=null;s.status=String(error&&error.message||error).slice(0,170);log(s,'REJECTED: '+s.status);return response(s);}
function status(s,forBuild){s.phase='toolchain';s.status='Checking registered Kotlin compiler + D8';return issue(s,'build.local','toolchainStatus','',forBuild?'compiler-status':'toolchain-only');}
function compile(s,status){if(status.schema!=='riftbuild-kotlin-toolchain-status/2'||status.ready!==true||status.dexer!=='D8')throw Error('Registered compiler and D8 not ready');const aj=status.androidJar?.path,kl=status.kotlinStdlib?.path;if(!aj||!kl)throw Error('Managed Android/stdlib path unavailable');const c=config(s);s.phase='compiling';s.status='Compiling external '+s.mode+' with compile-only V1 host interfaces';const req={schema:'riftbuild-compiler-json/1',language:'kotlin',sources:['HostAbiCompileOnly.kt',c.source],outputDir:c.classes,classpath:[aj,kl],options:{moduleName:'rift-external-'+s.mode,jvmTarget:'1.8',minSdk:28,noJdk:true,noStdlib:true,noReflect:true}};return issue(s,'build.local','compilerRun',JSON.stringify({project:PROJECT,compilerId:'kotlin-android',request:req}),'compiled');}
function strip(s){const c=config(s);if(s.stubIndex>=ABI_CLASSES.length){s.phase='dexing';s.status='D8 converting ONLY external component classes';return issue(s,'build.local','jvmDex',JSON.stringify({project:PROJECT,classesDir:c.classes,outputDir:c.dex,minSdk:28}),'dexed');}const file=ABI_CLASSES[s.stubIndex++];s.phase='stripping';s.status='Removing compile-only APK ABI: '+file;return issue(s,'fs.write','move',JSON.stringify({from:PROJECT+'/'+c.classes+'/com/riftos/app/'+file,to:PROJECT+'/'+c.stubs+'/'+file,replace:true}),'stripped');}
function afterDex(s,r){const c=config(s);if(r.schema!=='rift-jvm-dex/1'||r.state!=='dexed'||!Array.isArray(r.dexFiles)||r.dexFiles.length!==1)throw Error('Expected ONE indexed DEX from D8');const d=r.dexFiles[0];if(d.name!=='classes.dex'||!SHA.test(d.sha256||'')||!Number.isInteger(d.bytes)||d.bytes<112||d.bytes>32*1024*1024)throw Error('Compiled DEX SHA or bounds invalid');s.sha=d.sha256;s.phase='checking-header';s.status='Checking raw DEX magic and D8 SHA '+s.sha.slice(0,16);return issue(s,'fs.read','readBytes',JSON.stringify({path:PROJECT+'/'+c.dex+'/classes.dex',offset:0,length:8}),'header');}
function afterHeader(s,evt){const b=dec(evt.bytesBase64||'');if(b.length!==8||b[0]!==100||b[1]!==101||b[2]!==120||b[3]!==10||b[7]!==0)throw Error('D8 output has invalid Android DEX header');const c=config(s);s.phase='exporting';s.status='Exporting independently compiled '+s.mode+'.dex';return issue(s,'fs.write','move',JSON.stringify({from:PROJECT+'/'+c.dex+'/classes.dex',to:c.output,replace:true}),'exported');}
function afterExport(s){const c=config(s);const manifest={schema:'riftos.protected-component/1',component:c.kind,version:'0.1.0',entrypoint:c.entry,sha256:s.sha,buildSha256:s.sha,payload:c.kind+'.dex',abi:1,minSdk:28,maxSdk:1000};s.phase='manifest';s.status='Writing strict protected component manifest; activation DISABLED';return issue(s,'fs.write','writeText',c.manifest,'manifest-written',enc(utfBytes(JSON.stringify(manifest))));}
function eventEffect(s,e){const p=s.pending;if(!p||e.targetId!==p.id)throw Error('stale or unexpected Core effect receipt');s.pending=null;if(e.arg0!==1)throw Error(e.text||'Registered Core build capability denied');switch(p.then){
case 'toolchain-only': {const r=JSON.parse(e.text||'{}');s.phase='idle';s.status=(r.ready?'Registered compiler + D8 ready':'Registered compiler unavailable');log(s,'Toolchain check: '+r.defaultCompilerId);return response(s);}
case 'compiler-status': {const r=JSON.parse(e.text||'{}');log(s,'Managed compiler authority '+r.compilerAuthority);return compile(s,r);}
case 'compiled': {const r=JSON.parse(e.text||'{}');if(r.state!=='success'||r.response?.state!=='success')throw Error('Independent '+s.mode+' Kotlin compilation failed');s.stubIndex=0;log(s,'Kotlin compiler SUCCESS; stripping exact host ABI classes');return strip(s);}
case 'stripped': return strip(s);
case 'dexed': return afterDex(s,JSON.parse(e.text||'{}'));
case 'header': return afterHeader(s,e);
case 'exported': return afterExport(s);
case 'manifest-written':{s.phase='complete';const c=config(s);s.status='Compiled '+c.kind+'.dex independently; Core/Shell NOT ACTIVATED';log(s,'DEX '+c.output);log(s,'SHA '+s.sha.slice(0,20)+'… manifest ready');if(s.mode==='core')log(s,'Core JS runtime/lifecycle incomplete. DO NOT ACTIVATE.');else log(s,'Graphical Shell requires host verifier compatibility fix before staging.');return response(s);}
default:throw Error('Unknown protected component build step');}}
function run(input){const s=load(input),e=input.event||{};try{
if(e.kind===1&&e.targetId===BTN.core){if(s.pending)throw Error('Critical build already running');s.mode='core';s.sha='';s.stubIndex=0;log(s,'Building EXTERNAL Core type + catalogue (not runtime-complete)');return status(s,true);}
if(e.kind===1&&e.targetId===BTN.shell){if(s.pending)throw Error('Critical build already running');s.mode='shell';s.sha='';s.stubIndex=0;log(s,'Building independent graphical Shell renderer');return status(s,true);}
if(e.kind===1&&e.targetId===BTN.tools){if(s.pending)throw Error('Critical build already running');return status(s,false);}
if(e.kind===1&&e.targetId===BTN.clear){if(s.pending)throw Error('Cannot reset running critical build');return response(fresh());}
if(e.kind===13)return eventEffect(s,e);
return response(s);
}catch(error){return fail(s,error);}}
globalThis.riftRappMain=(json)=>run(JSON.parse(json));
})();