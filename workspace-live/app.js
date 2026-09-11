const CHANNEL="riftworkspace-live-v1";
let requestSeq=0;
const pending=new Map();
const state={cwd:"",rows:[],selected:"",revision:"",baseline:"",dirty:false,conflict:false,events:[]};

const $=selector=>document.querySelector(selector);
const fileList=$("#fileList"),pathLabel=$("#pathLabel"),filterInput=$("#filterInput"),editor=$("#editor"),fileName=$("#fileName"),revision=$("#revision"),saveBtn=$("#saveBtn"),renameBtn=$("#renameBtn"),deleteBtn=$("#deleteBtn"),followLive=$("#followLive"),activityLog=$("#activityLog"),diffOutput=$("#diffOutput"),diffMeta=$("#diffMeta"),conflict=$("#conflict"),liveDot=$("#liveDot"),liveText=$("#liveText");

function rpc(method,args={}){
  const id=`rw-${Date.now()}-${++requestSeq}`;
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{pending.delete(id);reject(new Error(`Workspace RPC timeout: ${method}`));},30000);
    pending.set(id,{resolve,reject,timer});
    parent.postMessage({channel:CHANNEL,kind:"request",id,method,args},"*");
  });
}
function fmtBytes(value){const n=Number(value||0);if(n<1024)return `${n} B`;if(n<1024**2)return `${(n/1024).toFixed(1)} KB`;return `${(n/1024**2).toFixed(1)} MB`;}
function parentPath(path){const parts=String(path||"").split("/").filter(Boolean);parts.pop();return parts.join("/");}
function leaf(path){return String(path||"").split("/").filter(Boolean).pop()||"workspace";}
function joinPath(base,name){return [String(base||"").replace(/^\/+|\/+$/g,""),String(name||"").replace(/^\/+|\/+$/g,"")].filter(Boolean).join("/");}
function setLive(on,text=on?"Live":"Disconnected"){liveDot.classList.toggle("on",on);liveText.textContent=text;liveText.classList.toggle("status-error",!on&&text!=="Connecting");}
function setConflict(message=""){state.conflict=!!message;conflict.classList.toggle("hidden",!message);conflict.textContent=message;}
function setRevision(hash="",extra=""){state.revision=hash||"";revision.textContent=hash?`${hash.slice(0,12)}${extra?` · ${extra}`:""}`:(extra||"No revision");}
function escapeHTML(value){return String(value??"").replace(/[&<>"']/g,ch=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[ch]));}
function escapeAttr(value){return escapeHTML(value);}

let statePublishTimer=0;
function editorViewState(){
  const text=String(editor.value||"");
  const start=Math.max(0,editor.selectionStart||0),end=Math.max(start,editor.selectionEnd||start);
  const before=text.slice(0,start),selected=text.slice(start,end);
  const cursorLine=before.split("\n").length;
  const lineHeight=17;
  const firstVisibleLine=Math.max(1,Math.floor((editor.scrollTop||0)/lineHeight)+1);
  const visibleLineCount=Math.max(1,Math.ceil((editor.clientHeight||340)/lineHeight)+2);
  const lastVisibleLine=firstVisibleLine+visibleLineCount-1;
  const lines=text.split("\n");
  const excerptStart=Math.max(0,firstVisibleLine-3);
  const excerptEnd=Math.min(lines.length,lastVisibleLine+2);
  return {
    activeFile:state.selected||null,
    cwd:state.cwd,
    revision:state.revision||null,
    dirty:state.dirty,
    conflict:state.conflict,
    cursor:{offset:start,line:cursorLine},
    selection:{start,end,text:selected.slice(0,12000)},
    visible:{startLine:firstVisibleLine,endLine:lastVisibleLine,excerpt:lines.slice(excerptStart,excerptEnd).join("\n").slice(0,24000)},
    at:Date.now()
  };
}
function publishState(){
  clearTimeout(statePublishTimer);
  statePublishTimer=setTimeout(()=>parent.postMessage({channel:CHANNEL,kind:"state",state:editorViewState()},"*"),70);
}

async function loadDirectory(path=state.cwd){
  state.cwd=String(path||"").replace(/^\/+|\/+$/g,"");
  pathLabel.textContent=`/${state.cwd}`.replace(/\/$/,"")||"/";
  const rows=await rpc("list",{path:state.cwd});
  state.rows=Array.isArray(rows)?rows:[];
  renderFiles();publishState();
}
function renderFiles(){
  const needle=filterInput.value.trim().toLowerCase();
  const rows=state.rows.filter(row=>!needle||String(row.name||leaf(row.path)).toLowerCase().includes(needle));
  if(!rows.length){fileList.innerHTML='<div class="empty">This folder is empty.</div>';return;}
  fileList.innerHTML=rows.map(row=>{
    const name=row.name||leaf(row.path),dir=row.kind==="directory";
    return `<button class="file-row ${row.path===state.selected?"selected":""}" data-path="${escapeAttr(row.path)}" data-kind="${escapeAttr(row.kind)}"><span>${dir?"▸":"·"}</span><b>${escapeHTML(name)}</b><small>${dir?"DIR":fmtBytes(row.size)}</small></button>`;
  }).join("");
}

async function openFile(path,{external=false}={}){
  const result=await rpc("read",{path});
  const previous=state.baseline;
  state.selected=path;
  state.baseline=String(result.text??"");
  editor.value=state.baseline;
  editor.disabled=false;
  state.dirty=false;
  saveBtn.disabled=true;renameBtn.disabled=false;deleteBtn.disabled=false;
  fileName.textContent=path;
  setRevision(result.sha256,`${fmtBytes(result.size)} · ${new Date(result.modified||Date.now()).toLocaleTimeString()}`);
  setConflict("");
  renderFiles();publishState();
  if(external&&previous!==state.baseline)renderDiff(previous,state.baseline,path);
}

function clearOpenFile(){
  state.selected="";state.baseline="";state.revision="";state.dirty=false;state.conflict=false;
  editor.value="";editor.disabled=true;fileName.textContent="Select a file";setRevision("","No file open");setConflict("");
  saveBtn.disabled=true;renameBtn.disabled=true;deleteBtn.disabled=true;renderFiles();publishState();
}

function renderDiff(before,after,path){
  const a=String(before??"").split("\n"),b=String(after??"").split("\n");
  let prefix=0;while(prefix<a.length&&prefix<b.length&&a[prefix]===b[prefix])prefix++;
  let suffix=0;while(suffix<a.length-prefix&&suffix<b.length-prefix&&a[a.length-1-suffix]===b[b.length-1-suffix])suffix++;
  const removed=a.slice(prefix,a.length-suffix),added=b.slice(prefix,b.length-suffix),start=prefix+1;
  const lines=[];
  const contextStart=Math.max(0,prefix-2);for(let i=contextStart;i<prefix;i++)lines.push(`  ${i+1} ${a[i]}`);
  removed.slice(0,20).forEach((line,i)=>lines.push(`- ${start+i} ${line}`));
  added.slice(0,20).forEach((line,i)=>lines.push(`+ ${start+i} ${line}`));
  const afterStart=b.length-suffix;for(let i=afterStart;i<Math.min(b.length,afterStart+2);i++)lines.push(`  ${i+1} ${b[i]}`);
  if(removed.length>20||added.length>20)lines.push(`… ${Math.max(0,removed.length-20)} more removed / ${Math.max(0,added.length-20)} more added lines`);
  diffMeta.textContent=`${path} · line ${start}`;
  diffOutput.textContent=lines.join("\n")||"Metadata changed; text content is unchanged.";
}

async function save(){
  if(!state.selected||!state.dirty)return;
  saveBtn.disabled=true;
  try{
    const result=await rpc("write",{path:state.selected,text:editor.value,expectedSha256:state.revision||null});
    const before=state.baseline;state.baseline=editor.value;state.dirty=false;setRevision(result.sha256,`${fmtBytes(result.size)} · saved`);setConflict("");renderDiff(before,state.baseline,state.selected);publishState();
  }catch(error){
    setConflict(`Save blocked: ${error.message}. Reload or copy your changes before retrying.`);saveBtn.disabled=false;publishState();
  }
}

async function createFile(){
  const name=prompt("New file name");if(!name)return;
  const path=joinPath(state.cwd,name);
  await rpc("write",{path,text:""});
  await loadDirectory(state.cwd);await openFile(path);
}
async function createFolder(){
  const name=prompt("New folder name");if(!name)return;
  await rpc("mkdir",{path:joinPath(state.cwd,name)});await loadDirectory(state.cwd);
}
async function renameSelected(){
  if(!state.selected)return;
  const name=prompt("Rename to",leaf(state.selected));if(!name||name===leaf(state.selected))return;
  const next=joinPath(parentPath(state.selected),name);
  await rpc("move",{path:state.selected,newPath:next});
  state.selected=next;await loadDirectory(parentPath(next));await openFile(next);
}
async function deleteSelected(){
  if(!state.selected)return;
  if(!confirm(`Delete ${state.selected}?`))return;
  const old=state.selected;await rpc("remove",{path:old});clearOpenFile();await loadDirectory(parentPath(old));
}

function addActivity(event){
  state.events.unshift(event);state.events=state.events.slice(0,120);
  activityLog.innerHTML=state.events.map(item=>`<div class="activity-row"><time>${new Date(item.at||Date.now()).toLocaleTimeString([], {hour:"2-digit",minute:"2-digit",second:"2-digit"})}</time><em>${escapeHTML(item.type||"change")}</em><span>${escapeHTML(item.path||"/")}</span></div>`).join("")||'<div class="empty">No events yet.</div>';
}

let refreshTimer=0,selectedReloadTimer=0;
async function handleNativeEvent(event){
  addActivity(event);
  clearTimeout(refreshTimer);
  refreshTimer=setTimeout(()=>loadDirectory(state.cwd).catch(()=>{}),120);
  if(!state.selected||event.directory||event.path!==state.selected)return;
  if(state.dirty){setConflict(`External change detected in ${state.selected} while you have unsaved edits.`);publishState();return;}
  if(!followLive.checked)return;
  clearTimeout(selectedReloadTimer);
  selectedReloadTimer=setTimeout(()=>openFile(state.selected,{external:true}).catch(error=>setConflict(error.message)),180);
}


const controlRefs=new Map();
let controlRefSeq=0;
function isVisibleElement(element){
  if(!(element instanceof Element))return false;
  const style=getComputedStyle(element),rect=element.getBoundingClientRect();
  return style.display!=="none"&&style.visibility!=="hidden"&&Number(style.opacity)!==0&&rect.width>0&&rect.height>0;
}
function controlRef(element){
  for(const [ref,node] of controlRefs){if(node===element)return ref;}
  const ref=`e${++controlRefSeq}`;controlRefs.set(ref,element);return ref;
}
function elementLabel(element){
  return String(element.getAttribute("aria-label")||element.getAttribute("title")||element.getAttribute("placeholder")||element.innerText||element.textContent||"").replace(/\s+/g," ").trim().slice(0,320);
}
function describeElement(element){
  if(!(element instanceof Element))return null;
  const rect=element.getBoundingClientRect();
  const out={
    ref:controlRef(element),tag:element.tagName.toLowerCase(),id:element.id||null,
    role:element.getAttribute("role")||null,label:elementLabel(element),
    disabled:"disabled" in element?!!element.disabled:undefined,
    visible:isVisibleElement(element),
    rect:{x:Math.round(rect.x),y:Math.round(rect.y),width:Math.round(rect.width),height:Math.round(rect.height)}
  };
  if(element instanceof HTMLInputElement||element instanceof HTMLTextAreaElement||element instanceof HTMLSelectElement){out.value=String(element.value??"").slice(0,4000);}
  if(element instanceof HTMLInputElement&&(element.type==="checkbox"||element.type==="radio"))out.checked=element.checked;
  const path=element.getAttribute("data-path");if(path)out.path=path;
  const kind=element.getAttribute("data-kind");if(kind)out.kind=kind;
  return out;
}
function targetElement(args={}){
  if(args.ref){const node=controlRefs.get(String(args.ref));if(node?.isConnected)return node;throw new Error(`Unknown or stale page ref: ${args.ref}`);}
  if(args.selector){const node=document.querySelector(String(args.selector));if(node)return node;throw new Error(`No element matches selector: ${args.selector}`);}
  const active=document.activeElement;if(active&&active!==document.body)return active;
  throw new Error("Page action requires ref or selector");
}
function serializable(value,depth=0,seen=new WeakSet()){
  if(value==null||typeof value==="string"||typeof value==="number"||typeof value==="boolean")return value??null;
  if(typeof value==="bigint")return value.toString();
  if(typeof value==="function")return `[Function ${value.name||"anonymous"}]`;
  if(value instanceof Element)return describeElement(value);
  if(value instanceof Event)return {type:value.type};
  if(depth>=5)return String(value).slice(0,2000);
  if(typeof value==="object"){
    if(seen.has(value))return "[Circular]";seen.add(value);
    if(Array.isArray(value))return value.slice(0,200).map(item=>serializable(item,depth+1,seen));
    const out={};for(const key of Object.keys(value).slice(0,200)){try{out[key]=serializable(value[key],depth+1,seen);}catch{}}
    return out;
  }
  return String(value);
}
function pageSnapshot(limit=180){
  controlRefs.clear();controlRefSeq=0;
  const selector='button,input,textarea,select,a,[role="button"],[role="textbox"],[tabindex],[data-path]';
  const elements=[...document.querySelectorAll(selector)].filter(isVisibleElement).slice(0,Math.max(1,Math.min(Number(limit)||180,300))).map(describeElement);
  return {
    surface:"workspace-live",title:document.title,connected:liveText.textContent==="Live",
    viewport:{width:innerWidth,height:innerHeight,scrollX,scrollY},
    editor:editorViewState(),elements
  };
}
function dispatchValueEvents(element){
  element.dispatchEvent(new Event("input",{bubbles:true}));
  element.dispatchEvent(new Event("change",{bubbles:true}));
}
async function handlePageControl(request={}){
  const op=String(request.op||"").trim();
  if(!op)throw new Error("Missing Workspace Live page operation");
  if(op==="snapshot")return pageSnapshot(request.limit);
  if(op==="html")return {html:document.documentElement.outerHTML.slice(0,Math.max(1,Math.min(Number(request.maxChars)||120000,240000)))};
  if(op==="query"){
    const selector=String(request.selector||"").trim();if(!selector)throw new Error("query requires selector");
    const limit=Math.max(1,Math.min(Number(request.limit)||80,200));
    return {selector,elements:[...document.querySelectorAll(selector)].slice(0,limit).map(describeElement)};
  }
  if(op==="eval"){
    const script=String(request.script||"");if(!script)throw new Error("eval requires script");
    const value=await eval(script);
    return {value:serializable(value)};
  }
  if(op==="scroll"&&!request.ref&&!request.selector){
    const x=Number(request.x)||0,y=Number(request.y)||0;window.scrollBy({left:x,top:y,behavior:"auto"});publishState();return {viewport:{scrollX,scrollY}};
  }
  const element=targetElement(request);
  if(op==="click"){
    element.scrollIntoView({block:"nearest",inline:"nearest"});element.click();await new Promise(resolve=>setTimeout(resolve,0));return {element:describeElement(element),state:editorViewState()};
  }
  if(op==="focus"){element.focus();return {element:describeElement(element)};}
  if(op==="type"){
    const text=String(request.text??"");element.focus();
    if(element instanceof HTMLInputElement||element instanceof HTMLTextAreaElement){
      const start=element.selectionStart??element.value.length,end=element.selectionEnd??start;element.setRangeText(text,start,end,"end");dispatchValueEvents(element);
    }else if(element.isContentEditable){document.execCommand("insertText",false,text);element.dispatchEvent(new Event("input",{bubbles:true}));}
    else throw new Error("Target does not accept typed text");
    publishState();return {element:describeElement(element),state:editorViewState()};
  }
  if(op==="setValue"){
    const text=String(request.text??"");element.focus();
    if(element instanceof HTMLInputElement||element instanceof HTMLTextAreaElement||element instanceof HTMLSelectElement){element.value=text;dispatchValueEvents(element);}
    else if(element.isContentEditable){element.textContent=text;element.dispatchEvent(new Event("input",{bubbles:true}));}
    else throw new Error("Target does not have an editable value");
    publishState();return {element:describeElement(element),state:editorViewState()};
  }
  if(op==="select"){
    const start=Math.max(0,Number(request.start)||0),end=Math.max(start,Number(request.end??start)||start);element.focus();
    if(element instanceof HTMLInputElement||element instanceof HTMLTextAreaElement){element.setSelectionRange(start,Math.min(end,element.value.length));}
    else{const range=document.createRange();range.selectNodeContents(element);const selection=getSelection();selection.removeAllRanges();selection.addRange(range);}
    publishState();return {element:describeElement(element),state:editorViewState()};
  }
  if(op==="scroll"){
    const x=Number(request.x)||0,y=Number(request.y)||0;
    if(request.ref||request.selector){if(x||y)element.scrollBy({left:x,top:y,behavior:"auto"});else element.scrollIntoView({block:"center",inline:"nearest"});}
    else window.scrollBy({left:x,top:y,behavior:"auto"});
    publishState();return {viewport:{scrollX,scrollY},element:describeElement(element)};
  }
  if(op==="key"){
    const init={key:String(request.key||""),code:String(request.code||""),ctrlKey:!!request.ctrl,altKey:!!request.alt,shiftKey:!!request.shift,metaKey:!!request.meta,bubbles:true,cancelable:true};
    element.focus();element.dispatchEvent(new KeyboardEvent("keydown",init));element.dispatchEvent(new KeyboardEvent("keyup",init));await new Promise(resolve=>setTimeout(resolve,0));publishState();return {element:describeElement(element),state:editorViewState()};
  }
  throw new Error(`Unsupported Workspace Live page operation: ${op}`);
}
async function respondToPageControl(request){
  const id=String(request?.id||"");if(!id)return;
  try{parent.postMessage({channel:CHANNEL,kind:"controlResult",response:{id,ok:true,value:await handlePageControl(request)}} ,"*");}
  catch(error){parent.postMessage({channel:CHANNEL,kind:"controlResult",response:{id,ok:false,error:error?.message||String(error)}} ,"*");}
}

globalThis.RiftWorkspaceLivePage=Object.freeze({
  snapshot:pageSnapshot,query:selector=>[...document.querySelectorAll(selector)].map(describeElement),
  rpc,openFile,loadDirectory,save,get state(){return serializable(state);},get view(){return editorViewState();}
});

window.addEventListener("message",event=>{
  const message=event.data;if(!message||message.channel!==CHANNEL)return;
  if(message.kind==="control"&&message.request){respondToPageControl(message.request);return;}
  if(message.kind==="response"){
    const waiter=pending.get(message.id);if(!waiter)return;clearTimeout(waiter.timer);pending.delete(message.id);message.ok?waiter.resolve(message.value):waiter.reject(new Error(message.error||"Workspace RPC failed"));return;
  }
  if(message.kind==="event"&&message.event)handleNativeEvent(message.event);
  if(message.kind==="connected")setLive(true,"Live");
});

fileList.addEventListener("click",event=>{const row=event.target.closest("[data-path]");if(!row)return;row.dataset.kind==="directory"?loadDirectory(row.dataset.path).catch(showError):openFile(row.dataset.path).catch(showError);});
filterInput.addEventListener("input",renderFiles);
$("#upBtn").onclick=()=>loadDirectory(parentPath(state.cwd)).catch(showError);
$("#refreshBtn").onclick=()=>loadDirectory(state.cwd).catch(showError);
$("#newFileBtn").onclick=()=>createFile().catch(showError);
$("#newFolderBtn").onclick=()=>createFolder().catch(showError);
saveBtn.onclick=()=>save();
renameBtn.onclick=()=>renameSelected().catch(showError);
deleteBtn.onclick=()=>deleteSelected().catch(showError);
$("#clearActivity").onclick=()=>{state.events=[];activityLog.innerHTML='<div class="empty">Activity cleared.</div>';};
editor.addEventListener("input",()=>{state.dirty=editor.value!==state.baseline;saveBtn.disabled=!state.dirty;if(state.dirty)setRevision(state.revision,"unsaved edits");publishState();});
editor.addEventListener("scroll",publishState,{passive:true});
editor.addEventListener("select",publishState);
editor.addEventListener("keyup",publishState);
editor.addEventListener("pointerup",publishState);
editor.addEventListener("keydown",event=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==="s"){event.preventDefault();save();}});
function showError(error){setLive(false,error.message||String(error));}

parent.postMessage({channel:CHANNEL,kind:"ready"},"*");
rpc("info").then(info=>{setLive(!!info?.watch?.active,"Live");return loadDirectory("");}).catch(showError);
publishState();
