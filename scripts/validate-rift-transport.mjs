import { existsSync, readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const stripCodeComments = text => text
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .split('\n').filter(line => !line.trimStart().startsWith('//')).join('\n');
const hasWebKitDependency = text => /(?:^|\n)\s*import\s+(?:android|androidx)\.webkit\.|(?:android|androidx)\.webkit\./m.test(stripCodeComments(text));
const k = 'android/app/src/main/java/com/riftos/app/';
const host = read(k + 'RiftToolHost.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const server = read(k + 'RiftMcpServer.kt');
const relayClient = read(k + 'RiftMcpRelayClient.kt');
const runtime = read(k + 'RiftMcpRuntime.kt');
const shell = read(k + 'RiftNativeShell.kt');
const services = read(k + 'RiftNativeShellServices.kt');
const headless = read(k + 'RiftHeadlessJsRuntime.kt');
const main = read(k + 'MainActivity.kt');
const desktop = read(k + 'RiftNativeDesktop.kt');
const workspaceApps = read(k + 'RiftNativeWorkspaceApps.kt');
const devLab = read(k + 'RiftNativeDevLab.kt');
const git = read(k + 'RiftNativeGit.kt');
const secrets = read(k + 'RiftSecretStore.kt');
const browserHost = read(k + 'RiftBrowserAppHost.kt');
const browserWindow = read(k + 'RiftBrowserWindow.kt');
const crashGuard = read(k + 'RiftBrowserRendererCrashGuard.kt');
const browserBridge = read(k + 'RiftBrowserMcpAppBridge.kt');
const vortex = read(k + 'RiftVortexBridgeClient.kt');
const vortexAgent = read(k + 'RiftVortexLocalAgent.kt');
const chatHandoff = read(k + 'RiftChatHandoff.kt');
const workspaceRecords = read(k + 'RiftWorkspaceRecords.kt');
const workspaceWatcher = read(k + 'RiftWorkspaceWatcher.kt');
const boundedAsync = read(k + 'RiftBoundedAsync.kt');
const manifest = read('android/app/src/main/AndroidManifest.xml');
const accessibilityConfig = read('android/app/src/main/res/xml/vortex_agent_accessibility.xml');
const gradle = read('android/app/build.gradle.kts');
const preBuildBlock = gradle.match(/tasks\.named\("preBuild"\)\.configure\s*\{([\s\S]*?)\n\}/)?.[1] || '';
const adapter = read('android/app/src/main/assets/riftbrowser-mcp-app.js');
const relayWorker = read('relay/src/index.js');

const expectedTools = [
  'rift_shell_exec','rift_info','rift_stat','rift_hash','rift_list','rift_read_text','rift_write_text','rift_mkdir',
  'rift_remove','rift_move','rift_copy','rift_archive','rift_extract','rift_audit','rift_scan','rift_project_export',
  'rift_workspace_diff','rift_workspace_exec'
];
const declaredTools = [...host.matchAll(/tool\(\s*"([^"]+)"/g)].map(match => match[1]);
const uniqueDeclaredTools = [...new Set(declaredTools)].sort();
const expectedSorted = [...expectedTools].sort();

const checks = [
  ['MCP surface remains exactly the expected 18-tool family', JSON.stringify(uniqueDeclaredTools) === JSON.stringify(expectedSorted)],
  ['MCP shell execution is process-owned and native', runtime.includes('private var nativeShell: RiftNativeShell?') && runtime.includes('fun shellExecutor(): RiftShellExecutor? = nativeShell') && shell.includes('class RiftNativeShell(context: Context) : RiftShellExecutor') && shell.includes('.put("webViewRequired", false)')],
  ['renderer shell fallback is absent', !existsSync(k + 'RiftShellBridge.kt') && !existsSync(k + 'RiftSystemDump.kt') && !runtime.includes('registerShellBridge') && !runtime.includes('compatibilityFallback') && !shell.includes('compatibilityFallback')],
  ['MainActivity is renderer-free', !hasWebKitDependency(main) && !main.includes('addJavascriptInterface')],
  ['Rift++ uses bounded headless QuickJS', shell.includes('headlessJs.executeRiftpp(args, cwd)') && headless.includes('quickJs {') && headless.includes('evaluationTimeoutMillis') && !hasWebKitDependency(headless) && !headless.includes('Socket(') && !headless.includes('ProcessBuilder')],
  ['RiftBrowser owns file chooser and WebView app surfaces', browserWindow.includes('WebChromeClient.FileChooserParams') && browserWindow.includes('FILE_CHOOSER_REQUEST') && browserHost.includes('WebViewCompat.addWebMessageListener') && browserHost.includes('appOrigin(app.id)') && browserHost.includes('https://app-$token.riftos.local')],
  ['browser renderer crashes stay browser-scoped', crashGuard.includes('destroyDeadWebView') && crashGuard.includes('MAX_EVENTS = 16') && crashGuard.includes('MAX_EVENT_STORE_BYTES = 32 * 1024') && !crashGuard.includes('requestShellRecovery') && !crashGuard.includes('MainActivity')],
  ['browser compatibility bridge has no shell authority', !browserBridge.includes('RiftShellBridge') && !browserBridge.includes('rift_shell_result') && !adapter.includes('RiftShellMcp')],
  ['native desktop and built-ins are Android-owned', main.includes('RiftNativeDesktop(') && main.includes('RiftNativeSystemApps(') && main.includes('RiftNativeWorkspaceApps(') && desktop.includes('class RiftNativeDesktop')],
  ['native Files owns persisted SAF mounts', workspaceApps.includes('Intent.ACTION_OPEN_DOCUMENT_TREE') && workspaceApps.includes('takePersistableUriPermission') && workspaceApps.includes('FLAG_GRANT_READ_URI_PERMISSION') && workspaceApps.includes('FLAG_GRANT_WRITE_URI_PERMISSION') && workspaceApps.includes('isReadPermission') && workspaceApps.includes('isWritePermission') && workspaceApps.includes('DocumentFile.fromTreeUri') && workspaceApps.includes('releasePersistableUriPermission')],
  ['native Files external editor is bounded and rollback-protected', workspaceApps.includes('MAX_EDITOR_BYTES') && workspaceApps.includes('MAX_FILES_ROWS') && workspaceApps.includes('readDocumentBytes') && workspaceApps.includes('writeDocumentBytes') && workspaceApps.includes('writeDocumentBytes(document, display, original)') && workspaceApps.includes('Android provider write verification failed') && workspaceApps.includes('Android save failed and rollback was incomplete') && workspaceApps.includes('Editor target is not a file') && workspaceApps.includes('Editor save failed and previous file could not be restored')],
  ['Dev Lab is native and browser execution is explicitly separated', devLab.includes('object RiftNativeDevLab') && services.includes('Web execution/HTML preview is owned by RiftBrowser') && !hasWebKitDependency(workspaceApps)],
  ['Workspace Records is local record-only native UI', workspaceApps.includes('WORKSPACE RECORDS · LOCAL ONLY') && workspaceRecords.includes('File(appContext.filesDir, "riftfs/workspace")') && workspaceRecords.includes('File(appContext.filesDir, "rift-workspace-records")') && workspaceRecords.includes('fun observe(') && workspaceRecords.includes('fun query(') && workspaceRecords.includes('fun checkpoint(') && !/\bfun\s+(?:approve|deny|rollback|reconnect)\s*\(/.test(workspaceRecords)],
  ['Git credential lives behind RiftSecretStore', git.includes('RiftSecretStore') && git.includes('TOKEN_KEY = "github.token"') && secrets.includes('AndroidKeyStore') && secrets.includes('MAX_SECRET_BYTES = 32 * 1024') && secrets.includes('MAX_PACKED_BYTES = 64 * 1024') && secrets.includes('Encrypted secret could not be persisted') && secrets.includes('Secret removal could not be persisted') && workspaceApps.includes('nativeGit.storeToken(token)') && workspaceApps.includes('tokenInput.setText("")') && services.includes('pairing token must be entered in native Settings')],
  ['native Git shell rejects token transport', git.includes('GitHub tokens are entered only in native Settings') && !shell.includes('github.token')],
  ['RiftLLM fixed training services are native', services.includes('RiftTrainDataTaskRunner.execute') && services.includes('RiftLlmDevClient') && services.includes('Legacy RiftLLM shell helper')],
  ['native Settings owns bounded credential ingress', workspaceApps.includes('GitHub authentication') && workspaceApps.includes('RiftLLM Dev API pairing') && workspaceApps.includes('Pair + Verify') && workspaceApps.includes('GitHub credential clear failed') && services.includes('pairing token must be entered in native Settings')],
  ['Vortex bridge is bounded Binder IPC', vortex.includes('BIND_AUTO_CREATE') && vortex.includes('DESCRIPTOR = "com.vortex3d.app.devbridge.v1"') && vortex.includes('MAX_REQUEST_JSON_BYTES = 256 * 1024') && vortex.includes('MAX_RESPONSE_JSON_BYTES = 512 * 1024') && vortex.includes('REMOTE_CHUNK_BYTES = 192 * 1024') && vortex.includes('MAX_IMAGE_BYTES = 512 * 1024') && vortex.includes('MAX_PULL_BYTES = 128L * 1024L * 1024L') && vortex.includes('Vortex artifact chunk offset mismatch') && vortex.includes('Vortex artifact next_offset mismatch') && vortex.includes('Vortex artifact transfer length mismatch') && vortex.includes('Unsupported Vortex preview MIME type') && services.includes('Vortex script source exceeds 240 KiB UTF-8') && !vortex.includes('Socket(')],
  ['Vortex shell bridge grammar is strict', services.includes('require(args.isEmpty()) { "usage: vortex $sub" }') && services.includes('ui-tree limit must be between 1 and 1024') && services.includes('require(args.size==3){"usage: vortex touch <action> <x> <y>"}') && services.includes('touch coordinates must be finite') && services.includes('require(args.size==1){"usage: vortex $sub <RiftFS-path> [--unsafe] [--live]"}') && services.includes('vortex artifact id is too long')],
  ['chat handoff is bounded local integrity storage', chatHandoff.includes('MAX_BUNDLE_BYTES = 32L * 1024L * 1024L') && chatHandoff.includes('MAX_BUNDLES_SCANNED = 4096') && chatHandoff.includes('MAX_JSON_DEPTH = 64') && chatHandoff.includes('MAX_PATH_CHARS = 1024') && chatHandoff.includes('verifyEntry(zip, manifest, "transcript.jsonl", MAX_TRANSCRIPT_BYTES)') && chatHandoff.includes('validateManifest(manifest, seen)') && chatHandoff.includes('Manifest transcript_included must be a boolean') && chatHandoff.includes('Manifest $key must be a string') && chatHandoff.includes('decodeUtf8Text') && chatHandoff.includes('CodingErrorAction.REPORT') && chatHandoff.includes('Chat handoff directory exceeds scan limit') && chatHandoff.includes('.riftchat bundle exceeds local bundle limit') && !chatHandoff.includes('Socket(')],
  ['chat shell grammar is strict', shell.includes('"chat" -> services.chat(args, cwd)') && services.includes('require(args.isEmpty()) { "usage: chat list" }') && services.includes('require(args.size == 1) { "usage: chat $sub <bundle.riftchat>" }') && services.includes('transcript max-chars must be between 1 and 65536') && services.includes('RiftChatHandoff.execute(riftRoot, request)')],
  ['local agent stays package-scoped and bounded', vortexAgent.includes('TARGET_PACKAGE = "com.vortex3d.app"') && vortexAgent.includes('TARGET_PACKAGE = "com.riftos.app"') && vortexAgent.includes('MAX_SCAN_NODES = 4096') && vortexAgent.includes('MAX_KEYBOARD_SCAN_NODES = 4096') && vortexAgent.includes('MAX_NODE_FIELD_CHARS = 512') && vortexAgent.includes('MAX_PARENT_DEPTH = 64') && vortexAgent.includes('gesture point is covered by another visible package window') && vortexAgent.includes('Password fields are not available to the local UI agent') && accessibilityConfig.includes('android:packageNames="com.vortex3d.app,com.riftos.app,com.samsung.android.honeyboard"') && manifest.includes('android.permission.BIND_ACCESSIBILITY_SERVICE') && !vortexAgent.includes('ProcessBuilder') && !vortexAgent.includes('Runtime.getRuntime')],
  ['local agent shell parsing is strict', services.includes('require(args.isEmpty()){"usage: $name $sub"}') && services.includes('require(args.size==2){"usage: $name tap <x> <y>"}') && services.includes('require(args.size in 4..5){"usage: $name swipe <x1> <y1> <x2> <y2> [ms]"}') && services.includes('tree limit must be between 1 and 1024') && services.includes('require(name=="riftos-agent")')],
  ['tool writes stay permission-gated', host.includes('allowWrite()') && host.includes('workspaceBatchMutates(args)') && host.includes('rift_workspace_exec')],
  ['workspace writes remain staged/atomic', sandbox.includes('private fun writeBytesAtomic') && sandbox.includes('commitStaged(staged, file, label)') && sandbox.includes('MAX_ARCHIVE_EXTRACTED_BYTES')],
  ['MCP retry dedupe is relay-scoped and transport-id neutral', server.includes('retryKey') && server.includes('private val completed') && server.includes('remove("id")') && server.includes('RequestWaiter') && relayClient.includes('server.handleAsync(payload, requestId)') && relayWorker.includes('existing.waiters.length >= 8') && relayWorker.includes('waiter.rpcId') && relayWorker.includes('requestFingerprint(payload)') && relayWorker.includes('delete normalized.id') && relayWorker.includes('canonicalJson')],
  ['MCP/local async lifecycle is bounded end-to-end', sandbox.includes('REQUEST_TIMEOUT_MS = 45_000L') && shell.includes('SHELL_TIMEOUT_MS = 60_000L') && server.includes('REQUEST_TIMEOUT_MS = 65_000L') && relayClient.includes('REQUEST_FORWARD_TIMEOUT_MS = 70_000L') && relayWorker.includes('REQUEST_TIMEOUT_MS = 75_000') && server.includes('MAX_IN_FLIGHT_REQUESTS = 64') && server.includes('MAX_COMPLETED_BYTES = 8 * 1024 * 1024') && boundedAsync.includes('terminal.compareAndSet(false, true)') && boundedAsync.includes('taskRef.get()?.cancel(true)') && boundedAsync.includes('RiftDeadline.runUntil')],
  ['workspace watcher and recorder bursts are bounded', workspaceWatcher.includes('MAX_WATCHED_DIRECTORIES = 2_048') && workspaceWatcher.includes('INSTALL_BUDGET_MS = 2_000L') && workspaceWatcher.includes('putIfAbsent') && workspaceRecords.includes('MAX_PENDING_WATCH_EVENTS = 512') && workspaceRecords.includes('watch:burst')],
  ['MCP manifest diagnostics remain exposed', server.includes('riftos/toolCount') && server.includes('riftos/toolManifestHash') && host.includes('mcpManifest')],
  ['relay ignores stale socket events and does not persist payloads', (relayWorker.match(/if \(socket !== this\.socket\) return;/g) || []).length >= 3 && relayWorker.includes('MAX_PENDING_REQUESTS = 64') && relayWorker.includes('readBoundedText') && relayWorker.includes('encoder.encode(raw).byteLength') && relayWorker.includes('Malformed RiftOS MCP response') && relayWorker.includes('mcp.notification') && relayClient.includes('mcp.notification') && !relayWorker.includes('ctx.storage')],
  ['device relay client uses byte-accurate bounded envelopes', relayClient.includes('MAX_MESSAGE_BYTES = 1_000_000') && relayClient.includes('text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES') && relayClient.includes('Local MCP response exceeds relay message limit')],
  ['raw chat tool protocol remains bounded and manual', adapter.includes("const CALL_OPEN = '[RIFT_CALL]'") && adapter.includes('MAX_RESULT_CHARS = 48000') && adapter.includes('stageComposerMessage') && !adapter.includes('auto-submit') && !adapter.includes('RiftMcpAppControl')],
  ['build provenance remains embedded', gradle.includes('RIFT_SOURCE_SHA') && gradle.includes('RIFT_BUILD_RUN_ID') && gradle.includes('RIFT_BUILD_RUN_NUMBER') && shell.includes('BuildConfig.RIFT_SOURCE_SHA')],
  ['WebView ownership is a preBuild gate', preBuildBlock.includes('dependsOn(validateRiftBrowserWebViewOwnership)') && gradle.includes('RiftBrowser WebKit owner set drifted') && gradle.includes('Actual WebKit dependencies/WebView XML are allowed only in')],
  ['manifest routes preview only to RiftBrowser-owned preview activity', manifest.includes('.RiftBrowserPreviewActivity') && !manifest.includes('.RiftPreviewActivity')],
  ['preview stays workspace-local and per-root isolated', read(k + 'RiftBrowserPreviewActivity.kt').includes('blockNetworkLoads=true') && read(k + 'RiftBrowserPreviewActivity.kt').includes('shouldOverrideUrlLoading') && read(k + 'RiftBrowserPreviewActivity.kt').includes('External preview networking is disabled') && read(k + 'RiftBrowserPreviewActivity.kt').includes('previewHostFor') && read(k + 'RiftBrowserPreviewActivity.kt').includes('previewRoot.path.startsWith(workspace.path+File.separator)')],
  ['retired renderer/dispatcher islands remain absent', ['RiftShellBridge.kt','RiftSystemDump.kt','RiftNativeDispatcher.kt','RiftTransferManifest.kt','AndroidWebViewBrowserEngine.kt','RiftNativeAppHost.kt','RiftPreviewActivity.kt','RiftRendererCrashGuard.kt'].every(name => !existsSync(k + name))],
];

const failed = checks.filter(([, ok]) => !ok);
for (const [name, ok] of checks) console.log(`${ok ? 'ok' : 'FAIL'} - ${name}`);
if (failed.length) process.exitCode = 1;
