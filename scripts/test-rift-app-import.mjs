import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const files = new Map();
const dirs = new Set(['/','/C:/Programs','/C:/ProgramData','/C:/ProgramData/Installer','/C:/ProgramData/Installer/staging','/C:/ProgramData/Installer/rollback','/D:/Users/Default/AppData']);
const normalize = value => ('/' + String(value || '').replace(/\\/g,'/').split('/').filter(Boolean).join('/'));
const fs = {
  async mkdir(path) { dirs.add(normalize(path)); return { path: normalize(path), kind: 'directory' }; },
  async setting() { return { value: true }; },
  async setSetting() { return true; },
  async writeJSON(path, value) { path=normalize(path); files.set(path, structuredClone(value)); dirs.add(path.slice(0,path.lastIndexOf('/'))||'/'); },
  async readJSON(path, fallback) { return files.has(normalize(path)) ? structuredClone(files.get(normalize(path))) : fallback; },
  async stat(path) { path=normalize(path); if(files.has(path))return {path,kind:'file'}; if(dirs.has(path)||[...files].some(([key])=>key.startsWith(path+'/')))return {path,kind:'directory'}; return null; },
  async list(path) { path=normalize(path); return [...files.keys()].filter(key=>key.startsWith(path+'/')).map(key=>({path:key,kind:'file'})); },
  async move(from,to) { from=normalize(from);to=normalize(to); const matching=[...files.entries()].filter(([key])=>key===from||key.startsWith(from+'/')); if(!matching.length&& !dirs.has(from))throw new Error(`missing move source ${from}`); for(const [key,value] of matching){files.delete(key);files.set(to+key.slice(from.length),value);} for(const dir of [...dirs].filter(dir=>dir===from||dir.startsWith(from+'/'))){dirs.delete(dir);dirs.add(to+dir.slice(from.length));} return {path:to,kind:'directory'}; },
  async remove(path) { path=normalize(path); for(const key of [...files.keys()])if(key===path||key.startsWith(path+'/'))files.delete(key); for(const dir of [...dirs])if(dir===path||dir.startsWith(path+'/'))dirs.delete(dir); return true; }
};
const registered = [];
const context = {
  window: {
    RiftOSCore: {
      ready: Promise.resolve(), fs,
      kernel: { registerApp: app => registered.push(app), unregisterApp() {} }
    },
    addEventListener() {}
  },
  document: { addEventListener() {}, querySelector() { return null; } },
  Blob, structuredClone, crypto: globalThis.crypto, console
};
context.globalThis=context.window;
vm.createContext(context);
const gradleSource=readFileSync('android/app/build.gradle.kts','utf8');
const appHostSource=readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt','utf8');
assert.ok(!gradleSource.includes('riftapps.js'),'retained RiftApps installer must not be packaged');
assert.ok(!gradleSource.includes('riftrt.js'),'retained RiftRT manager must not be packaged');
assert.ok(appHostSource.includes('Android-owned execution surface for installed RiftOS programs.'),'current app host boundary missing');
const appsSource=readFileSync('src/riftapps.js','utf8');
vm.runInContext(appsSource, context, { filename: 'src/riftapps.js' });

const fixture = process.argv[2]
  ? readFileSync(process.argv[2], 'utf8')
  : JSON.stringify({
      format: 'rift-app-v1',
      manifest: { id: 'demo.import.test', name: 'Import Test', version: '1.0.0', entry: 'index.html', permissions: ['storage','fs.read'] },
      files: { 'index.html': '<h1>Installed</h1>', 'riftrt.json': '{"engine":"native-webview","entry":"index.html"}' }
    });
const file = { name: 'Program.rift', type: 'application/octet-stream', size: Buffer.byteLength(fixture), text: async () => fixture };
const app = await context.window.RiftApps.installPackageFile(file);
assert.equal(app.format, 'rift-app-v1');
assert.equal(app.id, JSON.parse(fixture).manifest.id);
assert.equal((await context.window.RiftApps.get(app.id))?.manifest.name, app.manifest.name);
assert(files.has(`/C:/Programs/${app.id}/package.json`));
assert(files.has(`/C:/Programs/${app.id}/install.json`));
assert.equal(context.window.RiftApps.paths.programRoot, '/C:/Programs');
assert.equal(context.window.RiftApps.paths.userAppDataRoot, '/D:/Users/Default/AppData');
assert(registered.some(item => item.id === app.id));
assert(!appsSource.includes('<iframe'));
assert.match(readFileSync('src/riftrt.js','utf8'), /engine:'native-webview'/);
assert.match(readFileSync('src/riftrt.js','utf8'), /app\.runtime\.open/);
assert.match(readFileSync('src/riftrt.js','utf8'), /'rift-vm'/);

const vmFixture=readFileSync('examples/riftpp/hello-rift-executable.rift','utf8');
const vmFile={name:'RiftExecutable.rift',type:'application/octet-stream',size:Buffer.byteLength(vmFixture),text:async()=>vmFixture};
const vmApp=await context.window.RiftApps.installPackageFile(vmFile);
assert.equal(vmApp.manifest.entry,'main.rxe');
assert(files.has(`/C:/Programs/${vmApp.id}/package.json`));
assert.equal(JSON.parse(vmApp.files['riftrt.json']).engine,'rift-vm');

await assert.rejects(() => context.window.RiftApps.installPackageFile({ ...file, text: async () => 'not a package' }), /JSON containers/);
await assert.rejects(() => context.window.RiftApps.installPackageFile({ ...file, text: async () => '{"format":"other"}' }), /Unsupported package format/);
for (const [source, id] of [['src/riftapps.js', 'riftPackageInput'], ['src/riftrt.js', 'riftrtImport']]) assert.match(readFileSync(source, 'utf8'), new RegExp(`id="${id}" accept="\\.rift,\\*/\\*"`));
console.log('ok - retained RiftApps package-format/transaction reference remains internally consistent');
console.log('ok - retained RiftApps/RiftRT JavaScript stays un-packaged while current installed-app execution is Android-owned');
console.log('ok - retained package-format reference accepts the historical RiftVM main.rxe payload shape');
