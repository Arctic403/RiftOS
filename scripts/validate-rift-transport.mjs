import { readFileSync } from 'node:fs';

const browser = readFileSync('android/app/src/main/java/com/riftos/app/RiftBrowserWindow.kt', 'utf8');
const adapter = readFileSync('android/app/src/main/assets/riftbrowser-mcp-app.js', 'utf8');
const host = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolHost.kt', 'utf8');
const sandbox = readFileSync('android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt', 'utf8');
const journal = readFileSync('android/app/src/main/java/com/riftos/app/RiftAiJournal.kt', 'utf8');

const checks = [
  ['transport is parked behind the shell', browser.includes('parkTransportBehindShell()')],
  ['hidden transport is not made INVISIBLE', !/webView\.visibility\s*=\s*View\.INVISIBLE/.test(browser)],
  ['renderer priority is retained', browser.includes('RENDERER_PRIORITY_BOUND, false')],
  ['native dispatch uses queueTask', browser.includes('control.queueTask')],
  ['pending task clears on submitted event', browser.includes('phase == "submitted"')],
  ['JSON protocol V2 is declared', adapter.includes("const PROTOCOL_V2 = 'rift-tools-v2'")],
  ['V2 results are correlated', adapter.includes('RIFT_TOOL_RESULT_V2') && adapter.includes('request_id')],
  ['outgoing messages are acknowledged', adapter.includes('waitForOutgoingAcceptance')],
  ['legacy protocol remains available', adapter.includes('<rift_call>')],
  ['archive is classified as a write', host.includes('"archive"') && journal.includes('"archive"')],
  ['archive is locally implemented', sandbox.includes('private fun createArchive')]
];

const failed = checks.filter(([, ok]) => !ok);
for (const [name, ok] of checks) console.log(`${ok ? 'ok' : 'FAIL'} - ${name}`);
if (failed.length) process.exitCode = 1;
