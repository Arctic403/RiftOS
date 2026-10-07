(() => {
'use strict';
const A=1,T=7,H=13,R=1,TX=3,IN=4,B=5,F=1;
const ID={project:100,a32:110,a64:111,uni:112,pre:120,pack:121,sign:122,verify:123,reset:124};
const LIM={io:262144,apk:50331648,entries:128,name:4096};
const B64='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

function utf8(s){const o=[];for(let i=0;i<s.length;i++){let c=s.charCodeAt(i);if(c>=55296&&c<=56319&&i+1<s.length){const l=s.charCodeAt(i+1);if(l>=56320&&l<=57343){c=65536+((c-55296)<<10)+(l-56320);i++;}}if(c<128)o.push(c);else if(c<2048)o.push(192|(c>>6),128|(c&63));else if(c<65536)o.push(224|(c>>12),128|((c>>6)&63),128|(c&63));else o.push(240|(c>>18),128|((c>>12)&63),128|((c>>6)&63),128|(c&63));}return o;}
const APK_SIG_MAGIC=utf8('APK Sig Block 42');
function text(b){let s='';for(let i=0;i<b.length;){const a=b[i++]&255;let c;if(a<128)c=a;else if((a&224)===192)c=((a&31)<<6)|(b[i++]&63);else if((a&240)===224)c=((a&15)<<12)|((b[i++]&63)<<6)|(b[i++]&63);else{c=((a&7)<<18)|((b[i++]&63)<<12)|((b[i++]&63)<<6)|(b[i++]&63);}if(c<=65535)s+=String.fromCharCode(c);else{c-=65536;s+=String.fromCharCode(55296|(c>>10),56320|(c&1023));}}return s;}
function enc(b){if(!b||!b.length)return '';let s='';for(let i=0;i<b.length;i+=3){const a=b[i]&255,c=i+1<b.length?b[i+1]&255:0,d=i+2<b.length?b[i+2]&255:0,n=(a<<16)|(c<<8)|d;s+=B64[(n>>18)&63]+B64[(n>>12)&63]+(i+1<b.length?B64[(n>>6)&63]:'=')+(i+2<b.length?B64[n&63]:'=');}return s;}
function dec(s){if(!s)return [];s=String(s).replace(/\s+/g,'');if(s.length%4)throw Error('invalid base64');const o=[];for(let i=0;i<s.length;i+=4){const a=B64.indexOf(s[i]),b=B64.indexOf(s[i+1]),c=s[i+2]==='='?0:B64.indexOf(s[i+2]),d=s[i+3]==='='?0:B64.indexOf(s[i+3]);if(a<0||b<0||c<0||d<0)throw Error('invalid base64');const n=(a<<18)|(b<<12)|(c<<6)|d;o.push((n>>16)&255);if(s[i+2]!=='=')o.push((n>>8)&255);if(s[i+3]!=='=')o.push(n&255);}return o;}
function u16(b,o){return (b[o]|(b[o+1]<<8))>>>0;} function u32(b,o){return (b[o]|(b[o+1]<<8)|(b[o+2]<<16)|(b[o+3]<<24))>>>0;}
function fresh(){return {schema:'riftbuild-hosted-state/1',project:'/D:/Workspace',target:'arm32',status:'Ready',phase:'idle',requestId:1,pending:null,log:['Hosted provider loaded'],work:null,pack:null,sign:null,verify:null};}
function load(i){const b=dec(i.stateBase64||'');if(!b.length)return fresh();const s=JSON.parse(text(b));if(s.schema!=='riftbuild-hosted-state/1')throw Error('invalid provider state');s.log=Array.isArray(s.log)?s.log:[];s.requestId=Number.isInteger(s.requestId)&&s.requestId>0?s.requestId:1;return s;}
function log(s,m){s.log.push(String(m));if(s.log.length>8)s.log=s.log.slice(-8);}
function n(k,id,t){return {kind:k,id,parentId:id===1?0:1,text:t||''};}
function frame(s){const a=[n(R,1,''),n(TX,2,'RiftBuild Hosted · external RAPP provider'),n(IN,ID.project,s.project),n(TX,3,'Target: '+s.target+' · '+s.status),n(B,ID.a32,'ARM32'),n(B,ID.a64,'ARM64'),n(B,ID.uni,'Universal'),n(B,ID.pre,'Preflight'),n(B,ID.pack,'Pack APK'),n(B,ID.sign,'Sign APK'),n(B,ID.verify,'Verify APK'),n(B,ID.reset,'Reset')];let x=200;for(const l of s.log.slice(-6))a.push(n(TX,x++,l));return {layout:F,nodes:a};}
function out(s,e){const o={schema:'riftos-app-output-json/1',stateBase64:enc(utf8(JSON.stringify(s))),frame:frame(s)};if(e)o.effect=e;return JSON.stringify(o);}
function fail(s,e){s.pending=null;s.phase='error';s.status='Error: '+String(e&&e.message?e.message:e);log(s,s.status);return out(s);}
function req(s,cap,op,txt,bytes,p){const id=s.requestId++;s.pending=Object.assign({requestId:id},p||{});return out(s,{requestId:id,capability:cap,operation:op,token:0,text:txt||'',bytesBase64:enc(bytes||[])});}
function rows(e){const v=JSON.parse(text(dec(e.bytesBase64||'')));if(!Array.isArray(v))throw Error('filesystem list returned non-array');return v;}
function join(a,b){return String(a).replace(/\/+$/,'')+'/'+String(b).replace(/^\/+/,'');}
function root(s){return join(s.project,'build/riftbuild/prepared');}
function safeName(x){return /^[A-Za-z0-9._-]+$/.test(x||'')&&x!=='.'&&x!=='..';}
function safeZip(x){if(!x||x.length>1024||x[0]==='/'||x.indexOf('\\')>=0)return false;for(const p of x.split('/'))if(!p||p==='.'||p==='..'||utf8(p).length>LIM.name)return false;return utf8(x).length<=65535;}
function abis(t){if(t==='arm32')return ['armeabi-v7a'];if(t==='arm64')return ['arm64-v8a'];if(t==='universal')return ['arm64-v8a','armeabi-v7a'];throw Error('unsupported target');}
function rank(x){if(x==='AndroidManifest.xml')return [0,0,x];const d=/^classes(?:([2-9][0-9]*))?\.dex$/.exec(x);if(d)return [1,d[1]?Number(d[1]):1,x];if(x==='resources.arsc')return [2,0,x];if(x.indexOf('lib/')===0)return [3,0,x];if(x.indexOf('assets/')===0)return [4,0,x];return [9,0,x];}
function cmp(a,b){const x=rank(a.zip),y=rank(b.zip);return x[0]-y[0]||x[1]-y[1]||(x[2]<y[2]?-1:x[2]>y[2]?1:0);}

function preflight(s){const r=root(s);s.phase='preflight';s.status='Preflight: reading prepared tree';s.pack=s.sign=s.verify=null;s.work={root:r,entries:[],abi:abis(s.target),assets:[],total:0};log(s,'Preflight '+r);return req(s,'fs.read','list',r,[],{k:'pre-root'});}
function preRoot(s,e){const a=rows(e),allow=new Set(['AndroidManifest.xml','resources.arsc','lib','assets']);let m=null,lib=false,assets=false;for(const r of a){const z=String(r.name||'');if(r.kind==='file'&&/^classes(?:[2-9][0-9]*)?\.dex$/.test(z)){s.work.entries.push({zip:z,src:r.path,size:Number(r.size||0)});continue;}if(!allow.has(z))throw Error('unsupported prepared APK input: '+z);if(z==='AndroidManifest.xml'){if(r.kind!=='file')throw Error('manifest is not file');m=r;s.work.entries.push({zip:z,src:r.path,size:Number(r.size||0)});}else if(z==='resources.arsc'){if(r.kind!=='file')throw Error('resources.arsc is not file');s.work.entries.push({zip:z,src:r.path,size:Number(r.size||0)});}else if(z==='lib'){if(r.kind!=='directory')throw Error('lib is not directory');lib=true;}else if(z==='assets'){if(r.kind!=='directory')throw Error('assets is not directory');assets=true;}}if(!m)throw Error('prepared AndroidManifest.xml missing');if(!lib)throw Error('prepared lib directory missing');if(assets)s.work.assets.push({path:join(s.work.root,'assets'),rel:''});return req(s,'fs.read','readBytes',JSON.stringify({path:m.path,offset:0,length:8}),[],{k:'pre-manifest',size:Number(m.size||0)});}
function preManifest(s,e,p){const b=dec(e.bytesBase64||'');if(b.length!==8||u16(b,0)!==3||u16(b,2)!==8||u32(b,4)!==p.size)throw Error('AndroidManifest.xml must be compiled binary XML');return preAbi(s);}
function preAbi(s){if(s.work.abi.length){const a=s.work.abi[0];return req(s,'fs.read','list',join(s.work.root,'lib/'+a),[],{k:'pre-abi',abi:a});}return preAsset(s);}
function preAbiDone(s,e,p){let c=0;for(const r of rows(e)){if(r.kind!=='file'||!String(r.name||'').endsWith('.so')||!safeName(r.name))throw Error('unsafe native library in '+p.abi);s.work.entries.push({zip:'lib/'+p.abi+'/'+r.name,src:r.path,size:Number(r.size||0)});c++;}if(!c)throw Error(p.abi+' native library missing');s.work.abi.shift();return preAbi(s);}
function preAsset(s){if(s.work.assets.length){const a=s.work.assets[0];return req(s,'fs.read','list',a.path,[],{k:'pre-assets',path:a.path,rel:a.rel});}return preFinish(s);}
function preAssetDone(s,e,p){s.work.assets.shift();for(const r of rows(e)){const rel=p.rel?p.rel+'/'+r.name:String(r.name||'');if(!safeZip(rel))throw Error('unsafe asset path: '+rel);if(r.kind==='directory')s.work.assets.push({path:r.path,rel});else if(r.kind==='file')s.work.entries.push({zip:'assets/'+rel,src:r.path,size:Number(r.size||0)});else throw Error('unknown asset type');}return preAsset(s);}
function preFinish(s){s.work.entries.sort(cmp);if(!s.work.entries.length||s.work.entries.length>LIM.entries)throw Error('APK entry-count limit exceeded');let total=0;const seen=new Set();for(const e of s.work.entries){if(!safeZip(e.zip))throw Error('unsafe APK entry: '+e.zip);if(seen.has(e.zip))throw Error('duplicate APK entry: '+e.zip);seen.add(e.zip);if(!Number.isSafeInteger(e.size)||e.size<0)throw Error('invalid APK entry size');total+=e.size;if(total>LIM.apk)throw Error('prepared APK exceeds hosted proof limit');}s.work.total=total;s.phase='preflight-ready';s.status='Preflight ready · '+s.work.entries.length+' entries · '+total+' bytes';s.pending=null;log(s,s.status);return out(s);}

function invalidate(s,m){s.work=s.pack=s.sign=s.verify=null;s.pending=null;s.phase='idle';s.status='Ready';if(m)log(s,m);}
function action(s,id){if(id===ID.a32||id===ID.a64||id===ID.uni){s.target=id===ID.a32?'arm32':id===ID.a64?'arm64':'universal';invalidate(s,'Target changed to '+s.target);return out(s);}if(id===ID.pre)return preflight(s);if(id===ID.reset){const p=s.project,t=s.target;s=fresh();s.project=p;s.target=t;return out(s);}if(id===ID.pack||id===ID.sign||id===ID.verify)throw Error('provider phase not installed yet');return out(s);}
function effect(s,e){const p=s.pending;if(!p||p.requestId!==e.targetId)throw Error('unexpected host-effect result');if(e.arg0!==1){s.pending=null;throw Error(e.text||'host effect failed');}s.pending=null;if(p.k==='pre-root')return preRoot(s,e);if(p.k==='pre-manifest')return preManifest(s,e,p);if(p.k==='pre-abi')return preAbiDone(s,e,p);if(p.k==='pre-assets')return preAssetDone(s,e,p);throw Error('unknown continuation '+p.k);}
function main(j){const i=JSON.parse(j),s=load(i),e=i.event||{};try{if(e.kind===T&&e.targetId===ID.project){const v=String(e.text||'').trim();if(v&&v!==s.project){s.project=v;invalidate(s,'Project changed');}return out(s);}if(e.kind===A)return action(s,e.targetId);if(e.kind===H)return effect(s,e);return out(s);}catch(x){return fail(s,x);}}
globalThis.riftRappMain=main;

function le16(v){v=Number(v)>>>0;return [v&255,(v>>>8)&255];}
function le32(v){v=Number(v)>>>0;return [v&255,(v>>>8)&255,(v>>>16)&255,(v>>>24)&255];}
function cat(){const o=[];for(let a=0;a<arguments.length;a++)for(const b of (arguments[a]||[]))o.push(b&255);return o;}
function crcUpdate(crc,bytes){let c=crc>>>0;for(const b of bytes){c^=b&255;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return c>>>0;}
function localHeader(name){const z=utf8(name);return cat(le32(0x04034b50),le16(20),le16(0x0808),le16(0),le16(0),le16(0x21),le32(0),le32(0),le32(0),le16(z.length),le16(0),z);}
function descriptor(crc,size){return cat(le32(0x08074b50),le32(crc),le32(size),le32(size));}
function central(e){const z=utf8(e.zip);return cat(le32(0x02014b50),le16(20),le16(20),le16(0x0808),le16(0),le16(0),le16(0x21),le32(e.crc),le32(e.size),le32(e.size),le16(z.length),le16(0),le16(0),le16(0),le16(0),le32(0),le32(e.localOffset),z);}
function eocd(count,size,offset){return cat(le32(0x06054b50),le16(0),le16(0),le16(count),le16(count),le32(size),le32(offset),le16(0));}
function projectTag(s){const p=String(s.project||'').split('/').filter(Boolean),r=(p.length?p[p.length-1]:'app').replace(/[^A-Za-z0-9._-]+/g,'-').replace(/^-+|-+$/g,'');return r||'app';}
function rd(s,path,offset,length,p){return req(s,'fs.read','readBytes',JSON.stringify({path,offset,length}),[],p);}
function wr(s,path,offset,bytes,truncate,p){return req(s,'fs.write','writeBytes',JSON.stringify({path,offset,truncate:!!truncate}),bytes,p);}

function packStart(s){
  if(s.phase!=='preflight-ready'||!s.work||!Array.isArray(s.work.entries))throw Error('run successful preflight first');
  const path='/D:/Builds/Hosted/'+projectTag(s)+'-'+s.target+'-unsigned.apk';
  s.pack={path,outputOffset:0,index:0,srcOffset:0,crc:0xffffffff,centralIndex:0,centralOffset:0,centralSize:0,complete:false};
  s.sign=s.verify=null;s.phase='packing';s.status='Packing unsigned APK';log(s,s.status);
  return req(s,'fs.write','mkdir','/D:/Builds/Hosted',[],{k:'pack-mkdir'});
}
function packMkdir(s){return wr(s,s.pack.path,0,[],true,{k:'pack-init'});}
function packEntry(s){
  if(s.pack.index>=s.work.entries.length)return packCentralStart(s);
  const e=s.work.entries[s.pack.index];e.localOffset=s.pack.outputOffset;e.crc=0;s.pack.srcOffset=0;s.pack.crc=0xffffffff;
  const h=localHeader(e.zip);return wr(s,s.pack.path,s.pack.outputOffset,h,false,{k:'pack-head',len:h.length});
}
function packHead(s,p){s.pack.outputOffset+=p.len;const e=s.work.entries[s.pack.index];if(e.size===0){e.crc=0;const d=descriptor(0,0);return wr(s,s.pack.path,s.pack.outputOffset,d,false,{k:'pack-desc',len:d.length});}return packRead(s);}
function packRead(s){const e=s.work.entries[s.pack.index],left=e.size-s.pack.srcOffset;if(left<=0)return packDataDone(s);const len=Math.min(LIM.io,left);return rd(s,e.src,s.pack.srcOffset,len,{k:'pack-read',len});}
function packReadDone(s,e,p){const b=dec(e.bytesBase64||'');if(!b.length||b.length>p.len)throw Error('prepared source read length drift');s.pack.crc=crcUpdate(s.pack.crc,b);return wr(s,s.pack.path,s.pack.outputOffset,b,false,{k:'pack-write',len:b.length});}
function packWriteDone(s,p){s.pack.outputOffset+=p.len;s.pack.srcOffset+=p.len;const e=s.work.entries[s.pack.index];if(s.pack.srcOffset<e.size)return packRead(s);if(s.pack.srcOffset!==e.size)throw Error('prepared source size drift');return packDataDone(s);}
function packDataDone(s){const e=s.work.entries[s.pack.index];e.crc=(~s.pack.crc)>>>0;const d=descriptor(e.crc,e.size);return wr(s,s.pack.path,s.pack.outputOffset,d,false,{k:'pack-desc',len:d.length});}
function packDescDone(s,p){s.pack.outputOffset+=p.len;s.pack.index++;return packEntry(s);}
function packCentralStart(s){s.pack.centralOffset=s.pack.outputOffset;s.pack.centralSize=0;s.pack.centralIndex=0;return packCentral(s);}
function packCentral(s){
  if(s.pack.centralIndex>=s.work.entries.length){const z=eocd(s.work.entries.length,s.pack.centralSize,s.pack.centralOffset);return wr(s,s.pack.path,s.pack.outputOffset,z,false,{k:'pack-eocd',len:z.length});}
  const chunk=[];while(s.pack.centralIndex<s.work.entries.length){const z=central(s.work.entries[s.pack.centralIndex]);if(chunk.length&&chunk.length+z.length>196608)break;chunk.push(...z);s.pack.centralIndex++;}
  return wr(s,s.pack.path,s.pack.outputOffset,chunk,false,{k:'pack-central',len:chunk.length});
}
function packCentralDone(s,p){s.pack.outputOffset+=p.len;s.pack.centralSize+=p.len;return packCentral(s);}
function packFinish(s,p){s.pack.outputOffset+=p.len;s.pack.bytes=s.pack.outputOffset;s.pack.complete=true;s.phase='packed';s.status='Packed unsigned APK · '+s.pack.bytes+' bytes';s.pending=null;log(s,s.status);return out(s);}

function action(s,id){
  if(id===ID.a32||id===ID.a64||id===ID.uni){s.target=id===ID.a32?'arm32':id===ID.a64?'arm64':'universal';invalidate(s,'Target changed to '+s.target);return out(s);}
  if(id===ID.pre)return preflight(s);
  if(id===ID.pack)return packStart(s);
  if(id===ID.reset){const p=s.project,t=s.target;s=fresh();s.project=p;s.target=t;return out(s);}
  if(id===ID.sign||id===ID.verify)throw Error('provider signing phase not installed yet');
  return out(s);
}
function effect(s,e){
  const p=s.pending;if(!p||p.requestId!==e.targetId)throw Error('unexpected host-effect result');if(e.arg0!==1){s.pending=null;throw Error(e.text||'host effect failed');}s.pending=null;
  if(p.k==='pre-root')return preRoot(s,e);
  if(p.k==='pre-manifest')return preManifest(s,e,p);
  if(p.k==='pre-abi')return preAbiDone(s,e,p);
  if(p.k==='pre-assets')return preAssetDone(s,e,p);
  if(p.k==='pack-mkdir')return packMkdir(s);
  if(p.k==='pack-init')return packEntry(s);
  if(p.k==='pack-head')return packHead(s,p);
  if(p.k==='pack-read')return packReadDone(s,e,p);
  if(p.k==='pack-write')return packWriteDone(s,p);
  if(p.k==='pack-desc')return packDescDone(s,p);
  if(p.k==='pack-central')return packCentralDone(s,p);
  if(p.k==='pack-eocd')return packFinish(s,p);
  throw Error('unknown continuation '+p.k);
}

const SHA_K=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
function rr(x,n){return (x>>>n)|(x<<(32-n));}
function shNew(){return {h:[0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19],buf:[],len:0};}
function shBlock(s,b){const w=new Array(64);for(let i=0;i<16;i++){const o=i*4;w[i]=((b[o]<<24)|(b[o+1]<<16)|(b[o+2]<<8)|b[o+3])>>>0;}for(let i=16;i<64;i++){const x=w[i-15],y=w[i-2],a=(rr(x,7)^rr(x,18)^(x>>>3))>>>0,c=(rr(y,17)^rr(y,19)^(y>>>10))>>>0;w[i]=(w[i-16]+a+w[i-7]+c)>>>0;}let a=s.h[0],b0=s.h[1],c=s.h[2],d=s.h[3],e=s.h[4],f=s.h[5],g=s.h[6],h=s.h[7];for(let i=0;i<64;i++){const q=(rr(e,6)^rr(e,11)^rr(e,25))>>>0,ch=((e&f)^((~e)&g))>>>0,t1=(h+q+ch+SHA_K[i]+w[i])>>>0,q0=(rr(a,2)^rr(a,13)^rr(a,22))>>>0,maj=((a&b0)^(a&c)^(b0&c))>>>0,t2=(q0+maj)>>>0;h=g;g=f;f=e;e=(d+t1)>>>0;d=c;c=b0;b0=a;a=(t1+t2)>>>0;}s.h[0]=(s.h[0]+a)>>>0;s.h[1]=(s.h[1]+b0)>>>0;s.h[2]=(s.h[2]+c)>>>0;s.h[3]=(s.h[3]+d)>>>0;s.h[4]=(s.h[4]+e)>>>0;s.h[5]=(s.h[5]+f)>>>0;s.h[6]=(s.h[6]+g)>>>0;s.h[7]=(s.h[7]+h)>>>0;}
function shAdd(s,b){s.len+=b.length;let i=0;if(s.buf.length){while(i<b.length&&s.buf.length<64)s.buf.push(b[i++]&255);if(s.buf.length===64){shBlock(s,s.buf);s.buf=[];}}while(i+64<=b.length){shBlock(s,b.slice(i,i+64));i+=64;}while(i<b.length)s.buf.push(b[i++]&255);return s;}
function shEnd(s0){const s={h:s0.h.slice(),buf:s0.buf.slice(),len:s0.len},bits=s.len*8,hi=Math.floor(bits/4294967296)>>>0,lo=bits>>>0,t=s.buf.slice();t.push(128);while(t.length%64!==56)t.push(0);t.push((hi>>>24)&255,(hi>>>16)&255,(hi>>>8)&255,hi&255,(lo>>>24)&255,(lo>>>16)&255,(lo>>>8)&255,lo&255);for(let i=0;i<t.length;i+=64)shBlock(s,t.slice(i,i+64));const o=[];for(const h of s.h)o.push((h>>>24)&255,(h>>>16)&255,(h>>>8)&255,h&255);return o;}

function digestBegin(s,mode,sections){
  let chunks=0;for(const x of sections)if(x.length)chunks+=Math.ceil(x.length/1048576);if(!chunks)throw Error('APK has no digestible chunks');
  const top=shNew();shAdd(top,[0x5a].concat(le32(chunks)));
  s.digest={mode,sections,si:0,pos:0,left:0,chunk:null,top,chunks};
  return digestNext(s);
}
function digestNext(s){
  const d=s.digest;
  while(true){
    if(d.si>=d.sections.length){const z=shEnd(d.top),mode=d.mode;s.digest=null;return digestDone(s,mode,z);}
    const sec=d.sections[d.si];
    if(d.pos>=sec.length){d.si++;d.pos=0;d.left=0;d.chunk=null;continue;}
    if(!d.chunk){const len=Math.min(1048576,sec.length-d.pos);d.left=len;d.chunk=shNew();shAdd(d.chunk,[0xa5].concat(le32(len)));}
    const len=Math.min(131072,d.left);
    if(sec.inline){const all=dec(sec.inline),b=all.slice(d.pos,d.pos+len);if(b.length!==len)throw Error('inline digest section drift');return digestFeed(s,b);}
    return rd(s,sec.path,sec.offset+d.pos,len,{k:'digest-read',len});
  }
}
function digestFeed(s,b){const d=s.digest;if(!d.chunk||b.length>d.left)throw Error('digest state drift');shAdd(d.chunk,b);d.pos+=b.length;d.left-=b.length;if(d.left===0){shAdd(d.top,shEnd(d.chunk));d.chunk=null;}return digestNext(s);}
function digestReadDone(s,e,p){const b=dec(e.bytesBase64||'');if(b.length!==p.len)throw Error('APK digest read length drift');return digestFeed(s,b);}

function seq(){const o=[];for(let i=0;i<arguments.length;i++){const e=arguments[i]||[];o.push(...le32(e.length),...e);}return o;}
function algRecord(id,v){return cat(le32(8+v.length),le32(id),le32(v.length),v);}
function le64(v){if(!Number.isSafeInteger(v)||v<0)throw Error('uint64 overflow');return le32(v>>>0).concat(le32(Math.floor(v/4294967296)>>>0));}
function blockV2(v2){const size=8+8+4+v2.length+8+APK_SIG_MAGIC.length,field=size-8;return cat(le64(field),le64(4+v2.length),le32(0x7109871a),v2,le64(field),APK_SIG_MAGIC);}
function statSize(e){const x=JSON.parse(e.text||'{}'),n=Number(x.size||0);if(!Number.isSafeInteger(n)||n<22||n>LIM.apk+1048576)throw Error('APK size out of bounds');return n;}
function parseEocd(b,size){if(b.length!==22||u32(b,0)!==0x06054b50)throw Error('ZIP EOCD not found');if(u16(b,4)||u16(b,6)||u16(b,8)!==u16(b,10)||u16(b,20)!==0)throw Error('unsupported ZIP EOCD');const cdSize=u32(b,12),cdOff=u32(b,16);if(cdOff+cdSize!==size-22)throw Error('ZIP central-directory layout malformed');return {cdSize,cdOff,eocdOff:size-22};}

function signStart(s){
  if(s.phase!=='packed'||!s.pack||!s.pack.complete)throw Error('pack hosted APK first');
  s.sign={unsigned:s.pack.path,signed:'/D:/Builds/Hosted/'+projectTag(s)+'-'+s.target+'-signed.apk'};s.verify=null;s.phase='signing';s.status='Signing: inspect unsigned APK';log(s,s.status);
  return req(s,'fs.read','stat',s.sign.unsigned,[],{k:'sign-stat'});
}
function signStat(s,e){s.sign.size=statSize(e);return rd(s,s.sign.unsigned,s.sign.size-22,22,{k:'sign-eocd'});}
function signEocd(s,e){const b=dec(e.bytesBase64||''),z=parseEocd(b,s.sign.size);s.sign.eocd=enc(b);s.sign.cdOff=z.cdOff;s.sign.cdSize=z.cdSize;s.sign.eocdOff=z.eocdOff;s.status='Signing: APK v2 content digest';return digestBegin(s,'sign',[{path:s.sign.unsigned,offset:0,length:z.cdOff},{path:s.sign.unsigned,offset:z.cdOff,length:z.cdSize},{path:s.sign.unsigned,offset:z.eocdOff,length:22}]);}
function digestDone(s,mode,z){
  if(mode==='sign'){s.sign.digest=enc(z);s.status='Signing: requesting public identity';return req(s,'signing.identity','describe','',[],{k:'sign-describe'});}
  if(mode==='verify'){if(enc(z)!==s.verify.expectedDigest)throw Error('APK v2 protected content digest mismatch');s.verify.digest=enc(z);s.status='Verify: checking signing identity';return req(s,'signing.identity','describe','',[],{k:'verify-describe'});}
  throw Error('unknown digest mode');
}
function signDescribe(s,e){
  const m=JSON.parse(e.text||'{}'),cert=dec(m.certificateDerBase64||''),pub=dec(m.publicKeyDerBase64||''),dig=dec(s.sign.digest);
  if(!cert.length||!pub.length)throw Error('signing identity public material missing');
  const signed=seq(algRecord(0x0103,dig),seq(cert),[]);
  s.sign.cert=m.certificateDerBase64;s.sign.pub=m.publicKeyDerBase64;s.sign.signedData=enc(signed);s.status='Signing: AndroidKeyStore RSA/SHA-256';
  return req(s,'signing.identity','signSha256RsaPkcs1','',signed,{k:'sign-rsa'});
}
function signRsa(s,e){
  const sig=dec(e.bytesBase64||'');if(!sig.length)throw Error('signing identity returned no signature');
  const signed=dec(s.sign.signedData),pub=dec(s.sign.pub),v2=seq(seq(seq(signed,algRecord(0x0103,sig),pub))),block=blockV2(v2);
  if(block.length>262144)throw Error('APK v2 signing block too large');
  s.sign.signature=enc(sig);s.sign.block=enc(block);s.sign.blockBytes=block.length;s.status='Signing: writing signed APK';
  return wr(s,s.sign.signed,0,[],true,{k:'sign-init'});
}
function signCopyNext(s){
  const c=s.sign.copy;
  if(c.stage==='prefix'){
    if(c.src<c.end)return rd(s,s.sign.unsigned,c.src,Math.min(LIM.io,c.end-c.src),{k:'sign-copy-read',len:Math.min(LIM.io,c.end-c.src)});
    const b=dec(s.sign.block);return wr(s,s.sign.signed,c.out,b,false,{k:'sign-block',len:b.length});
  }
  if(c.stage==='central'){
    if(c.src<c.end)return rd(s,s.sign.unsigned,c.src,Math.min(LIM.io,c.end-c.src),{k:'sign-copy-read',len:Math.min(LIM.io,c.end-c.src)});
    const z=dec(s.sign.eocd);writeU32At(z,16,s.sign.cdOff+s.sign.blockBytes);return wr(s,s.sign.signed,c.out,z,false,{k:'sign-final-eocd',len:z.length});
  }
  throw Error('unknown sign copy stage');
}
function signInit(s){s.sign.copy={stage:'prefix',src:0,end:s.sign.cdOff,out:0};return signCopyNext(s);}
function signCopyRead(s,e,p){const b=dec(e.bytesBase64||'');if(b.length!==p.len)throw Error('sign copy read drift');return wr(s,s.sign.signed,s.sign.copy.out,b,false,{k:'sign-copy-write',len:b.length});}
function signCopyWrite(s,p){const c=s.sign.copy;c.src+=p.len;c.out+=p.len;return signCopyNext(s);}
function signBlockDone(s,p){const c=s.sign.copy;c.out+=p.len;c.stage='central';c.src=s.sign.cdOff;c.end=s.sign.eocdOff;return signCopyNext(s);}
function signFinish(s,p){s.sign.copy.out+=p.len;s.sign.bytes=s.sign.copy.out;delete s.sign.copy;s.phase='signed';s.status='Signed APK · verification required';s.pending=null;log(s,s.status);return out(s);}
function writeU32At(b,o,v){const z=le32(v);for(let i=0;i<4;i++)b[o+i]=z[i];}

function action(s,id){
  if(id===ID.a32||id===ID.a64||id===ID.uni){s.target=id===ID.a32?'arm32':id===ID.a64?'arm64':'universal';invalidate(s,'Target changed to '+s.target);return out(s);}
  if(id===ID.pre)return preflight(s);
  if(id===ID.pack)return packStart(s);
  if(id===ID.sign)return signStart(s);
  if(id===ID.reset){const p=s.project,t=s.target;s=fresh();s.project=p;s.target=t;return out(s);}
  if(id===ID.verify)throw Error('provider verification phase not installed yet');
  return out(s);
}
function effect(s,e){
  const p=s.pending;if(!p||p.requestId!==e.targetId)throw Error('unexpected host-effect result');if(e.arg0!==1){s.pending=null;throw Error(e.text||'host effect failed');}s.pending=null;
  if(p.k==='pre-root')return preRoot(s,e);if(p.k==='pre-manifest')return preManifest(s,e,p);if(p.k==='pre-abi')return preAbiDone(s,e,p);if(p.k==='pre-assets')return preAssetDone(s,e,p);
  if(p.k==='pack-mkdir')return packMkdir(s);if(p.k==='pack-init')return packEntry(s);if(p.k==='pack-head')return packHead(s,p);if(p.k==='pack-read')return packReadDone(s,e,p);if(p.k==='pack-write')return packWriteDone(s,p);if(p.k==='pack-desc')return packDescDone(s,p);if(p.k==='pack-central')return packCentralDone(s,p);if(p.k==='pack-eocd')return packFinish(s,p);
  if(p.k==='digest-read')return digestReadDone(s,e,p);if(p.k==='sign-stat')return signStat(s,e);if(p.k==='sign-eocd')return signEocd(s,e);if(p.k==='sign-describe')return signDescribe(s,e);if(p.k==='sign-rsa')return signRsa(s,e);if(p.k==='sign-init')return signInit(s);if(p.k==='sign-copy-read')return signCopyRead(s,e,p);if(p.k==='sign-copy-write')return signCopyWrite(s,p);if(p.k==='sign-block')return signBlockDone(s,p);if(p.k==='sign-final-eocd')return signFinish(s,p);
  throw Error('unknown continuation '+p.k);
}
if(enc(shEnd(shAdd(shNew(),utf8('abc'))))!=='ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=')throw Error('SHA-256 self-test failed');

function C(b){return {b,o:0};}
function cu32(c){const v=u32(c.b,c.o);c.o+=4;return v;}
function cb(c,n){if(n<0||c.o+n>c.b.length)throw Error('truncated v2 field');const z=c.b.slice(c.o,c.o+n);c.o+=n;return z;}
function clp(c){return cb(c,cu32(c));}
function cdone(c,l){if(c.o!==c.b.length)throw Error(l+' contains trailing bytes');}

function parseV2(block){
  if(block.length<32)throw Error('APK Signing Block truncated');
  const size=read64(block,0);if(size+8!==block.length)throw Error('APK Signing Block size mismatch');
  if(read64(block,block.length-24)!==size)throw Error('APK Signing Block size fields disagree');
  for(let i=0;i<APK_SIG_MAGIC.length;i++)if(block[block.length-16+i]!==APK_SIG_MAGIC[i])throw Error('APK Signing Block magic mismatch');
  const pair=read64(block,8);if(pair<4||8+8+pair!==block.length-24)throw Error('APK Signing Block pair layout invalid');
  if(u32(block,16)!==0x7109871a)throw Error('APK v2 block id missing');
  const v2=block.slice(20,20+pair-4),v=C(v2),signersBytes=clp(v);cdone(v,'v2 container');
  const signers=C(signersBytes),signerBytes=clp(signers);cdone(signers,'v2 signers');
  const signer=C(signerBytes),signedData=clp(signer),sigBytes=clp(signer),pub=clp(signer);cdone(signer,'v2 signer');
  const sigs=C(sigBytes),sigRec=C(clp(sigs));cdone(sigs,'v2 signatures');
  if(cu32(sigRec)!==0x0103)throw Error('unsupported v2 signature algorithm');const signature=clp(sigRec);cdone(sigRec,'v2 signature record');
  const sd=C(signedData),digestsBytes=clp(sd),certsBytes=clp(sd),attrs=clp(sd);cdone(sd,'v2 signed data');if(attrs.length)throw Error('unexpected v2 attributes');
  const digs=C(digestsBytes),digRec=C(clp(digs));cdone(digs,'v2 digests');
  if(cu32(digRec)!==0x0103)throw Error('v2 digest/signature mismatch');const digest=clp(digRec);cdone(digRec,'v2 digest record');if(digest.length!==32)throw Error('v2 SHA-256 digest length mismatch');
  const certs=C(certsBytes),cert=clp(certs);cdone(certs,'v2 certificates');
  return {signedData,signature,pub,cert,digest};
}
function read64(b,o){const lo=u32(b,o),hi=u32(b,o+4),v=lo+hi*4294967296;if(!Number.isSafeInteger(v))throw Error('uint64 read overflow');return v;}

function verifyStart(s){
  if(s.phase!=='signed'||!s.sign||!s.sign.signed)throw Error('sign hosted APK first');
  s.verify={path:s.sign.signed};s.phase='verifying';s.status='Verify: inspect signed APK';log(s,s.status);
  return req(s,'fs.read','stat',s.verify.path,[],{k:'verify-stat'});
}
function verifyStat(s,e){s.verify.size=statSize(e);return rd(s,s.verify.path,s.verify.size-22,22,{k:'verify-eocd'});}
function verifyEocd(s,e){
  const b=dec(e.bytesBase64||''),z=parseEocd(b,s.verify.size);s.verify.eocd=enc(b);s.verify.cdOff=z.cdOff;s.verify.cdSize=z.cdSize;s.verify.eocdOff=z.eocdOff;
  if(z.cdOff<24)throw Error('signed APK central directory too early');
  return rd(s,s.verify.path,z.cdOff-24,24,{k:'verify-tail'});
}
function verifyTail(s,e){
  const b=dec(e.bytesBase64||'');if(b.length!==24)throw Error('APK Signing Block tail truncated');
  for(let i=0;i<16;i++)if(b[8+i]!==APK_SIG_MAGIC[i])throw Error('APK v2 signing block missing');
  const field=read64(b,0),total=field+8;if(total<32||total>262144||total>s.verify.cdOff)throw Error('APK Signing Block size invalid');
  s.verify.blockStart=s.verify.cdOff-total;s.verify.blockBytes=total;
  return rd(s,s.verify.path,s.verify.blockStart,total,{k:'verify-block'});
}
function verifyBlock(s,e){
  const p=parseV2(dec(e.bytesBase64||''));s.verify.expectedDigest=enc(p.digest);s.verify.signedData=enc(p.signedData);s.verify.signature=enc(p.signature);s.verify.pub=enc(p.pub);s.verify.cert=enc(p.cert);
  const z=dec(s.verify.eocd);writeU32At(z,16,s.verify.blockStart);s.status='Verify: recomputing APK v2 digest';
  return digestBegin(s,'verify',[{path:s.verify.path,offset:0,length:s.verify.blockStart},{path:s.verify.path,offset:s.verify.cdOff,length:s.verify.cdSize},{inline:enc(z),offset:0,length:z.length}]);
}
function verifyDescribe(s,e){
  const m=JSON.parse(e.text||'{}');if(m.certificateDerBase64!==s.verify.cert)throw Error('APK certificate does not match hosted identity');if(m.publicKeyDerBase64!==s.verify.pub)throw Error('APK public key does not match hosted identity');
  s.status='Verify: RSA/SHA-256 signature';
  return req(s,'signing.identity','verifySha256RsaPkcs1',JSON.stringify({signatureBase64:s.verify.signature}),dec(s.verify.signedData),{k:'verify-rsa'});
}
function verifyFinish(s,e){
  const m=JSON.parse(e.text||'{}');if(m.verified!==true)throw Error('signing identity did not verify APK signature');
  s.phase='verified';s.status='Verified · hosted APK v2 digest + RSA signature PASS';s.pending=null;log(s,s.status);return out(s);
}

function action(s,id){
  if(id===ID.a32||id===ID.a64||id===ID.uni){s.target=id===ID.a32?'arm32':id===ID.a64?'arm64':'universal';invalidate(s,'Target changed to '+s.target);return out(s);}
  if(id===ID.pre)return preflight(s);if(id===ID.pack)return packStart(s);if(id===ID.sign)return signStart(s);if(id===ID.verify)return verifyStart(s);
  if(id===ID.reset){const p=s.project,t=s.target;s=fresh();s.project=p;s.target=t;return out(s);}return out(s);
}
function effect(s,e){
  const p=s.pending;if(!p||p.requestId!==e.targetId)throw Error('unexpected host-effect result');if(e.arg0!==1){s.pending=null;throw Error(e.text||'host effect failed');}s.pending=null;
  if(p.k==='pre-root')return preRoot(s,e);if(p.k==='pre-manifest')return preManifest(s,e,p);if(p.k==='pre-abi')return preAbiDone(s,e,p);if(p.k==='pre-assets')return preAssetDone(s,e,p);
  if(p.k==='pack-mkdir')return packMkdir(s);if(p.k==='pack-init')return packEntry(s);if(p.k==='pack-head')return packHead(s,p);if(p.k==='pack-read')return packReadDone(s,e,p);if(p.k==='pack-write')return packWriteDone(s,p);if(p.k==='pack-desc')return packDescDone(s,p);if(p.k==='pack-central')return packCentralDone(s,p);if(p.k==='pack-eocd')return packFinish(s,p);
  if(p.k==='digest-read')return digestReadDone(s,e,p);if(p.k==='sign-stat')return signStat(s,e);if(p.k==='sign-eocd')return signEocd(s,e);if(p.k==='sign-describe')return signDescribe(s,e);if(p.k==='sign-rsa')return signRsa(s,e);if(p.k==='sign-init')return signInit(s);if(p.k==='sign-copy-read')return signCopyRead(s,e,p);if(p.k==='sign-copy-write')return signCopyWrite(s,p);if(p.k==='sign-block')return signBlockDone(s,p);if(p.k==='sign-final-eocd')return signFinish(s,p);
  if(p.k==='verify-stat')return verifyStat(s,e);if(p.k==='verify-eocd')return verifyEocd(s,e);if(p.k==='verify-tail')return verifyTail(s,e);if(p.k==='verify-block')return verifyBlock(s,e);if(p.k==='verify-describe')return verifyDescribe(s,e);if(p.k==='verify-rsa')return verifyFinish(s,e);
  throw Error('unknown continuation '+p.k);
}
/*__RIFTBUILD_HOSTED_EXTENSIONS__*/



})();