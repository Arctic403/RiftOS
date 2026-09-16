const core=globalThis.RiftOSCore;
const workspace=globalThis.RiftWorkspace;
if(!core?.native||!workspace)throw new Error("RiftLLM bridge requires RiftOS native + workspace authority");

const PROJECT_ROOT="RiftLLM";
const TARGET_REPO="Arctic403/RiftLLM";
const TARGET_BRANCH="main";
const MAX_PATCH_CHANGES=500;
const utf8=new TextEncoder();

function clone(value){return value==null?value:JSON.parse(JSON.stringify(value));}
function normalizeProjectPath(value){
  let raw=String(value??"").trim().replace(/\\/g,"/");
  for(const prefix of ["/workspace/RiftLLM/","workspace/RiftLLM/","/D:/Workspace/RiftLLM/","D:/Workspace/RiftLLM/","/RiftLLM/","RiftLLM/"])if(raw.startsWith(prefix)){raw=raw.slice(prefix.length);break;}
  raw=raw.replace(/^\/+/,"");
  if(!raw)throw new Error("RiftLLM project path is required");
  const parts=raw.split("/");
  if(parts.some(part=>!part||part==="."||part===".."||part.includes("\0")))throw new Error("Invalid RiftLLM project path");
  return parts.join("/");
}
function canonicalPath(relative){return `${PROJECT_ROOT}/${normalizeProjectPath(relative)}`;}
function resolveRiftFs(cwd,value){const raw=String(value||"").trim();if(!raw)throw new Error("RiftFS source path is required");return core.path.isAbsolute(raw)?core.path.normalize(raw):core.path.join(cwd||"/",raw);}
function hex(bytes){return [...new Uint8Array(bytes)].map(byte=>byte.toString(16).padStart(2,"0")).join("");}
async function sha256Text(text){return hex(await crypto.subtle.digest("SHA-256",utf8.encode(String(text??""))));}

const CORPUS_FORMAT="rift-corpus-v1";
const CORPUS_BUILDER="rift-corpus-builder-v1";
const CORPUS_ROOT="/workspace/RiftLLM/tokenizer/private";
const CORPUS_DEFAULT_INPUT=`${CORPUS_ROOT}/samples.jsonl`;
const CORPUS_DEFAULT_OUTPUT=`${CORPUS_ROOT}/build`;
const CORPUS_DEFAULT_SEED="rift-corpus-v1-split-a";
const CORPUS_DEFAULT_HELDOUT=1000;
const CORPUS_MAX_SAMPLES=2000000;
const CORPUS_MAX_SAMPLE_BYTES=16*1024;
const CORPUS_SHARD_SET_ID="rift-shard-set-v1";
const CORPUS_SHARD_NAME_RE=/^[A-Za-z0-9._-]{1,96}\.jsonl$/;
const CORPUS_MAX_SHARD_BYTES=3*1024*1024;
const CORPUS_HELDOUT_BENCHMARK_MAX_SAMPLES=4096;
const CORPUS_HELDOUT_BENCHMARK_MAX_BYTES=3*1024*1024;
const CORPUS_HELDOUT_BENCHMARK_MAX_SAMPLE_BYTES=4*1024;
const CORPUS_CATEGORIES=Object.freeze(["prose","code","non_ascii"]);
const CORPUS_ORIGINS=Object.freeze(["original","public_reference_rewrite"]);
const CORPUS_BLOCKED_PROJECTS=Object.freeze(["riftllm","riftos","vortex3d","vtxbuilder","vortexscript"]);
const CORPUS_BLOCKED_URIS=Object.freeze([
  "github.com/arctic403/riftllm","github.com/arctic403/riftos","github.com/arctic403/vortex3d",
  "github.com/arctic403/vtxbuilder","github.com/arctic403/vortexscript"
]);
const CORPUS_ID_RE=/^[A-Za-z0-9._:-]{1,160}$/;
const CORPUS_FIELDS=new Set(["id","category","domain","origin","text","source_project","reference_uri","reference_title","reference_license","notes"]);
const CORPUS_SYNTH_FORMAT="rift-corpus-synth-v1";
const CORPUS_SYNTH_GENERATOR="rift-corpus-synthesizer-v1";
const CORPUS_SYNTH_TEMPLATE="rift-synth-templates-v1";
const CORPUS_SYNTH_SEED="rift-corpus-synth-v1-a";
const CORPUS_SYNTH_DEFAULT_COUNT=12000;
const CORPUS_SYNTH_MAX_COUNT=20000;
const CORPUS_SYNTH_OUTPUT=`${CORPUS_ROOT}/synthesized`;
const CORPUS_SYNTH_MANIFEST=`${CORPUS_ROOT}/synth-manifest.json`;

