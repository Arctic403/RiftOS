import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const packages = new Map();
const fs = {
  async mkdir() {},
  async setting() { return { value: true }; },
  async writeJSON(path, value) { packages.set(path, value); },
  async readJSON(path, fallback) { return packages.get(path) ?? fallback; },
  async list() { return [...packages.keys()].map(path => ({ path, kind: 'file' })); }
};
const registered = [];
const context = {
  window: {
    RiftOSCore: {
      ready: Promise.resolve(), fs,
      kernel: { registerApp: app => registered.push(app) }
    },
    addEventListener() {}
  },
  document: { addEventListener() {}, querySelector() { return null; } },
  Blob, console
};
vm.createContext(context);
vm.runInContext(readFileSync('src/riftapps.js', 'utf8'), context, { filename: 'src/riftapps.js' });

const fixture = process.argv[2]
  ? readFileSync(process.argv[2], 'utf8')
  : JSON.stringify({
      format: 'rift-app-v1',
      manifest: { id: 'demo.import.test', name: 'Import Test', version: '1.0.0', entry: 'index.html', permissions: ['storage'] },
      files: { 'index.html': '<h1>Imported</h1>' }
    });
const file = { name: 'ChatTestIDE-1.rift', type: 'application/octet-stream', size: Buffer.byteLength(fixture), text: async () => fixture };
const app = await context.window.RiftApps.installPackageFile(file);
assert.equal(app.format, 'rift-app-v1');
assert.equal(app.id, JSON.parse(fixture).manifest.id);
assert.equal((await context.window.RiftApps.get(app.id))?.manifest.name, app.manifest.name);
assert(registered.some(item => item.id === app.id));

await assert.rejects(
  () => context.window.RiftApps.installPackageFile({ ...file, text: async () => 'not a package' }),
  /JSON containers/
);
await assert.rejects(
  () => context.window.RiftApps.installPackageFile({ ...file, text: async () => '{"format":"other"}' }),
  /Unsupported package format/
);

for (const [source, id] of [['src/riftapps.js', 'riftPackageInput'], ['src/riftrt.js', 'riftrtImport']]) {
  assert.match(readFileSync(source, 'utf8'), new RegExp(`id="${id}" accept="\\.rift,\\*/\\*"`));
}
console.log('ok - Android generic-MIME .rift imports and registers an app; both pickers allow it');
console.log('ok - invalid content is rejected after selection');
