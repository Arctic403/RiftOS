import assert from 'node:assert/strict';
import fs from 'node:fs';
import { webcrypto, createHash } from 'node:crypto';

const gradleSource=fs.readFileSync('android/app/build.gradle.kts','utf8');
assert.ok(!gradleSource.includes('riftllm-bridge.js'),'retained RiftLLM text-encoding bridge must not be packaged');

globalThis.crypto ||= webcrypto;
globalThis.btoa ||= value=>Buffer.from(value,'binary').toString('base64');

const CHUNK=192*1024;
const artifactPath='/workspace/RiftLLM/tokenizer/output/rift-token-a-frequency-v2.riftbpe';
const heldoutPath='/workspace/RiftLLM/tokenizer/challenges/rift-tokenizer-challenge-v2.tsv';
const files=new Map([
  [artifactPath,'A'.repeat(CHUNK+17001)],
  [heldoutPath,'prose\t'+('held-out '.repeat(51000))+'\n']
]);
const uploads=new Map();
const calls=[];
const sha=value=>createHash('sha256').update(Buffer.from(value,'utf8')).digest('hex');

const nativeCall=async(method,args)=>{
  assert.equal(method,'riftllm.dev');
  const op=args.op,request=args.request||{};calls.push({op,request});
  if(op==='text_encoding_begin'){
    assert.ok(['artifact','heldout'].includes(request.slot));
    assert.ok(request.totalBytes>0);
    assert.match(request.sha256,/^[0-9a-f]{64}$/);
    uploads.set(request.slot,{expected:request.sha256,bytes:Buffer.alloc(0)});
    return {slot:request.slot,maxChunkBytes:CHUNK,target:request.slot==='artifact'?'candidate.riftbpe':'heldout.tsv'};
  }
  if(op==='text_encoding_append'){
    const state=uploads.get(request.slot);assert.ok(state);
    assert.equal(request.offset,state.bytes.length);
    const chunk=Buffer.from(request.dataBase64,'base64');assert.ok(chunk.length>0&&chunk.length<=CHUNK);
    state.bytes=Buffer.concat([state.bytes,chunk]);
    return {slot:request.slot,receivedBytes:state.bytes.length,totalBytes:state.bytes.length,complete:false};
  }
  if(op==='text_encoding_commit'){
    const state=uploads.get(request.slot);assert.ok(state);
    const actual=createHash('sha256').update(state.bytes).digest('hex');assert.equal(actual,state.expected);
    return {slot:request.slot,committed:true,sha256:actual,bytes:state.bytes.length};
  }
  if(op==='text_encoding_start'){
    assert.equal(request.artifactSha256,uploads.get('artifact').expected);
    assert.equal(request.corpusSha256,uploads.get('heldout').expected);
    assert.equal(request.expectedCandidateId,'rift-token-a-frequency-v2');
    return {schema:'riftllm-text-encoding-dev-job-v1',active:true,state:'queued',jobId:'test-job',artifactSha256:request.artifactSha256,corpusSha256:request.corpusSha256,expectedCandidateId:request.expectedCandidateId};
  }
  if(op==='text_encoding_status')return {schema:'riftllm-text-encoding-dev-job-v1',active:false,state:'complete',recordId:'run-text-encoding-test.json'};
  throw new Error(`unexpected native op ${op}`);
};

globalThis.RiftOSCore={
  native:{call:nativeCall},
  fs:{
    async stat(path){const value=files.get(path);return value==null?null:{path,kind:'file',size:Buffer.byteLength(value,'utf8')};},
    async readText(path){return files.get(path)??null;}
  },
  path:{
    isAbsolute:value=>String(value||'').startsWith('/'),
    normalize:value=>String(value||'').replace(/\\/g,'/'),
    join:(a,b)=>`${String(a).replace(/\/$/,'')}/${String(b).replace(/^\//,'')}`,
    canonical:value=>String(value||'').replace(/^\/workspace\//,'')
  }
};
globalThis.RiftWorkspace={history:async()=>[],previewPatch:async()=>({valid:true}),applyPatch:async()=>({historyId:'x'}),stat:async()=>null,readText:async()=>null};

await import(new URL('../src/riftllm-bridge.js?text-encoding-contract=1',import.meta.url));
const bridge=globalThis.RiftLlmBridge;
const started=await bridge.textEncodingEval('a2','challenge');
assert.equal(started.candidateId,'rift-token-a-frequency-v2');
assert.equal(started.corpusLane,'challenge');
assert.equal(started.artifact.sha256,sha(files.get(artifactPath)));
assert.equal(started.heldout.sha256,sha(files.get(heldoutPath)));
assert.equal(started.job.expectedCandidateId,'rift-token-a-frequency-v2');

const appendCalls=calls.filter(row=>row.op==='text_encoding_append');
assert.ok(appendCalls.length>=4,'expected multi-chunk artifact + held-out uploads');
for(const row of appendCalls)assert.ok(Buffer.from(row.request.dataBase64,'base64').length<=CHUNK);
assert.deepEqual(calls.filter(row=>row.op==='text_encoding_begin').map(row=>row.request.slot),['artifact','heldout']);
assert.equal(calls.at(-1).op,'text_encoding_start');
await assert.rejects(()=>bridge.textEncodingEval('/workspace/arbitrary.riftbpe'),/candidate must be a, b, a2 or b2/i);
await assert.rejects(()=>bridge.textEncodingEval('a2','/workspace/arbitrary.tsv'),/corpus lane must be heldout or challenge/i);
const status=await bridge.textEncodingStatus();assert.equal(status.recordId,'run-text-encoding-test.json');

console.log('RiftLLM retained Text Encoding Lab bridge regression contract OK');
