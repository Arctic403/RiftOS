import { existsSync, readFileSync } from 'node:fs';

const browser = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserWindow.kt', 'utf8');
const adapter = readFileSync('android/app/src/main/assets/riftbrowser-mcp-app.js', 'utf8');
const bridge = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt', 'utf8');
const main = readFileSync('android/app/src/main/java/com/riftos/app/MainActivity.kt', 'utf8');
const host = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolHost.kt', 'utf8');
const sandbox = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt', 'utf8');
const entry = readFileSync('src/riftandroid-entry.js', 'utf8');
const mcpSystem = readFileSync('src/riftmcp-system.js', 'utf8');
const workspaceHost = readFileSync('src/riftworkspace-live-host.js', 'utf8');
const workspacePage = readFileSync('workspace-live/index.html', 'utf8');
const workspaceWatcher = readFileSync('android/app/src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt', 'utf8');
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');

const checks = [
  ['Rift AI workspace app is removed', !existsSync('src/riftai-workspace.js') && !entry.includes('riftai-workspace')],
  ['native Rift AI command surface is removed', !main.includes('"ai.') && !main.includes('RiftAiJournal')],
  ['browser has no Rift AI task orchestration', !browser.includes('startAiTask') && !browser.includes('aiTransportOnly') && !browser.includes('control.queueTask')],
  ['MCP bridge has no Rift AI event channel', !bridge.includes('rift/ai/event')],
  ['MCP launcher identifies ChatGPT Web tooling', mcpSystem.includes('ChatGPT Web tools')],
  ['browser installs exact-origin MCP bridge', browser.includes('RiftBrowserMcpAppBridge(activity, webView)')],
  ['browser never enables file/content access', browser.includes('allowFileAccess = false') && browser.includes('allowContentAccess = false')],
  ['JSON protocol V2 is declared', adapter.includes("const PROTOCOL_V2 = 'rift-tools-v2'")],
  ['V2 results are correlated', adapter.includes('RIFT_TOOL_RESULT_V2') && adapter.includes('request_id')],
  ['outgoing messages are acknowledged', adapter.includes('waitForOutgoingAcceptance')],
  ['legacy protocol remains available', adapter.includes('<rift_call>')],
  ['archive is classified as a write', host.includes('"archive"')],
  ['archive is locally implemented', sandbox.includes('private fun createArchive')],
  ['Workspace Live HTML is sandboxed', workspaceHost.includes('sandbox=\"allow-scripts\"') && !workspaceHost.includes('allow-same-origin')],
  ['Workspace Live uses narrow postMessage RPC', workspaceHost.includes('riftworkspace-live-v1') && workspaceHost.includes('Unsupported live workspace method')],
  ['native workspace watcher is workspace-scoped', workspaceWatcher.includes('riftfs/workspace') && workspaceWatcher.includes('isInsideRoot')],
  ['workspace watcher is exposed only through shell kernel requests', main.includes('\"workspace.watch.start\"') && main.includes('RiftWorkspaceNative?.__event')],
  ['Workspace Live assets are packaged', gradle.includes('include(\"workspace-live/**\")') && workspacePage.includes('Rift Workspace')]
];

const failed = checks.filter(([, ok]) => !ok);
for (const [name, ok] of checks) console.log(`${ok ? 'ok' : 'FAIL'} - ${name}`);
if (failed.length) process.exitCode = 1;
