import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const identity = read(k + 'RiftFileIdentityV2.kt');
const records = read(k + 'RiftWorkspaceRecords.kt');
const diff = read(k + 'RiftDiffEngineV2.kt');
const gradle = read('android/app/build.gradle.kts');
const ownership = read('docs/SOURCE_OWNERSHIP.md');
const doc = read('docs/systems/workspace/live/README.md');

assert.match(identity, /internal object RiftFileIdentityV2/);
assert.match(identity, /const val VERSION = 2/);
assert.match(identity, /MIN_RENAME_SIMILARITY = 60/);
assert.match(identity, /MAJOR_REWRITE_MAX_SIMILARITY = 25/);
assert.match(identity, /MIN_MAJOR_REWRITE_BYTES = 512L/);
assert.match(identity, /MAX_SIMILARITY_CANDIDATES_PER_SIDE = 64/);
assert.match(identity, /MAX_SIMILARITY_COMPARISONS = 1024/);
assert.match(identity, /Relation\("renamed", source\.path, target\.path, 100, "sha256", true\)/);
assert.match(identity, /Relation\("copied", source\.path, target\.path, 100, "sha256", true\)/);
assert.match(identity, /"bounded-line-dice"/);
assert.match(identity, /similaritySkipped = candidateLimitExceeded \|\| comparisons >= MAX_SIMILARITY_COMPARISONS/);
assert.ok(!/import android\./.test(identity), 'Identity engine must remain Android-framework independent');

const rewriteLoop = identity.match(/for \(path in beforeByPath[\s\S]*?relations \+= Relation\("rewritten"/)?.[0] || '';
assert.match(rewriteLoop, /if \(comparisons >= MAX_SIMILARITY_COMPARISONS\) break/);
assert.match(rewriteLoop, /comparisons\+\+[\s\S]*majorRewriteSimilarity/);

assert.match(records, /RiftFileIdentityV2\.correlate\(/);
assert.match(records, /action", relation\.kind/);
assert.match(records, /"renamed", "copied"/);
assert.match(records, /rewriteSimilarity != null -> "rewritten"/);
assert.match(records, /rewriteRelations\[path\]/);
assert.match(records, /allowRewriteHeuristic = false/);
assert.match(records, /rewriteRelation\?\.let\(::relationJson\)/);
assert.match(records, /identityRelations/);
assert.match(records, /similarityComparisons/);
assert.match(records, /similaritySkipped/);
assert.match(records, /relationJson\(/);

assert.match(diff, /beforePath: String\? = null/);
assert.match(diff, /afterPath: String\? = null/);
assert.match(diff, /val leftPath = beforePath \?: path/);
assert.match(diff, /val rightPath = afterPath \?: path/);

assert.match(gradle, /RiftFileIdentityV2\.kt/);
assert.match(ownership, /RiftFileIdentityV2\.kt/);
assert.match(ownership, /scripts\/test-rift-file-identity-v2\.mjs/);
assert.match(doc, /64 candidates per side/);
assert.match(doc, /1024 line-similarity comparisons/);
assert.match(doc, /content identity evidence, not proof of user intent/);

console.log('ok - Rift File Identity V2 is bounded, deterministic, provenance-safe and wired into Workspace Records');