function sortedJsonValue(value){
  if(Array.isArray(value))return value.map(sortedJsonValue);
  if(value&&typeof value==="object")return Object.fromEntries(Object.keys(value).sort().map(key=>[key,sortedJsonValue(value[key])]));
  return value;
}
function canonicalJson(value){
  if(value===null||typeof value!=="object")return JSON.stringify(value);
  if(Array.isArray(value))return `[${value.map(canonicalJson).join(",")}]`;
  return `{${Object.keys(value).sort().map(key=>`${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
}
function corpusLeaf(path){return String(path||"").replace(/\\/g,"/").split("/").filter(Boolean).pop()||"";}
function corpusShardDescriptorMaterial(descriptors){return [...descriptors].sort((a,b)=>a.name<b.name?-1:a.name>b.name?1:0).map(item=>`${item.name}\t${item.utf8Bytes}\t${item.sha256}\n`).join("");}
async function corpusShardSetSha(descriptors){return sha256Text(corpusShardDescriptorMaterial(descriptors));}
function corpusShardRows(descriptors,prefix=""){return descriptors.map(item=>({path:prefix?`${prefix.replace(/\/$/,"")}/${item.name}`:item.name,sampleCount:item.sampleCount,utf8Bytes:item.utf8Bytes,sha256:item.sha256}));}
function requiredCorpusString(row,key,line){const value=row?.[key];if(typeof value!=="string"||!value.trim())throw new Error(`line ${line}: ${key} must be a non-empty string`);return value;}
function normalizeCorpusPath(value,fallback){
  const raw=String(value||fallback||"").trim();if(!raw)throw new Error("RiftCorpus path is required");
  const display=core.path.normalize(core.path.isAbsolute(raw)?raw:core.path.join("/workspace/RiftLLM",raw));
  const path=core.path.canonical(display);
  if(path!==CORPUS_ROOT&&!path.startsWith(`${CORPUS_ROOT}/`))throw new Error(`RiftCorpus path must stay under ${CORPUS_ROOT}`);
  return path;
}
async function validateCorpusSample(row,line){
  if(!row||typeof row!=="object"||Array.isArray(row))throw new Error(`line ${line}: each JSONL record must be an object`);
  const unknown=Object.keys(row).filter(key=>!CORPUS_FIELDS.has(key)).sort();if(unknown.length)throw new Error(`line ${line}: unknown fields: ${unknown.join(", ")}`);
  const id=requiredCorpusString(row,"id",line);if(!CORPUS_ID_RE.test(id))throw new Error(`line ${line}: invalid id`);
  const category=requiredCorpusString(row,"category",line);if(!CORPUS_CATEGORIES.includes(category))throw new Error(`line ${line}: invalid category`);
  const domain=requiredCorpusString(row,"domain",line).trim();
  const origin=requiredCorpusString(row,"origin",line);if(!CORPUS_ORIGINS.includes(origin))throw new Error(`line ${line}: invalid origin`);
  const text=requiredCorpusString(row,"text",line),textBytes=utf8.encode(text);if(textBytes.byteLength>CORPUS_MAX_SAMPLE_BYTES)throw new Error(`line ${line}: sample exceeds ${CORPUS_MAX_SAMPLE_BYTES} UTF-8 bytes`);if(text.includes("\0"))throw new Error(`line ${line}: NUL bytes are not allowed`);
  const sourceProject=String(row.source_project||"").trim(),normalizedProject=sourceProject.toLowerCase().replace(/[^a-z0-9]+/g,"");
  if(CORPUS_BLOCKED_PROJECTS.some(name=>normalizedProject.includes(name)))throw new Error(`line ${line}: unfinished Rift project source is excluded in RiftCorpus V1: ${sourceProject}`);
  if(origin==="public_reference_rewrite"){
    if(!sourceProject)throw new Error(`line ${line}: public_reference_rewrite requires source_project provenance`);
    const uri=requiredCorpusString(row,"reference_uri",line).trim();if(!/^https?:\/\//i.test(uri))throw new Error(`line ${line}: public reference URI must be http(s)`);
    if(CORPUS_BLOCKED_URIS.some(fragment=>uri.toLowerCase().includes(fragment)))throw new Error(`line ${line}: unfinished Rift project references are excluded in this phase`);
    requiredCorpusString(row,"reference_title",line);requiredCorpusString(row,"reference_license",line);
  }else for(const key of ["reference_uri","reference_title","reference_license"])if(String(row[key]||"").trim())throw new Error(`line ${line}: ${key} is only valid for public_reference_rewrite`);
  const out={id,category,domain,origin,text,source_project:sourceProject};
  for(const key of ["reference_uri","reference_title","reference_license","notes"]){const value=row[key];if(typeof value==="string"&&value.trim())out[key]=value.trim();}
  out.text_sha256=await sha256Text(text);return out;
}
async function corpusJsonlSources(inputPath){
  const stat=await core.fs.stat(inputPath);if(!stat)throw new Error(`RiftCorpus input not found: ${inputPath}`);
  if(stat.kind==="file")return {layout:"file",sources:[{path:inputPath,name:corpusLeaf(inputPath)}]};
  if(stat.kind!=="directory")throw new Error(`RiftCorpus input must be a JSONL file or shard directory: ${inputPath}`);
  const listed=await core.fs.list(inputPath,{recursive:false}),sources=(Array.isArray(listed)?listed:[])
    .filter(row=>row?.kind==="file"&&String(row.path||row.name||"").toLowerCase().endsWith(".jsonl"))
    .map(row=>{const path=normalizeCorpusPath(row.path||core.path.join(inputPath,row.name),inputPath);return {path,name:corpusLeaf(path)};});
  const invalid=sources.find(row=>!CORPUS_SHARD_NAME_RE.test(row.name));if(invalid)throw new Error(`invalid RiftCorpus shard name: ${invalid.name}`);
  sources.sort((a,b)=>a.name<b.name?-1:a.name>b.name?1:0);
  if(!sources.length)throw new Error(`RiftCorpus shard directory contains no .jsonl files: ${inputPath}`);
  return {layout:"sharded-jsonl",sources};
}
async function loadCorpusSamples(inputPath){
  const {layout,sources}=await corpusJsonlSources(inputPath),samples=[],ids=new Set(),textHashes=new Map(),descriptors=[];let globalLine=0;
  for(const sourceInfo of sources){
    const source=await core.fs.readText(sourceInfo.path);if(source==null)throw new Error(`RiftCorpus shard disappeared: ${sourceInfo.path}`);
    const text=String(source),lines=text.split(/\r?\n/);let sourceCount=0;
    for(let index=0;index<lines.length;index++){
      const raw=lines[index];if(!raw.trim())continue;globalLine++;let parsed;try{parsed=JSON.parse(raw);}catch(error){throw new Error(`${sourceInfo.name}:${index+1}: invalid JSON: ${error?.message||error}`);}
      const sample=await validateCorpusSample(parsed,globalLine);if(ids.has(sample.id))throw new Error(`${sourceInfo.name}:${index+1}: duplicate sample id ${sample.id}`);ids.add(sample.id);
      const prior=textHashes.get(sample.text_sha256);if(prior)throw new Error(`${sourceInfo.name}:${index+1}: duplicate text matches sample ${prior}`);textHashes.set(sample.text_sha256,sample.id);
      samples.push(sample);sourceCount++;if(samples.length>CORPUS_MAX_SAMPLES)throw new Error(`corpus exceeds ${CORPUS_MAX_SAMPLES} samples`);
    }
    descriptors.push({name:sourceInfo.name,utf8Bytes:utf8.encode(text).byteLength,sha256:await sha256Text(text),sampleCount:sourceCount});
  }
  if(!samples.length)throw new Error("corpus contains no samples");
  const inputHashMode=layout==="file"?"file-sha256":CORPUS_SHARD_SET_ID,inputSha256=layout==="file"?descriptors[0].sha256:await corpusShardSetSha(descriptors);
  return {samples,inputHashMode,inputSha256,inputShards:corpusShardRows(descriptors),layout};
}
async function corpusSplitBucket(sample,seed){
  const digest=new Uint8Array(await crypto.subtle.digest("SHA-256",utf8.encode(`${seed}\0${sample.id}\0${sample.text_sha256}`)));
  let value=0;for(let index=0;index<8;index++)value=(value*256+digest[index])%10000;return value;
}
async function splitCorpus(samples,seed,heldoutPermyriad){
  const heldoutValue=Number(heldoutPermyriad);if(!Number.isInteger(heldoutValue)||heldoutValue<1||heldoutValue>5000)throw new Error("heldoutPermyriad must be an integer in 1..5000");
  const train=[],heldout=[];for(const sample of [...samples].sort((a,b)=>a.id.localeCompare(b.id)))(await corpusSplitBucket(sample,seed)<heldoutValue?heldout:train).push(sample);
  for(const category of CORPUS_CATEGORIES){if(!train.some(row=>row.category===category))throw new Error(`training split has no ${category} samples; add more custom data`);if(!heldout.some(row=>row.category===category))throw new Error(`held-out split has no ${category} samples; add more custom data`);}
  return {train,heldout};
}
function corpusCounts(rows,key){const counts={};for(const row of rows){const value=String(row[key]||"");counts[value]=(counts[value]||0)+1;}return Object.fromEntries(Object.keys(counts).sort().map(key=>[key,counts[key]]));}
function escapeCorpusTsv(text){return String(text).replace(/\\/g,"\\\\").replace(/\t/g,"\\t").replace(/\r/g,"\\r").replace(/\n/g,"\\n");}
async function ensureCorpusDir(path){if(!(await core.fs.stat(path)))await core.fs.mkdir(path);const stat=await core.fs.stat(path);if(!stat||stat.kind!=="directory")throw new Error(`RiftCorpus output is not a directory: ${path}`);}
async function writeCorpusShards(directory,rows){
  await ensureCorpusDir(directory);const descriptors=[];let text="",bytes=0,count=0,index=0;
  const flush=async()=>{if(!count)return;const name=`part-${String(index).padStart(5,"0")}.jsonl`,path=`${directory}/${name}`;await core.fs.writeText(path,text);descriptors.push({name,utf8Bytes:bytes,sha256:await sha256Text(text),sampleCount:count});text="";bytes=0;count=0;index++;};
  for(const row of rows){const line=canonicalJson(row)+"\n",lineBytes=utf8.encode(line).byteLength;if(lineBytes>CORPUS_MAX_SHARD_BYTES)throw new Error(`one canonical JSONL row exceeds shard budget (${lineBytes} > ${CORPUS_MAX_SHARD_BYTES})`);if(count&&bytes+lineBytes>CORPUS_MAX_SHARD_BYTES)await flush();text+=line;bytes+=lineBytes;count++;}
  await flush();if(!descriptors.length)throw new Error("cannot write an empty shard set");return descriptors;
}
function corpusHeldoutBenchmark(rows){
  const groups=Object.fromEntries([...CORPUS_CATEGORIES].sort().map(category=>[category,rows.filter(row=>row.category===category).sort((a,b)=>a.id.localeCompare(b.id))])),positions=Object.fromEntries(Object.keys(groups).map(category=>[category,0])),selected=[];let text="",bytes=0;
  while(selected.length<CORPUS_HELDOUT_BENCHMARK_MAX_SAMPLES){let progressed=false;for(const category of Object.keys(groups)){
    const index=positions[category];if(index>=groups[category].length)continue;const row=groups[category][index];positions[category]++;progressed=true;
    const sampleBytes=utf8.encode(row.text).byteLength;if(sampleBytes>CORPUS_HELDOUT_BENCHMARK_MAX_SAMPLE_BYTES)continue;
    const line=`${row.category}\t${escapeCorpusTsv(row.text)}\n`,lineBytes=utf8.encode(line).byteLength;if(bytes+lineBytes>CORPUS_HELDOUT_BENCHMARK_MAX_BYTES)return {rows:selected,text,bytes};
    selected.push(row);text+=line;bytes+=lineBytes;if(selected.length>=CORPUS_HELDOUT_BENCHMARK_MAX_SAMPLES)break;
  }if(!progressed)break;}
  for(const category of CORPUS_CATEGORIES)if(!selected.some(row=>row.category===category))throw new Error(`held-out benchmark contains no ${category} samples`);return {rows:selected,text,bytes};
}
async function replaceCorpusDirectory(stage,target){
  const backup=normalizeCorpusPath(`${target}.backup-${Date.now()}-${Math.random().toString(36).slice(2,10)}`),existing=await core.fs.stat(target);let backedUp=false;
  if(existing){if(existing.kind!=="directory")throw new Error(`RiftCorpus output exists and is not a directory: ${target}`);await core.fs.move(target,backup,{overwrite:false});backedUp=true;}
  try{await core.fs.move(stage,target,{overwrite:false});}catch(error){if(backedUp&&await core.fs.stat(backup))await core.fs.move(backup,target,{overwrite:false}).catch(()=>{});throw error;}
  let backupCleanupPending=false;if(backedUp&&await core.fs.stat(backup))backupCleanupPending=!(await core.fs.remove(backup).catch(()=>false));return backupCleanupPending;
}
async function corpusBuild(input=CORPUS_DEFAULT_INPUT,outputDir=CORPUS_DEFAULT_OUTPUT,options={}){
  const inputPath=normalizeCorpusPath(input,CORPUS_DEFAULT_INPUT),target=normalizeCorpusPath(outputDir,CORPUS_DEFAULT_OUTPUT),seed=String(options.seed||CORPUS_DEFAULT_SEED),heldoutPermyriad=options.heldoutPermyriad==null?CORPUS_DEFAULT_HELDOUT:Number(options.heldoutPermyriad);
  if(target===inputPath||inputPath.startsWith(`${target}/`))throw new Error("RiftCorpus output cannot contain its input");
  const source=await loadCorpusSamples(inputPath),{train,heldout}=await splitCorpus(source.samples,seed,heldoutPermyriad),benchmark=corpusHeldoutBenchmark(heldout);
  const stage=normalizeCorpusPath(`${target}.stage-${Date.now()}-${Math.random().toString(36).slice(2,10)}`);await ensureCorpusDir(stage);
  try{
    const trainDescriptors=await writeCorpusShards(`${stage}/train`,train),heldoutDescriptors=await writeCorpusShards(`${stage}/heldout`,heldout);
    await core.fs.writeText(`${stage}/heldout.tsv`,benchmark.text);
    const manifest={format:CORPUS_FORMAT,builder:CORPUS_BUILDER,splitAlgorithm:"sha256(seed\\0id\\0text_sha256)-u64-mod10000",splitSeed:seed,heldoutPermyriad,inputHashMode:source.inputHashMode,inputSha256:source.inputSha256,inputShards:source.inputShards,sampleCount:source.samples.length,trainCount:train.length,heldoutCount:heldout.length,categoryCounts:corpusCounts(source.samples,"category"),domainCounts:corpusCounts(source.samples,"domain"),originCounts:corpusCounts(source.samples,"origin"),trainLayout:"sharded-jsonl",trainHashMode:CORPUS_SHARD_SET_ID,trainSha256:await corpusShardSetSha(trainDescriptors),trainShards:corpusShardRows(trainDescriptors,"train"),heldoutLayout:"sharded-jsonl",heldoutHashMode:CORPUS_SHARD_SET_ID,heldoutJsonlSha256:await corpusShardSetSha(heldoutDescriptors),heldoutShards:corpusShardRows(heldoutDescriptors,"heldout"),heldoutBenchmarkCount:benchmark.rows.length,heldoutBenchmarkCategoryCounts:corpusCounts(benchmark.rows,"category"),heldoutTsvUtf8Bytes:benchmark.bytes,heldoutTsvSha256:await sha256Text(benchmark.text),heldoutBenchmarkLimits:{maxSamples:CORPUS_HELDOUT_BENCHMARK_MAX_SAMPLES,maxUtf8Bytes:CORPUS_HELDOUT_BENCHMARK_MAX_BYTES,maxSampleUtf8Bytes:CORPUS_HELDOUT_BENCHMARK_MAX_SAMPLE_BYTES},unfinishedRiftSourceExcluded:[...CORPUS_BLOCKED_PROJECTS].sort(),normalization:"identity-utf8"};
    const manifestText=JSON.stringify(sortedJsonValue(manifest),null,2)+"\n",manifestSha256=await sha256Text(manifestText);await core.fs.writeText(`${stage}/corpus-manifest.json`,manifestText);
    const backupCleanupPending=await replaceCorpusDirectory(stage,target);return {...manifest,manifestSha256,inputPath,outputDir:target,backupCleanupPending};
  }catch(error){if(await core.fs.stat(stage))await core.fs.remove(stage).catch(()=>{});throw error;}
}
async function corpusStatus(outputDir=CORPUS_DEFAULT_OUTPUT){
  const target=normalizeCorpusPath(outputDir,CORPUS_DEFAULT_OUTPUT),manifestPath=`${target}/corpus-manifest.json`,stat=await core.fs.stat(manifestPath);if(!stat)return {available:false,outputDir:target};
  const text=await core.fs.readText(manifestPath);let manifest;try{manifest=JSON.parse(text);}catch{throw new Error(`Invalid RiftCorpus manifest: ${manifestPath}`);}
  return {available:true,outputDir:target,manifest,manifestSha256:await sha256Text(text)};
}

const SYNTH_PROSE_DOMAINS=Object.freeze(["systems","networking","storage","security","performance","ai_ml","structured_data","concurrency","testing","troubleshooting","math_science","general_writing"]);
const SYNTH_SUBJECTS=Object.freeze(["scheduler","parser","serializer","worker pool","cache","queue","model loader","benchmark harness","file index","transport","validator","runtime","allocator","event loop","manifest writer","request router","token stream","state machine","database adapter","configuration layer"]);
const SYNTH_ACTIONS=Object.freeze(["validates its inputs before changing state","records enough metadata to reproduce a result","keeps ownership of mutable state explicit","separates policy from mechanism","uses bounded work queues under load","writes complete replacements before swapping active data","checks invariants where data enters","distinguishes temporary failures from permanent errors","keeps evaluation data outside the training path","measures latency and throughput separately","normalizes paths before containment checks","uses stable identifiers instead of timestamps","keeps a simple reference path beside optimized code","limits memory growth with explicit budgets","publishes readiness only after dependencies are verified","treats cancellation as an observable state transition"]);
const SYNTH_CONDITIONS=Object.freeze(["when inputs are malformed","during a cold start","while several workers run concurrently","after a partial network interruption","under low-memory pressure","when the same operation is retried","before a version migration","after configuration changes","while processing a large batch","when a dependency becomes unavailable","during repeated benchmark runs","when a cache entry expires"]);
const SYNTH_EVIDENCE=Object.freeze(["a content hash and stable record id","a bounded retry counter and final error class","before-and-after byte counts","a deterministic manifest with sorted keys","a median across repeated measurements","a round-trip equality check","an explicit state-transition log","a separate held-out result","a checksum plus input-shape metadata","a reproducible test case","a size limit enforced before allocation","an atomic replacement marker"]);
const SYNTH_SECOND=Object.freeze(["That makes failures easier to localize because the evidence is tied to one boundary.","The fallback path keeps the same ownership rules as the primary path.","The result is easier to audit because each transition has a named cause and bounded effect.","This keeps timing noise from becoming a correctness claim.","A later implementation can optimize the mechanism without changing the external contract.","The same record can be compared across runs because identity does not depend on wall-clock time.","This avoids silently converting uncertainty into permission or success.","A recovery path remains available until the replacement is fully verified."]);
const SYNTH_CODE_LANGS=Object.freeze(["python","javascript","typescript","kotlin","cpp","rust","sql","json","yaml","shell"]);
const SYNTH_STEMS=Object.freeze(["alpha","delta","vector","buffer","packet","record","sample","frame","metric","entry","segment","window","batch","cursor","worker","request","result","state","chunk","index"]);
const SYNTH_NONASCII=Object.freeze([
  ["fr","Le composant {subject} valide {obj} avant de publier un nouvel état. La mesure {metric} est répétée et la limite est {value} éléments."],
  ["es","El componente {subject} valida {obj} antes de publicar un estado nuevo. La métrica {metric} se repite y el límite es de {value} elementos."],
  ["de","Die Komponente {subject} prüft {obj}, bevor ein neuer Zustand veröffentlicht wird. Die Messung {metric} wird wiederholt; die Grenze liegt bei {value} Elementen."],
  ["pt","O componente {subject} valida {obj} antes de publicar um novo estado. A métrica {metric} é repetida e o limite é {value} itens."],
  ["it","Il componente {subject} convalida {obj} prima di pubblicare un nuovo stato. La misura {metric} viene ripetuta e il limite è {value} elementi."],
  ["pl","Komponent {subject} sprawdza {obj} przed opublikowaniem nowego stanu. Pomiar {metric} jest powtarzany, a limit wynosi {value} elementów."],
  ["tr","{subject} bileşeni yeni durumu yayımlamadan önce {obj} verisini doğrular. {metric} ölçümü tekrarlanır ve sınır {value} öğedir."],
  ["el","Το στοιχείο {subject} ελέγχει το {obj} πριν δημοσιεύσει νέα κατάσταση. Η μέτρηση {metric} επαναλαμβάνεται και το όριο είναι {value} στοιχεία."],
  ["ru","Компонент {subject} проверяет {obj} перед публикацией нового состояния. Измерение {metric} повторяется, а лимит равен {value} элементам."],
  ["ja","{subject} コンポーネントは新しい状態を公開する前に {obj} を検証します。{metric} の測定を繰り返し、上限を {value} 件にします。"],
  ["ko","{subject} 구성 요소는 새 상태를 게시하기 전에 {obj} 데이터를 검증합니다. {metric} 측정을 반복하고 한도를 {value}개로 둡니다."],
  ["ar","يتحقق المكوّن {subject} من {obj} قبل نشر حالة جديدة. يتكرر قياس {metric} ويكون الحد {value} عنصراً."],
  ["hi","{subject} घटक नई स्थिति प्रकाशित करने से पहले {obj} की जाँच करता है। {metric} माप दोहराया जाता है और सीमा {value} मद है।"]
]);
const SYNTH_NONASCII_SUBJECTS=Object.freeze(["parser","scheduler","cache","router","worker","validator","runtime","index"]);
const SYNTH_NONASCII_OBJECTS=Object.freeze(["input","manifest","request","buffer","record","path","batch","configuration"]);
const SYNTH_NONASCII_METRICS=Object.freeze(["latency","throughput","memory","error rate","token density","queue depth"]);
function synthChoose(values,index,salt){return values[(index*(31+salt*2)+salt*17)%values.length];}
function synthRecord(id,category,domain,text){return {id,category,domain,origin:"original",source_project:"",text,notes:`generated by ${CORPUS_SYNTH_GENERATOR}/${CORPUS_SYNTH_TEMPLATE}`};}
function synthProse(index){const domain=SYNTH_PROSE_DOMAINS[index%SYNTH_PROSE_DOMAINS.length],subject=synthChoose(SYNTH_SUBJECTS,index,1),action=synthChoose(SYNTH_ACTIONS,index,2),condition=synthChoose(SYNTH_CONDITIONS,index,3),evidence=synthChoose(SYNTH_EVIDENCE,index,4),second=synthChoose(SYNTH_SECOND,index,5),limit=16+((index*977+113)%8176),run=(index*7919+104729)%100000,text=`In ${domain} case P${String(index).padStart(5,"0")}, the ${subject} ${action} ${condition}. It records ${evidence}, enforces a working bound of ${limit} units, and labels observation run-${String(run).padStart(5,"0")}. ${second}`;return synthRecord(`synth.prose.${String(index).padStart(5,"0")}`,"prose",domain,text);}
function synthCodeText(index,lang,stem){const limit=16+((index*1543+97)%4096),num=String(index).padStart(5,"0"),ident=`${stem}_${num}`;
  if(lang==="python")return `def fold_${ident}(items):\n    if len(items) > ${limit}: raise ValueError("batch exceeds limit")\n    total = sum((i + 1) * int(v) for i, v in enumerate(items))\n    return {"id": "${ident}", "count": len(items), "total": total}`;
  if(lang==="javascript")return `export function fold_${ident}(items) {\n  if (items.length > ${limit}) throw new RangeError("batch exceeds limit");\n  return items.reduce((sum, v, i) => sum + (i + 1) * Number(v), 0);\n}`;
  if(lang==="typescript")return `type Result${num} = { id: string; value: number };\nfunction normalize_${ident}(value: number): Result${num} {\n  return { id: "${ident}", value: Number.isFinite(value) ? value : 0 };\n}`;
  if(lang==="kotlin")return `data class Result${num}(val count: Int, val checksum: Long)\nfun fold_${ident}(values: List<Int>): Result${num} {\n    require(values.size <= ${limit})\n    var sum = 0L\n    values.forEachIndexed { i, v -> sum += (i + 1L) * v }\n    return Result${num}(values.size, sum)\n}`;
  if(lang==="cpp")return `#include <cstdint>\n#include <vector>\nstd::int64_t fold_${ident}(const std::vector<int>& values) {\n    if (values.size() > ${limit}u) return -1;\n    std::int64_t sum = 0;\n    for (std::size_t i = 0; i < values.size(); ++i) sum += static_cast<std::int64_t>(i + 1) * values[i];\n    return sum;\n}`;
  if(lang==="rust")return `fn fold_${ident}(values: &[i64]) -> Option<i64> {\n    if values.len() > ${limit} { return None; }\n    Some(values.iter().enumerate().map(|(i, v)| (i as i64 + 1) * v).sum())\n}`;
  if(lang==="sql")return `SELECT id, value, updated_at\nFROM ${stem}_records\nWHERE updated_at >= ${1000+index*17} AND state = 'ready'\nORDER BY updated_at DESC, id ASC\nLIMIT ${16+index%240}; -- ${ident}`;
  if(lang==="json")return JSON.stringify(sortedJsonValue({format:"synthetic-record-v1",id:ident,limits:{items:limit,bytes:limit*8},flags:["validate","measure","commit"],enabled:true}),null,2);
  if(lang==="yaml")return `job:\n  id: ${ident}\n  workers: ${1+index%8}\n  policy:\n    validate_before_write: true\n    max_items: ${limit}\n    retry_limit: ${1+index%5}`;
  return `set -eu\ninput="${ident}.json"\noutput="${ident}.checked"\ntest -f "$input"\nbytes=$(wc -c < "$input")\ntest "$bytes" -le ${4096+index*3}\nprintf "%s\\n" "$bytes" > "$output"`;}
function synthCode(index){const lang=SYNTH_CODE_LANGS[index%SYNTH_CODE_LANGS.length],stem=synthChoose(SYNTH_STEMS,index,6);return synthRecord(`synth.code.${String(index).padStart(5,"0")}`,"code",lang,synthCodeText(index,lang,stem));}
function synthNonAscii(index){const [domain,template]=SYNTH_NONASCII[index%SYNTH_NONASCII.length],subject=synthChoose(SYNTH_NONASCII_SUBJECTS,index,7),obj=synthChoose(SYNTH_NONASCII_OBJECTS,index,8),metric=synthChoose(SYNTH_NONASCII_METRICS,index,9),value=16+((index*1237+211)%8176),text=template.replace("{subject}",subject).replace("{obj}",obj).replace("{metric}",metric).replace("{value}",String(value))+` [N${String(index).padStart(5,"0")}:${domain}-${value}]`;return synthRecord(`synth.nonascii.${String(index).padStart(5,"0")}`,"non_ascii",domain,text);}
function stripCorpusHash(row){const {text_sha256,...rest}=row;return rest;}
async function corpusSynth(base=CORPUS_DEFAULT_INPUT,output=CORPUS_SYNTH_OUTPUT,manifestPath=CORPUS_SYNTH_MANIFEST,options={}){
  const basePath=normalizeCorpusPath(base,CORPUS_DEFAULT_INPUT),outputPath=normalizeCorpusPath(output,CORPUS_SYNTH_OUTPUT),manifest=normalizeCorpusPath(manifestPath,CORPUS_SYNTH_MANIFEST),count=options.countPerCategory==null?CORPUS_SYNTH_DEFAULT_COUNT:Number(options.countPerCategory),seed=String(options.seed||CORPUS_SYNTH_SEED);
  const baseStat=await core.fs.stat(basePath);if(!baseStat||baseStat.kind!=="file")throw new Error("RiftCorpus Synthesizer V1 base must be one authored JSONL file");
  if(seed!==CORPUS_SYNTH_SEED)throw new Error(`RiftCorpus Synthesizer V1 seed is pinned to ${CORPUS_SYNTH_SEED}`);if(!Number.isInteger(count)||count<1||count>CORPUS_SYNTH_MAX_COUNT)throw new Error(`count-per-category must be an integer in 1..${CORPUS_SYNTH_MAX_COUNT}`);if(outputPath===basePath)throw new Error("RiftCorpus synth output must differ from the authored base");if(manifest===basePath||manifest===outputPath)throw new Error("RiftCorpus synth manifest must use a separate private path");if(/\.jsonl$/i.test(outputPath))throw new Error("RiftCorpus synth output is a shard directory, not one JSONL file");
  const baseSource=await loadCorpusSamples(basePath),rows=baseSource.samples.map(stripCorpusHash),ids=new Set(rows.map(row=>row.id)),texts=new Set(rows.map(row=>row.text)),generated=[];
  for(let index=1;index<=count;index++)for(const row of [synthProse(index),synthCode(index),synthNonAscii(index)]){if(ids.has(row.id))throw new Error(`synthesized id collides with base: ${row.id}`);if(texts.has(row.text))throw new Error(`synthesized text collides with base/generated data: ${row.id}`);if(utf8.encode(row.text).byteLength>CORPUS_MAX_SAMPLE_BYTES)throw new Error(`synthesized sample exceeds ${CORPUS_MAX_SAMPLE_BYTES} UTF-8 bytes: ${row.id}`);ids.add(row.id);texts.add(row.text);generated.push(row);}
  const all=[...rows,...generated],stage=normalizeCorpusPath(`${outputPath}.stage-${Date.now()}-${Math.random().toString(36).slice(2,10)}`);await ensureCorpusDir(stage);
  try{
    const descriptors=await writeCorpusShards(stage,all),result={format:CORPUS_SYNTH_FORMAT,generator:CORPUS_SYNTH_GENERATOR,templateVersion:CORPUS_SYNTH_TEMPLATE,seed,countPerCategory:count,basePresent:true,baseSha256:baseSource.inputSha256,baseCount:rows.length,synthesizedCount:generated.length,totalCount:all.length,categoryCounts:corpusCounts(all,"category"),domainCounts:corpusCounts(all,"domain"),outputLayout:"sharded-jsonl",outputHashMode:CORPUS_SHARD_SET_ID,outputSha256:await corpusShardSetSha(descriptors),outputUtf8Bytes:descriptors.reduce((sum,item)=>sum+item.utf8Bytes,0),outputShards:corpusShardRows(descriptors),origin:"original",unfinishedRiftSourceIncluded:false};
    const backupCleanupPending=await replaceCorpusDirectory(stage,outputPath),manifestText=JSON.stringify(sortedJsonValue(result),null,2)+"\n";await core.fs.writeText(manifest,manifestText);return {...result,manifestSha256:await sha256Text(manifestText),basePath,outputPath,manifestPath:manifest,backupCleanupPending};
  }catch(error){if(await core.fs.stat(stage))await core.fs.remove(stage).catch(()=>{});throw error;}
}

async function native(op,request={},extra={}){return core.native.call("riftllm.dev",{op,request,...extra});}

async function status(){return native("status");}
async function pair(){
  if(typeof prompt!=="function")throw new Error("Secure RiftLLM token entry is unavailable");
  const token=prompt("RiftLLM Dev API token (stored only in Android Keystore-backed RiftOS secret storage):","");
  if(token==null||!String(token).trim())return {paired:false,cancelled:true};
  return native("pair",{}, {token:String(token).trim()});
}
async function unpair(){return native("unpair");}
async function listStaged(){return native("list_staged");}
async function stagedEntry(relative){const rows=await listStaged();return Array.isArray(rows)?rows.find(row=>row?.path===relative)||null:null;}

async function sync(path){
  const relative=normalizeProjectPath(path),full=canonicalPath(relative),stat=await workspace.stat(full);
  if(!stat)throw new Error(`Canonical RiftLLM source does not exist: ${full}`);
  if(stat.kind!=="file")throw new Error(`Canonical RiftLLM source is not a file: ${full}`);
  const content=await workspace.readText(full),sha256=await sha256Text(content);
  const result=await native("sync_source",{path:relative,content,sha256});
  if(String(result?.sha256||"").toLowerCase()!==sha256)throw new Error(`RiftLLM sync verification failed: ${relative}`);
  return result;
}
async function syncMissing(path){
  const relative=normalizeProjectPath(path),full=canonicalPath(relative);
  if(await workspace.stat(full))throw new Error(`sync-missing refused because canonical source exists: ${full}`);
  return native("sync_missing",{path:relative});
}
async function prepareBaseline(path){
  const relative=normalizeProjectPath(path),active=await stagedEntry(relative);
  if(active)return {relative,alreadyStaged:true};
  const full=canonicalPath(relative),stat=await workspace.stat(full);
  return {relative,alreadyStaged:false,baseline:stat?await sync(relative):await syncMissing(relative)};
}
async function load(path){const {relative}=await prepareBaseline(path);return native("load_source",{path:relative});}
async function stage(path,content,reason="RiftOS RiftLLM bridge"){
  const {relative}=await prepareBaseline(path);
  return native("stage",{path:relative,content:String(content??""),reason:String(reason||"RiftOS RiftLLM bridge")});
}
async function stageDelete(path,reason="RiftOS RiftLLM bridge"){
  const relative=normalizeProjectPath(path),active=await stagedEntry(relative);
  if(!active){const full=canonicalPath(relative),stat=await workspace.stat(full);if(!stat||stat.kind!=="file")throw new Error(`Cannot delete canonical RiftLLM file that does not exist: ${full}`);await sync(relative);}
  return native("delete",{path:relative,reason:String(reason||"RiftOS RiftLLM bridge")});
}
async function unstage(path){return native("unstage",{path:normalizeProjectPath(path)});}
async function reset(){return native("reset");}
async function snapshot(note=""){return native("snapshot",{note:String(note||"")});}
async function listSnapshots(limit=50){const value=Math.max(1,Math.min(200,Number(limit)||50));return native("list_snapshots",{limit:value});}
async function getSnapshot(id="latest"){return native("get_snapshot",{snapshotId:String(id||"latest")});}
async function listBenchmarks(limit=50){const value=Math.max(1,Math.min(200,Number(limit)||50));return native("list_benchmarks",{limit:value});}
async function getBenchmark(id="latest"){return native("get_benchmark",{recordId:String(id||"latest")});}
async function concreteSnapshot(id="latest"){
  const snapshot=await getSnapshot(id),snapshotId=String(snapshot?.id||"").trim();
  if(!snapshotId)throw new Error("RiftLLM snapshot response has no immutable id");
  return {snapshotId,snapshot};
}
function validatePatch(patch){
  if(!patch||typeof patch!=="object"||Array.isArray(patch))throw new Error("RiftLLM patch must be a JSON object");
  if(patch.format!=="riftcity-ai-patch"||Number(patch.version)!==2)throw new Error("RiftLLM patch format/version mismatch");
  if(patch.target_repo!==TARGET_REPO||patch.target_branch!==TARGET_BRANCH)throw new Error("RiftLLM patch target repo/branch mismatch");
  if(!Array.isArray(patch.changes)||patch.changes.length<1||patch.changes.length>MAX_PATCH_CHANGES)throw new Error("RiftLLM patch has an invalid change count");
  for(const change of patch.changes){
    if(!change||typeof change!=="object"||Array.isArray(change))throw new Error("RiftLLM patch change must be an object");
    if(!["write","delete"].includes(change.action))throw new Error(`Unsupported RiftLLM patch action: ${change.action}`);
    const expected=canonicalPath(change.path);
    if(change.path!==expected)throw new Error(`RiftLLM patch path escaped canonical project prefix: ${change.path}`);
    if(!Object.prototype.hasOwnProperty.call(change,"base_sha256"))throw new Error(`RiftLLM patch is missing base_sha256: ${change.path}`);
    if(change.base_sha256!=null&&!/^[a-fA-F0-9]{64}$/.test(String(change.base_sha256)))throw new Error(`RiftLLM patch has an invalid baseline hash: ${change.path}`);
    if(change.action==="delete"&&change.base_sha256==null)throw new Error(`RiftLLM delete cannot target an untracked baseline: ${change.path}`);
    if(change.action==="write"&&typeof change.content!=="string")throw new Error(`RiftLLM write is missing text content: ${change.path}`);
  }
  return patch;
}
async function patchForSnapshot(snapshotId){return validatePatch(await native("get_patch",{snapshotId}));}
async function preview(id="latest"){
  const {snapshotId}=await concreteSnapshot(id),patch=await patchForSnapshot(snapshotId),previewResult=await workspace.previewPatch(patch);
  return {snapshotId,valid:previewResult?.valid===true,preview:previewResult};
}
async function acknowledge(snapshotId,historyId,details={}){
  const id=String(historyId||"").trim();if(!id)throw new Error("Workspace history id is required for RiftLLM publication acknowledgment");
  const history=await workspace.history(),record=history.find(item=>item?.id===id);
  if(!record||record.targetRepo!==TARGET_REPO||record.targetBranch!==TARGET_BRANCH)throw new Error("RiftLLM publication acknowledgment requires a matching Workspace history record");
  return native("ack_publish",{snapshotId:String(snapshotId),historyId:id,details:{source:"RiftOS RiftLLM bridge",previewed:true,...clone(details)}});
}
async function publish(id="latest"){
  const {snapshotId}=await concreteSnapshot(id),patch=await patchForSnapshot(snapshotId),previewResult=await workspace.previewPatch(patch);
  if(previewResult?.valid!==true)throw new Error("RiftLLM Workspace preview did not validate");
  const applied=await workspace.applyPatch(patch);
  try{
    const receipt=await native("ack_publish",{snapshotId,historyId:applied.historyId,details:{source:"RiftOS RiftLLM bridge",previewed:true}});
    return {published:true,acknowledged:true,snapshotId,preview:previewResult,workspace:applied,receipt};
  }catch(error){
    return {published:true,acknowledged:false,snapshotId,preview:previewResult,workspace:applied,acknowledgementError:error?.message||String(error)};
  }
}

async function run(args,print=console.log,context={}){
  const list=[...args],cmd=(list.shift()||"help").toLowerCase();
  const show=value=>{print(typeof value==="string"?value:JSON.stringify(value,null,2));return value;};
  if(cmd==="help")return print(`RiftLLM standalone Dev API bridge\nriftllm-agent status\nriftllm-agent pair\nriftllm-agent unpair\nriftllm-agent sync <project-path>\nriftllm-agent sync-missing <project-path>\nriftllm-agent load <project-path>\nriftllm-agent staged\nriftllm-agent stage <project-path> <text>\nriftllm-agent stage-file <project-path> <riftfs-source-file>\nriftllm-agent delete <project-path>\nriftllm-agent unstage <project-path>\nriftllm-agent reset\nriftllm-agent snapshot [note]\nriftllm-agent snapshots [limit]\nriftllm-agent get-snapshot [id|latest]\nriftllm-agent benchmarks [limit]\nriftllm-agent benchmark [record-id|latest]\nriftllm-agent corpus-synth [base] [output] [manifest] [count-per-category]\nriftllm-agent corpus-build [input] [output-dir] [heldout-permyriad] [seed]\nriftllm-agent corpus-status [output-dir]\nriftllm-agent preview [id|latest]\nriftllm-agent publish [id|latest]\nriftllm-agent ack <id|latest> <workspace-history-id>\nCorpus commands are local-only and confined to /workspace/RiftLLM/tokenizer/private. Pairing token is entered only in the local secure prompt, never as a shell argument.`);
  if(cmd==="status")return show(await status());
  if(cmd==="pair"){if(list.length)throw new Error("usage: riftllm-agent pair (enter the token only in the secure local prompt)");return show(await pair());}
  if(cmd==="unpair"){if(list.length)throw new Error("usage: riftllm-agent unpair");return show(await unpair());}
  if(cmd==="staged")return show(await listStaged());
  if(cmd==="reset")return show(await reset());
  if(cmd==="sync"||cmd==="sync-missing"||cmd==="load"||cmd==="delete"||cmd==="unstage"){
    if(!list[0])throw new Error(`usage: riftllm-agent ${cmd} <project-path>`);
    const fn={sync,"sync-missing":syncMissing,load,delete:stageDelete,unstage}[cmd];return show(await fn(list[0]));
  }
  if(cmd==="stage"){if(list.length<2)throw new Error("usage: riftllm-agent stage <project-path> <text>");const path=list.shift();return show(await stage(path,list.join(" ")));}
  if(cmd==="stage-file"){
    if(list.length<2)throw new Error("usage: riftllm-agent stage-file <project-path> <riftfs-source-file>");
    const path=list.shift(),sourcePath=resolveRiftFs(context.cwd,list.shift()),content=await core.fs.readText(sourcePath);return show(await stage(path,content,`staged from ${sourcePath}`));
  }
  if(cmd==="snapshot")return show(await snapshot(list.join(" ")));
  if(cmd==="snapshots"){if(list[0]!==undefined&&!Number.isFinite(Number(list[0])))throw new Error("snapshot limit must be numeric");return show(await listSnapshots(list[0]));}
  if(cmd==="get-snapshot")return show(await getSnapshot(list[0]||"latest"));
  if(cmd==="benchmarks"){if(list[0]!==undefined&&!Number.isFinite(Number(list[0])))throw new Error("benchmark limit must be numeric");return show(await listBenchmarks(list[0]));}
  if(cmd==="benchmark")return show(await getBenchmark(list[0]||"latest"));
  if(cmd==="corpus-synth"){
    if(list.length>4)throw new Error("usage: riftllm-agent corpus-synth [base] [output] [manifest] [count-per-category]");
    const base=list[0]||CORPUS_DEFAULT_INPUT,output=list[1]||CORPUS_SYNTH_OUTPUT,manifest=list[2]||CORPUS_SYNTH_MANIFEST,count=list[3]===undefined?CORPUS_SYNTH_DEFAULT_COUNT:Number(list[3]);
    return show(await corpusSynth(base,output,manifest,{countPerCategory:count,seed:CORPUS_SYNTH_SEED}));
  }
  if(cmd==="corpus-build"){
    if(list.length>4)throw new Error("usage: riftllm-agent corpus-build [input] [output-dir] [heldout-permyriad] [seed]");
    const input=list[0]||CORPUS_DEFAULT_INPUT,output=list[1]||CORPUS_DEFAULT_OUTPUT,heldout=list[2]===undefined?CORPUS_DEFAULT_HELDOUT:Number(list[2]),seed=list[3]||CORPUS_DEFAULT_SEED;
    return show(await corpusBuild(input,output,{heldoutPermyriad:heldout,seed}));
  }
  if(cmd==="corpus-status"){if(list.length>1)throw new Error("usage: riftllm-agent corpus-status [output-dir]");return show(await corpusStatus(list[0]||CORPUS_DEFAULT_OUTPUT));}
  if(cmd==="preview")return show(await preview(list[0]||"latest"));
  if(cmd==="publish")return show(await publish(list[0]||"latest"));
  if(cmd==="ack"){if(list.length<2)throw new Error("usage: riftllm-agent ack <id|latest> <workspace-history-id>");const {snapshotId}=await concreteSnapshot(list[0]);return show(await acknowledge(snapshotId,list[1],{recovery:true}));}
  throw new Error(`unknown riftllm-agent command: ${cmd}`);
}

globalThis.RiftLlmBridge=Object.freeze({version:1,status,pair,unpair,sync,syncMissing,load,listStaged,stage,stageDelete,unstage,reset,snapshot,listSnapshots,getSnapshot,listBenchmarks,getBenchmark,corpusSynth,corpusBuild,corpusStatus,preview,publish,acknowledge,run});
