import assert from 'node:assert/strict';
import { createHash, webcrypto } from 'node:crypto';

globalThis.crypto ||= webcrypto;

const normalize=value=>{
  const raw=String(value||'/').replace(/\\/g,'/');
  const parts=[];
  for(const part of raw.split('/')){
    if(!part||part==='.')continue;
    if(part==='..')throw new Error('path traversal');
    parts.push(part);
  }
  return '/'+parts.join('/');
};
const join=(...parts)=>normalize(parts.join('/'));
const parent=path=>{const parts=normalize(path).split('/').filter(Boolean);parts.pop();return '/'+parts.join('/');};
const files=new Map();
const dirs=new Set(['/','/workspace','/workspace/RiftLLM','/workspace/RiftLLM/tokenizer','/workspace/RiftLLM/tokenizer/private']);
const ensureDir=path=>{
  const normalized=normalize(path),parts=normalized.split('/').filter(Boolean);let current='';
  for(const part of parts){current+='/'+part;dirs.add(current);}
};
const fsMock={
  async stat(path){path=normalize(path);if(files.has(path))return {path,kind:'file',size:new TextEncoder().encode(files.get(path)).byteLength};if(dirs.has(path))return {path,kind:'directory',size:0};return null;},
  async readText(path){return files.get(normalize(path))??null;},
  async writeText(path,text){path=normalize(path);ensureDir(parent(path));files.set(path,String(text));return {path,kind:'file',size:new TextEncoder().encode(String(text)).byteLength};},
  async mkdir(path){path=normalize(path);ensureDir(path);return {path,kind:'directory',size:0};},
  async list(path,{recursive=false}={}){
    path=normalize(path);if(!dirs.has(path))throw new Error(`missing list directory ${path}`);const prefix=path==='/'?'/':path+'/';const rows=[];
    for(const dir of [...dirs].sort()){if(dir===path||!dir.startsWith(prefix))continue;const tail=dir.slice(prefix.length);if(!recursive&&tail.includes('/'))continue;rows.push({path:dir,kind:'directory',size:0});}
    for(const [file,text] of [...files.entries()].sort(([a],[b])=>a.localeCompare(b))){if(!file.startsWith(prefix))continue;const tail=file.slice(prefix.length);if(!recursive&&tail.includes('/'))continue;rows.push({path:file,kind:'file',size:new TextEncoder().encode(text).byteLength});}
    return rows;
  },
  async move(from,to,{overwrite=false}={}){
    from=normalize(from);to=normalize(to);
    const fromFile=files.has(from),fromDir=dirs.has(from);if(!fromFile&&!fromDir)throw new Error(`missing move source ${from}`);
    if((files.has(to)||dirs.has(to))&&!overwrite)throw new Error(`destination exists ${to}`);
    if(fromFile){ensureDir(parent(to));files.set(to,files.get(from));files.delete(from);return {path:to,kind:'file'};}
    const movedFiles=[...files.entries()].filter(([path])=>path===from||path.startsWith(from+'/'));
    const movedDirs=[...dirs].filter(path=>path===from||path.startsWith(from+'/')).sort((a,b)=>a.length-b.length);
    ensureDir(parent(to));
    for(const path of movedDirs){dirs.delete(path);dirs.add(to+path.slice(from.length));}
    for(const [path,text] of movedFiles){files.delete(path);files.set(to+path.slice(from.length),text);}
    return {path:to,kind:'directory'};
  },
  async remove(path){path=normalize(path);files.delete(path);for(const key of [...files.keys()])if(key.startsWith(path+'/'))files.delete(key);for(const key of [...dirs])if(key===path||key.startsWith(path+'/'))dirs.delete(key);return true;}
};

globalThis.RiftOSCore={
  native:{call:async()=>{throw new Error('Binder should not be used by corpus build');}},
  fs:fsMock,
  path:{normalize,isAbsolute:value=>String(value||'').startsWith('/')||/^[A-Za-z]:($|\/)/.test(String(value||'')),join,canonical:value=>normalize(value).replace(/^\/D:\/Workspace(?=\/|$)/i,'/workspace')}
};
globalThis.RiftWorkspace={stat:async()=>null,readText:async()=>null,history:async()=>[],previewPatch:async()=>({valid:true}),applyPatch:async()=>({historyId:'test'})};

await import(new URL('../src/riftllm-bridge.js?corpus-test=1',import.meta.url));
const api=globalThis.RiftLlmBridge;
assert.ok(api?.corpusSynth,'corpusSynth public bridge method missing');
assert.ok(api?.corpusBuild,'corpusBuild public bridge method missing');
assert.ok(api?.corpusStatus,'corpusStatus public bridge method missing');
const shardVectorMaterial=`part-00000.jsonl\t3\t${'a'.repeat(64)}\npart-00001.jsonl\t5\t${'b'.repeat(64)}\n`;
assert.equal(createHash('sha256').update(shardVectorMaterial,'utf8').digest('hex'),'23eac9b3140464bc1a798481d04a8f40fe78c7246829b8c41ccebd46db051303');
const descriptorDigest=rows=>createHash('sha256').update([...rows].sort((a,b)=>a.path.localeCompare(b.path)).map(row=>`${row.path.split('/').pop()}\t${row.utf8Bytes}\t${row.sha256}\n`).join(''),'utf8').digest('hex');

