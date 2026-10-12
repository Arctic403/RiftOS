import assert from 'node:assert/strict';
import fs from 'node:fs';

const root = new URL('../', import.meta.url);
const read = p => fs.readFileSync(new URL(p, root), 'utf8');
const shell = read('external-components/IndependentGraphicalShellV2.kt');
const v1 = read('external-components/IndependentGraphicalShellV1.kt');
const recipe = JSON.parse(read('external-components/compile-shell-v2.json'));
const producer = read('external-components/runtime.js');
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const abi = read('android/app/src/main/java/com/riftos/app/RiftShellComponentAbiV1.kt');

assert.match(v1, /class IndependentGraphicalShellV1 : RiftShellGraphicalComponentV1/);
assert.match(shell, /^package com\.riftos\.external\.shell/m);
assert.match(shell, /class IndependentGraphicalShellV2 : RiftShellGraphicalComponentV1/);
assert.match(abi, /interface RiftShellPlatformServicesV1/);
assert.match(shell, /override fun attach\(/);
for (const marker of [
  '"files" to "Files (Core)"',
  '"terminal" to "RiftShell Terminal"',
  '"tasks" to "Tasks (Core)"',
  '"installed-apps" to "Installed Apps"',
  '"workspace-live" to "Workspace Records"',
  '"settings" to "RiftOS Settings"',
  '"mcp" to "Rift MCP Settings"',
  '"admin-permissions" to "Permissions (read-only)"',
  'services().installed()', 'services().open(id)',
  'services().snapshot(win.id)', 'services().event(win.id, win.generation,',
  'services().execute(command, "/")',
  'if (win.generation > 0L)',
  'if (win.minimized || win.generation <= 0L) continue',
  'openWindow("system:" + id, title, 0L)',
  'RiftMcpActivity',
  'MCP settings (Android-hosted interim)',
  'Install/uninstall controls require a separate permissions/UI parity gate',
  'One-use admin consent, protected component activation',
]) assert.ok(shell.includes(marker), 'Missing independent V2 implementation/limit: ' + marker);

for (const forbidden of [
  'import com.riftos.app.RiftNativeDesktop',
  'import com.riftos.app.RiftNativeSystemApps',
  'import com.riftos.app.RiftNativeWorkspaceApps',
  'import com.riftos.app.RiftCoreRuntime',
  'RiftManualRecoveryStore.restore(',
  'RiftProtectedComponentInstaller.activate(',
  'saveSelectedBackup(',
]) assert.ok(!shell.includes(forbidden), 'External V2 must not load embedded owner or change manual Recovery: ' + forbidden);

assert.equal(recipe.sources.includes('IndependentGraphicalShellV2.kt'), true);
assert.equal(recipe.sources.includes('IndependentGraphicalShellV1.kt'), false);
assert.match(producer, /shellV2:105/);
assert.match(producer, /BTN\.shellV2/);
assert.match(producer, /s\.mode='shell-v2'/);
assert.match(producer, /IndependentGraphicalShellV2\.kt/);
assert.match(producer, /IndependentGraphicalShellV2'/);
assert.match(producer, /k==='shell-v2'\?'v2\/'/);
assert.match(producer, /k==='shell-v2'\?'0\.2\.0'/);
assert.match(producer, /version:c\.version/);
assert.match(producer, /No critical component staging\/activation authority/);
assert.equal(ownership.split('| `external-components/IndependentGraphicalShellV2.kt` |').length - 1, 1);
console.log('External Shell V2 source contracts PASS: isolated version, Core-backed native system tools, MCP host interim, RAPP ownership, manual Recovery untouched. Kotlin compile and device behavior NOT YET PROVEN.');
