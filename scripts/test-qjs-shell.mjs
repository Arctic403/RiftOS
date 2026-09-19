import fs from 'node:fs';
import assert from 'node:assert/strict';

const shell = fs.readFileSync(new URL('../android/app/src/main/java/com/riftos/app/RiftNativeShell.kt', import.meta.url), 'utf8');
const runtime = fs.readFileSync(new URL('../android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt', import.meta.url), 'utf8');
const gradle = fs.readFileSync(new URL('../android/app/build.gradle.kts', import.meta.url), 'utf8');

assert.match(shell, /"qjs"\s*->\s*\{\s*val value = headlessJs\.executeQuickJs\(args, cwd\)/s);
assert.match(shell, /qjs help\|version\|eval\|run/);
assert.match(shell, /"qjs", "semx", "riftpp"/);

const start = runtime.indexOf('fun executeQuickJs(args: List<String>, cwd: String): CommandResult');
const end = runtime.indexOf('fun executeDeveloperTool(args: List<String>): CommandResult', start);
assert.ok(start >= 0 && end > start, 'bounded QuickJS method is missing');
const qjs = runtime.slice(start, end);

assert.match(qjs, /QJS_EVALUATION_TIMEOUT_MS/);
assert.match(qjs, /MAX_QJS_SOURCE_BYTES/);
assert.match(qjs, /MAX_QJS_TOTAL_BYTES/);
assert.match(qjs, /MAX_QJS_FILES/);
assert.match(qjs, /MAX_QJS_OUTPUT_BYTES/);
assert.match(qjs, /bounded qjs run accepts classic \.js scripts only/);
assert.match(qjs, /function\("__rift_qjs_read_text"\)/);
assert.match(qjs, /riftFsRead", true/);
assert.match(qjs, /riftFsWrite", false/);
assert.match(qjs, /processAuthority", false/);
assert.match(qjs, /networkAuthority", false/);
assert.match(qjs, /androidAuthority", false/);
assert.doesNotMatch(qjs, /__rift_write_text|ProcessBuilder|Runtime\.getRuntime|Socket\(|startActivity|nativeGit|executeSemnexis|executeRiftpp/);

assert.match(runtime, /const val QJS_PRELUDE = """/);
assert.match(runtime, /readText: path => __rift_qjs_read_text\(String\(path\)\)/);
const prelude = runtime.match(/const val QJS_PRELUDE = """[\s\S]*?"""/)?.[0] ?? '';
assert.doesNotMatch(prelude, /writeText|fetch\(|XMLHttpRequest|WebSocket|process|require\(/);

assert.match(gradle, /io\.github\.dokar3:quickjs-kt:1\.0\.14/);

console.log('ok - bounded qjs RiftShell boundary');