const categories=['prose','code','non_ascii'];
const rows=[];
for(const category of categories){
  for(let index=0;index<60;index++)rows.push({
    id:`${category}:${String(index).padStart(3,'0')}`,
    category,
    domain:category==='code'?'programming':category==='non_ascii'?'multilingual':'general',
    origin:'original',
    source_project:'',
    text:`${category} custom corpus example ${index} :: ${category==='non_ascii'?'café 東京 λ':'deterministic private training material'}.`
  });
}
const input='/workspace/RiftLLM/tokenizer/private/samples.jsonl';
await fsMock.writeText(input,rows.map(row=>JSON.stringify(row)).join('\n')+'\n');
const synth=await api.corpusSynth(input,undefined,undefined,{countPerCategory:12,seed:'rift-corpus-synth-v1-a'});
assert.equal(synth.format,'rift-corpus-synth-v1');
assert.equal(synth.generator,'rift-corpus-synthesizer-v1');
assert.equal(synth.synthesizedCount,36);
assert.equal(synth.totalCount,216);
assert.deepEqual(synth.categoryCounts,{code:72,non_ascii:72,prose:72});
assert.equal(synth.outputLayout,'sharded-jsonl');
assert.equal(synth.outputHashMode,'rift-shard-set-v1');
assert.ok(synth.outputUtf8Bytes>0);
assert.ok(synth.outputShards.length>=1&&synth.outputShards.every(row=>row.utf8Bytes<=3*1024*1024));
assert.ok(dirs.has('/workspace/RiftLLM/tokenizer/private/synthesized'));
assert.ok(files.has('/workspace/RiftLLM/tokenizer/private/synthesized/part-00000.jsonl'));
assert.ok(files.has('/workspace/RiftLLM/tokenizer/private/synth-manifest.json'));
const result=await api.corpusBuild('/workspace/RiftLLM/tokenizer/private/synthesized',undefined,{heldoutPermyriad:5000,seed:'rift-corpus-test-seed'});
assert.equal(result.format,'rift-corpus-v1');
assert.equal(result.sampleCount,216);
assert.equal(result.trainCount+result.heldoutCount,216);
assert.ok(result.trainCount>0&&result.heldoutCount>0);
assert.equal(result.backupCleanupPending,false);
assert.equal(result.trainLayout,'sharded-jsonl');
assert.equal(result.trainHashMode,'rift-shard-set-v1');
assert.equal(result.heldoutLayout,'sharded-jsonl');
assert.equal(result.heldoutHashMode,'rift-shard-set-v1');
assert.ok(result.trainShards.length>=1&&result.trainShards.every(row=>row.utf8Bytes<=3*1024*1024));
assert.ok(result.heldoutShards.length>=1&&result.heldoutShards.every(row=>row.utf8Bytes<=3*1024*1024));
assert.equal(result.trainSha256,descriptorDigest(result.trainShards));
assert.equal(result.heldoutJsonlSha256,descriptorDigest(result.heldoutShards));
assert.ok(result.heldoutBenchmarkCount<=4096);
assert.equal(result.heldoutBenchmarkLimits.maxUtf8Bytes,3*1024*1024);
assert.equal(result.heldoutBenchmarkLimits.maxSampleUtf8Bytes,4*1024);
for(const path of ['heldout.tsv','corpus-manifest.json'])assert.ok(files.has(`/workspace/RiftLLM/tokenizer/private/build/${path}`),`missing ${path}`);
assert.ok(files.has('/workspace/RiftLLM/tokenizer/private/build/train/part-00000.jsonl'));
assert.ok(files.has('/workspace/RiftLLM/tokenizer/private/build/heldout/part-00000.jsonl'));
const status=await api.corpusStatus();
assert.equal(status.available,true);
assert.equal(status.manifest.sampleCount,216);
assert.equal(status.manifest.normalization,'identity-utf8');

await assert.rejects(()=>api.corpusSynth('/workspace/RiftOS-main/private.jsonl'),/must stay under/);
await fsMock.mkdir('/workspace/RiftLLM/tokenizer/private/base-dir');
await assert.rejects(()=>api.corpusSynth('/workspace/RiftLLM/tokenizer/private/base-dir'),/base must be one authored JSONL file/);
await assert.rejects(()=>api.corpusSynth(input,'/workspace/outside.jsonl'),/must stay under/);
await assert.rejects(()=>api.corpusSynth(input,undefined,undefined,{countPerCategory:20001,seed:'rift-corpus-synth-v1-a'}),/1\.\.20000/);
await assert.rejects(()=>api.corpusBuild('/workspace/RiftOS-main/private.jsonl'),/must stay under/);
const blocked={...rows[0],id:'blocked:001',text:'blocked source sample',source_project:'RiftOS'};
await fsMock.writeText(input,[JSON.stringify(blocked),...rows.slice(1).map(row=>JSON.stringify(row))].join('\n')+'\n');
await assert.rejects(()=>api.corpusBuild(input,undefined,{heldoutPermyriad:5000,seed:'rift-corpus-test-seed'}),/unfinished Rift project source is excluded/);

console.log('RiftLLM local RiftCorpus build contract OK');
