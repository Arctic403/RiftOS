import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const read=file=>fs.readFileSync(file,'utf8');
const coreSource=read('src/riftcore.js');
const buildSource=read('src/riftbuild.js');
const repoSource=read('src/riftrepo.js');
const gitSource=read('src/riftgit.js');
const vaultSource=read('src/riftvault.js');
const memorySource=read('src/riftmemory-control.js');
const llmSource=read('src/riftllm-bridge.js');
const devLabSource=read('src/riftdevlab.js');
const shellSource=read('src/riftos.js');
const batchSource=read('src/riftshell-batch.js');

const pureStart=coreSource.indexOf('const RIFT_VOLUMES');
const pureEnd=coreSource.indexOf('class RiftNativeBridge');
assert.ok(pureStart>=0&&pureEnd>pureStart,'RiftCore path helper region missing');
const pathContext={TextEncoder};
pathContext.globalThis=pathContext;
vm.createContext(pathContext);
vm.runInContext(`${coreSource.slice(pureStart,pureEnd)}\nglobalThis.__path={normalize:normalizePath,parent:parentPath,basename,join:joinPath,isAbsolute:isAbsolutePath,canonical:canonicalPath};`,pathContext);
const path=pathContext.__path;
assert.equal(path.isAbsolute('D:/Workspace/ROPE'),true);
assert.equal(path.isAbsolute('C:\\Programs\\demo'),true);
assert.equal(path.normalize('D:/Workspace/ROPE'),'/D:/Workspace/ROPE');
assert.equal(path.canonical('D:/Workspace/ROPE'),'/workspace/ROPE');
assert.equal(path.canonical('/D:/Workspace/ROPE'),'/workspace/ROPE');
assert.equal(path.canonical('C:/Programs/demo'),'/system/programs/demo');
assert.equal(path.canonical('/workspace/ROPE'),'/workspace/ROPE');
assert.match(coreSource,/isAbsolute:isAbsolutePath,canonical:canonicalPath/);
assert.match(coreSource,/"\/system\/programs"/);
assert.match(coreSource,/"\/home\/users"/);

const buildFn=buildSource.match(/function normalizeProject\([^\n]+/u)?.[0];
assert.ok(buildFn,'RiftBuild normalizeProject missing');
const buildContext={core:{path}};buildContext.globalThis=buildContext;vm.createContext(buildContext);vm.runInContext(`${buildFn}\nglobalThis.__normalizeProject=normalizeProject;`,buildContext);
assert.equal(buildContext.__normalizeProject('D:/Workspace/ROPE','/'),'/workspace/ROPE');
assert.equal(buildContext.__normalizeProject('/workspace/ROPE','/'),'/workspace/ROPE');
assert.throws(()=>buildContext.__normalizeProject('D:/Documents/ROPE','/'),/must live under D:\/Workspace/);

const repoMatch=repoSource.match(/function normalizeWorkspacePath\([\s\S]*?\n\}/u)?.[0];
assert.ok(repoMatch,'RiftRepo normalizeWorkspacePath missing');
const repoContext={core:{path}};repoContext.globalThis=repoContext;vm.createContext(repoContext);vm.runInContext(`${repoMatch}\nglobalThis.__normalizeWorkspacePath=normalizeWorkspacePath;`,repoContext);
assert.equal(repoContext.__normalizeWorkspacePath('D:/Workspace/Game','/'),'/workspace/Game');
assert.equal(repoContext.__normalizeWorkspacePath('/workspace/Game','/'),'/workspace/Game');
assert.throws(()=>repoContext.__normalizeWorkspacePath('C:/Programs/Game','/'),/must live under D:\/Workspace/);

assert.match(gitSource,/function canonicalPath\(/);
assert.match(gitSource,/function isAbsolutePath\(/);
assert.match(gitSource,/const normalized=canonicalPath\(root\)/);
assert.match(gitSource,/let cwd=canonicalPath\(context\.cwd\|\|"\/home"\)/);
assert.match(vaultSource,/core\.path\.isAbsolute\(value\)/);
assert.match(memorySource,/core\.path\.isAbsolute\(value\)/);
assert.match(llmSource,/D:\/Workspace\/RiftLLM\//);
assert.match(llmSource,/core\.path\.isAbsolute\(raw\)/);
assert.match(devLabSource,/core\.path\.isAbsolute\(raw\)/);
assert.match(batchSource,/DISABLED: RiftShell batch commands are disabled/);
assert.match(batchSource,/disabled:true/);
assert.match(batchSource,/status:"DISABLED"/);
assert.match(shellSource,/\["\/D:\/Workspace","◇","Workspace"\]/);
assert.match(shellSource,/\["\/C:\/Programs","▦","Programs"\]/);

console.log('ok - RiftCore drive paths canonicalize to stable compatibility identities');
console.log('ok - RiftBuild/RiftRepo accept D:/Workspace while enforcing the same physical sandbox');
console.log('ok - Git, Vault, Memory, RiftLLM, Dev Lab and Files consume the shared drive-path contract; retired batch stays disabled');
