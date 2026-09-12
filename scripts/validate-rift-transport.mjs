import { existsSync, readFileSync } from 'node:fs';

const browser = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserWindow.kt', 'utf8');
const engine = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserEngine.kt', 'utf8');
const webViewEngine = readFileSync('android/app/src/main/java/com/riftos/app/AndroidWebViewBrowserEngine.kt', 'utf8');
const desktop = readFileSync('src/riftdesktop-android.js', 'utf8');
const adapter = readFileSync('android/app/src/main/assets/riftbrowser-mcp-app.js', 'utf8');
const bridge = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt', 'utf8');
const main = readFileSync('android/app/src/main/java/com/riftos/app/MainActivity.kt', 'utf8');
const dispatcher = readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt', 'utf8');
const core = readFileSync('src/riftcore.js', 'utf8');
const workspaceAdapter = readFileSync('src/riftworkspace-android-adapter.js', 'utf8');
const filesUi = readFileSync('src/riftos.js', 'utf8');
const host = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolHost.kt', 'utf8');
const journal = readFileSync('android/app/src/main/java/com/riftos/app/RiftAiJournal.kt', 'utf8');
const sandbox = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt', 'utf8');
const entry = readFileSync('src/riftandroid-entry.js', 'utf8');
const mcpSystem = readFileSync('src/riftmcp-system.js', 'utf8');
const workspaceHost = readFileSync('src/riftworkspace-live-host.js', 'utf8');
const workspacePage = readFileSync('workspace-live/index.html', 'utf8');
const workspaceWatcher = readFileSync('android/app/src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt', 'utf8');
const gradle = readFileSync('android/app/build.gradle.kts', 'utf8');
const shellStyles = readFileSync('styles.css', 'utf8');
const mcpServer = readFileSync('android/app/src/main/java/com/riftos/app/RiftMcpServer.kt', 'utf8');
const relayWorker = readFileSync('relay/src/index.js', 'utf8');

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
  ['desktop window controls stay enabled on Android', desktop.includes('const desktopEnabled=()=>true')],
  ['desktop geometry fits narrow viewports', desktop.includes('const minWidth=Math.min(MIN_W,maxWidth)') && desktop.includes('window.visualViewport')],
  ['AndroidX Core dependency backs inset compatibility APIs', gradle.includes('androidx.core:core-ktx:1.19.0')],
  ['Android host consumes system bar and cutout insets', main.includes('WindowCompat.setDecorFitsSystemWindows(window, false)') && main.includes('WindowInsetsCompat.Type.displayCutout()') && main.includes('view.setPadding(safe.left, safe.top, safe.right, safe.bottom)')],
  ['web shell cannot exceed its host viewport', shellStyles.includes('.os{width:100%;max-width:100%;min-width:0;') && shellStyles.includes('.content{grid-row:2;position:relative;min-width:0;max-width:100%;')],
  ['native MCP coalesces identical retried tool calls', mcpServer.includes('private val inFlight') && mcpServer.includes('private val completed') && mcpServer.includes('completeRequest(key, response)')],
  ['relay ignores stale socket close and response events', relayWorker.includes('if (socket !== this.socket) return;') && (relayWorker.match(/if \(socket !== this\.socket\) return;/g)||[]).length >= 3],
  ['relay never uses Durable Object payload storage', !relayWorker.includes('ctx.storage') && !relayWorker.includes('.storage.put') && !relayWorker.includes('.storage.get')],
  ['raw chat protocol is declared', adapter.includes("const CALL_OPEN = '[RIFT_CALL]'") && adapter.includes("const RESULT_MARKER = '[RIFT_RESULT]'")],
  ['chat-facing JSON protocol is removed', !adapter.includes('rift-tools-v2') && !adapter.includes('RIFT_TOOL_RESULT_V2') && !adapter.includes('reply with ONLY JSON')],
  ['raw parser supports nested paths and heredocs', adapter.includes('setRawPath') && adapter.includes('parseRawCallBlock') && adapter.includes('Heredoc delimiter')],
  ['raw results are bounded before composer injection', adapter.includes('MAX_RESULT_CHARS = 48000') && adapter.includes('Rift result truncated')],
  ['outgoing messages are acknowledged', adapter.includes('waitForOutgoingAcceptance')],
  ['MCP tool contract exposes hash, archive, and safe extract', host.includes('"rift_hash"') && host.includes('"rift_archive"') && host.includes('"rift_extract"') && host.includes('"fs.hash"') && host.includes('"fs.archive"') && host.includes('"fs.extract"')],
  ['archive and extract are classified as writes', host.includes('"rift_archive", "rift_extract"') && host.includes('"archive", "extract")) return true')],
  ['archive/extract participate in AI rollback journaling', journal.includes('"rift_copy", "rift_archive", "rift_extract"') && journal.includes('"copy", "archive", "extract"')],
  ['workspace capability reports include hash/archive/extract', sandbox.includes('"stat", "hash", "list"') && sandbox.includes('"copy", "archive", "extract"')],
  ['workspace delete fails closed', sandbox.includes('require(removed && !file.exists())')],
  ['workspace content writes are staged atomically', sandbox.includes('private fun writeBytesAtomic') && sandbox.includes('commitStaged(staged, file, label)')],
  ['workspace copy and archive stage before replacement', sandbox.includes('UUID.randomUUID()}.copying') && sandbox.includes('commitStaged(temporary, destination, to)')],
  ['workspace extraction rejects traversal and oversized archives', sandbox.includes('ZipInputStream(BufferedInputStream') && sandbox.includes('Archive entry escaped destination') && sandbox.includes('MAX_ARCHIVE_EXTRACTED_BYTES')],
  ['workspace directory reads fail instead of returning false-empty results', sandbox.includes('Could not read directory:') && sandbox.includes('Could not read archive source directory:')],
  ['long transfers use the dedicated native worker', dispatcher.includes('method in setOf("fs.copy", "fs.move", "fs.zip", "fs.unzip")')],
  ['native copy waits for the destination before resolving', dispatcher.includes('val result = copyNode(') && !dispatcher.includes('transferService.submit(job)')],
  ['native move waits for source removal before resolving', dispatcher.includes('val result = moveNode(')],
  ['browser transfer IDs reach native progress', core.includes('transferId:transferId||') && dispatcher.includes('args.optString("transferId").ifBlank')],
  ['native progress targets the transfer UI', main.includes('window.RiftTransferUI?.__progress')],
  ['mount import and export are binary-safe', !workspaceAdapter.includes('core.fs.readText') && workspaceAdapter.includes('core.fs.copy')],
  ['SAF writes fall back across provider modes', dispatcher.includes('for (mode in listOf("rwt", "wt", "w"))') && dispatcher.includes('openExternalOutput(destination, toPath)')],
  ['SAF file creation preserves exact names', dispatcher.includes('DocumentsContract.createDocument') && dispatcher.includes('Android provider changed file name')],
  ['partial SAF trees roll back recursively', dispatcher.includes('fun removeTree(current: DocumentFile)') && dispatcher.includes('current.listFiles().forEach')],
  ['copy reports the exact rejected path', dispatcher.includes('Copy failed at $fromPath -> $toPath') && dispatcher.includes('Copied file is missing: $toPath')],
  ['workspace exports verify every file, directory and byte', dispatcher.includes('verifyTransferComplete(manifest, progress') && dispatcher.includes('Transfer incomplete: copied ${progress.files} of ${manifest.files} files') && dispatcher.includes('Transfer incomplete: wrote ${progress.bytes} of ${manifest.bytes} bytes')],
  ['mount moves use the native transfer engine', workspaceAdapter.includes('moveFromMount') && workspaceAdapter.includes('moveToMount') && workspaceAdapter.includes('core.fs.move')],
  ['ZIP and unzip preserve both mount identities', core.includes('fromMountId:source.mountId') && core.includes('toMountId:destination.mountId') && dispatcher.includes('args.getString("toMountId")')],
  ['ZIP and unzip have one core implementation each', (core.match(/async zip\(/g)||[]).length===1 && (core.match(/async unzip\(/g)||[]).length===1],
  ['unzip has entry and expansion limits', dispatcher.includes('MAX_ARCHIVE_ENTRIES') && dispatcher.includes('MAX_EXTRACTED_BYTES')],
  ['workspace archive completes before returning', !sandbox.includes('RiftTransferJob("archive"') && sandbox.includes('"archive" -> createArchive(')],
  ['delete verifies the Android provider result', core.includes('if(removed!==true)throw new Error')],
  ['file actions reject duplicate execution', filesUi.includes('if(fileActionBusy)return')],
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
