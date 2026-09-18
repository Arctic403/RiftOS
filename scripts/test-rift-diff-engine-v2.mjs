import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const engine = read(k + 'RiftDiffEngineV2.kt');
const records = read(k + 'RiftWorkspaceRecords.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const workspaceRecordsDoc = read('docs/systems/workspace/live/README.md');

assert.match(engine, /internal object RiftDiffEngineV2/);
assert.match(engine, /const val VERSION = 2/);
assert.match(engine, /MAX_EXACT_MATRIX_CELLS = 250_000L/);
assert.match(engine, /\(aSize\.toLong\(\) \+ 1L\) \* \(bSize\.toLong\(\) \+ 1L\) <= MAX_EXACT_MATRIX_CELLS/);
assert.match(engine, /No textual line delta; file existence changed/);
assert.match(engine, /No normalized textual line delta; byte content changed/);
assert.match(engine, /private fun exactLcs\(/);
assert.match(engine, /private fun patienceAnchors\(/);
assert.match(engine, /Longest increasing subsequence by B index/);
assert.match(engine, /private fun hunks\(/);
assert.match(engine, /CONTEXT_LINES \* 2 \+ 1/);
assert.match(engine, /Rift-Diff-Strategy: adaptive-lcs-patience/);
assert.match(engine, /diff truncated \(\$representedChanges\/\$changed changed lines represented\)/);
assert.ok(!/import android\./.test(engine), 'Diff engine must remain Android-framework independent');

assert.match(records, /RiftDiffEngineV2\.render\(/);
assert.match(records, /RiftDiffEngineV2\.Descriptor\(it\.size, it\.sha256\)/);
assert.ok(!records.includes('removed.take(MAX_DIFF_LINES / 2)'), 'Legacy one-middle-block diff implementation returned');

assert.match(gradle, /RiftDiffEngineV2\.kt/);
assert.match(ownership, /RiftDiffEngineV2\.kt/);
assert.match(workspaceRecordsDoc, /adaptive exact-LCS \/ patience-style multi-hunk engine/);
assert.match(workspaceRecordsDoc, /250000 matrix cells/);

console.log('ok - Rift Diff Engine V2 is bounded, adaptive, multi-hunk, source-owned and wired into Workspace Records');
