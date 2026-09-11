import { existsSync, readFileSync } from 'node:fs';

const browser = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserWindow.kt', 'utf8');
const engine = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserEngine.kt', 'utf8');
const webViewEngine = readFileSync('android/app/src/main/java/com/riftos/app/AndroidWebViewBrowserEngine.kt', 'utf8');
const desktop = readFileSync('src/riftdesktop-android.js', 'utf8');
const desktopCss = readFileSync('src/riftdesktop-android.css', 'utf8');
const shellCss = readFileSync('styles.css', 'utf8');
const adapter = readFileSync('android/app/src/main/assets/riftbrowser-mcp-app.js', 'utf8');
const bridge = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt', 'utf8');
const main = readFileSync('android/app/src/main/java/com/riftos/app/MainActivity.kt', 'utf8');
const host = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolHost.kt', 'utf8');
const sandbox = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt', 'utf8');
const runtime = readFileSync('android/app/src/main/java/com/riftos/app/RiftMcpRuntime.kt', 'utf8');
const entry = readFileSync('src/riftandroid-entry.js', 'utf8');
const mcpSystem = readFileSync('src/riftmcp-system.js', 'utf8');
const workspaceHost = readFileSync('src/riftworkspace-live-host.js', 'utf8');
const workspacePage = readFileSync('workspace-live/index.html', 'utf8');
const workspaceApp = readFileSync('workspace-live/app.js', 'utf8');
const workspaceState = readFileSync('android/app/src/main/java/com/riftos/app/RiftWorkspaceLiveState.kt', 'utf8');
const workspaceController = readFileSync('android/app/src/main/java/com/riftos/app/RiftWorkspaceLiveController.kt', 'utf8');
const workspaceWatcher = readFileSync('android/app/src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt', 'utf8');
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');

const checks = [
  ['Rift AI workspace app is removed', !existsSync('src/riftai-workspace.js') && !entry.includes('riftai-workspace')],
  ['native Rift AI command surface is removed', !main.includes('"ai.') && !main.includes('RiftAiJournal')],
  ['browser has no Rift AI task orchestration', !browser.includes('startAiTask') && !browser.includes('aiTransportOnly') && !browser.includes('control.queueTask')],
  ['MCP bridge has no Rift AI event channel', !bridge.includes('rift/ai/event')],
  ['MCP launcher identifies ChatGPT Web tooling', mcpSystem.includes('ChatGPT Web tools')],
  ['RiftBrowser owns a renderer interface', engine.includes('interface RiftBrowserEngine') && browser.includes('private val engine: RiftBrowserEngine')],
  ['WebView backend installs exact-origin MCP bridge', webViewEngine.includes('RiftBrowserMcpAppBridge(activity, webView)')],
  ['WebView backend never enables file/content access', webViewEngine.includes('allowFileAccess = false') && webViewEngine.includes('allowContentAccess = false')],
  ['hidden browser surface is actually removed from layout', browser.includes('surfaceHost.visibility = View.GONE') && !browser.includes('parkBehindShell')],
  ['browser renderer is clipped to an owned surface container', browser.includes('surfaceHost.addView') && browser.includes('clipChildren = true') && browser.includes('surfaceHost.bringToFront()')],
  ['desktop emits immediate browser visibility lifecycle', desktop.includes('riftos:window-visibility') && desktop.includes("announceVisibility(win,false,'minimize')")],
  ['desktop grid cannot grow wider than the viewport', shellCss.includes('grid-template-columns:minmax(0,1fr)') && desktopCss.includes('max-width:100vw') && desktopCss.includes('overflow:hidden;display:flex')],
  ['JSON protocol V2 is declared', adapter.includes("const PROTOCOL_V2 = 'rift-tools-v2'")],
  ['V2 results are correlated', adapter.includes('RIFT_TOOL_RESULT_V2') && adapter.includes('request_id')],
  ['outgoing messages are acknowledged', adapter.includes('waitForOutgoingAcceptance')],
  ['legacy protocol remains available', adapter.includes('<rift_call>')],
  ['archive is classified as a write', host.includes('"archive"')],
  ['archive is locally implemented', sandbox.includes('private fun createArchive')],
  ['Workspace Live HTML is sandboxed', workspaceHost.includes('sandbox=\"allow-scripts allow-modals\"') && !workspaceHost.includes('allow-same-origin')],
  ['Workspace Live opaque-origin script can execute', workspacePage.includes('<script defer src="./app.js"></script>') && !workspacePage.includes('type="module"')],
  ['Workspace Live exposes the raw workspace bridge', workspaceHost.includes('rawWorkspaceBridge:true') && workspaceHost.includes('case "remove"') && workspaceHost.includes('case "move"') && workspaceHost.includes('case "applyPatch"')],
  ['Direct MCP and Workspace Live share one native workspace core', runtime.includes('fun workspaceCore(context: Context): RiftToolSandbox') && runtime.includes('RiftToolHost(context.applicationContext, aiJournal(context), workspaceCore(context))') && host.includes('private val sandbox: RiftToolSandbox')],
  ['Workspace Live raw file RPC crosses the shared workspace core', main.includes('\"workspace.core.call\"') && main.includes('RiftMcpRuntime.workspaceCore(this).handleAsync') && workspaceHost.includes('core.native.call("workspace.core.call"') && workspaceHost.includes('workspaceCore:"shared-rift-tool-sandbox-v1"')],
  ['Shared workspace core stays workspace-scoped', main.includes('Unsupported Workspace Core method') && sandbox.includes('Rift MCP is scoped to $WORKSPACE_ROOT/ only') && sandbox.includes('Rift Code Mode is scoped to $WORKSPACE_ROOT/')],
  ['Workspace Live publishes editor view state to native', workspaceApp.includes('kind:"state"') && main.includes('workspace.live.state.set') && workspaceState.includes('object RiftWorkspaceLiveState')],
  ['MCP exposes Workspace Live view state', host.includes('rift_view_state') && sandbox.includes('workspace.viewState')],
  ['MCP exposes real-time Workspace Live page control', host.includes('rift_live_page') && host.includes('RiftWorkspaceLiveController.callAsync') && adapter.includes('Workspace Live page control')],
  ['Workspace Live page control round-trips through native broker', workspaceController.includes('object RiftWorkspaceLiveController') && main.includes('workspace.live.control.result') && workspaceHost.includes('__mcpControl') && workspaceHost.includes('controlResult') && workspaceApp.includes('handlePageControl')],
  ['Workspace Live page control remains local-page scoped', host.includes('controls only the local Workspace Live page') && workspaceHost.includes('activeSurface') && !workspaceHost.includes('allow-same-origin')],
  ['Workspace Live uses workspace-only postMessage RPC', workspaceHost.includes('riftworkspace-live-v1') && workspaceHost.includes('Unsupported live workspace method')],
  ['native workspace watcher is workspace-scoped', workspaceWatcher.includes('riftfs/workspace') && workspaceWatcher.includes('isInsideRoot')],
  ['workspace watcher is exposed only through shell kernel requests', main.includes('\"workspace.watch.start\"') && main.includes('RiftWorkspaceNative?.__event')],
  ['Workspace Live assets are packaged', gradle.includes('include(\"workspace-live/**\")') && workspacePage.includes('Rift Workspace')]
];

const failed = checks.filter(([, ok]) => !ok);
for (const [name, ok] of checks) console.log(`${ok ? 'ok' : 'FAIL'} - ${name}`);
if (failed.length) process.exitCode = 1;
