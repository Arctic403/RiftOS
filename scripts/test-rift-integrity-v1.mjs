import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = file => readFileSync(file, 'utf8');
const k = 'android/app/src/main/java/com/riftos/app/';
const source = read(k + 'RiftSourceIntelligenceV2.kt');
const sandbox = read(k + 'RiftToolSandbox.kt');
const observer = read(k + 'RiftRepositoryConsistencyObserver.kt');
const pkg = JSON.parse(read('package.json'));

assert.match(source, /const val VERSION = 6/);
assert.match(source, /const val MAX_SYNTAX_ISSUES = 128/);
assert.match(source, /data class SyntaxIssue\(/);
assert.match(source, /data class SyntaxEvidence\(/);
assert.match(source, /val localIntent: Boolean\? = null/);
assert.match(source, /syntax = analyzeSyntax\(language, normalized\)/);
assert.match(source, /private fun analyzeSyntax\(language: String, text: String\): SyntaxEvidence/);
assert.match(source, /mode = "bounded-structural-v4-conservative"/);
for (const code of [
  'unexpected-closing-delimiter',
  'mismatched-delimiter',
  'unterminated-block-comment',
  'unterminated-triple-string',
  'unterminated-string',
  'unclosed-delimiter',
]) {
  assert.ok(source.includes(code), 'missing structural syntax issue: ' + code);
}
assert.match(source, /AnalysisBoundExceeded\("syntax-issue-bound"\)/);
assert.ok(source.includes('"cpp" -> Regex'), 'C/C++ dependency extraction must remain present');
assert.ok(source.includes('localIntent = it.groupValues[1]'), 'quoted include local-intent classification must remain present');
assert.match(source, /specifier\.startsWith\("\."\)/);
assert.match(source, /specifier\.startsWith\("crate::"\)/);
assert.match(source, /add\(it\.groupValues\[1\], "module", index \+ 1, true\)/);
assert.match(source, /fun kotlinStringEnd\(/);
assert.match(source, /fun kotlinInterpolationEnd\(/);
assert.match(source, /fun maskKotlinInterpolatedStrings\(/);
assert.match(source, /fun javascriptRegexMayStart\(/);
assert.match(source, /fun javascriptRegexEnd\(/);
assert.ok(source.includes('index = regexEnd'), 'structural scanner must resume exactly after the regex literal');
assert.ok(!source.includes('index = regexEnd + 1'), 'regex scanning must not skip the token immediately after a regex literal');
assert.match(source, /fun javascriptTemplateEnd\(/);
assert.match(source, /fun javascriptTemplateExpressionEnd\(/);
assert.match(source, /fun maskJavascriptTemplates\(/);
assert.match(source, /fun javascriptDependencyCodeMask\(/);
assert.match(source, /filter\(::javascriptMatchStartsInCode\)/);
assert.ok(!source.includes('fun kotlinInterpolatedStringEnd('), 'retired last-quote Kotlin interpolation heuristic must not return');
assert.ok(source.includes('(?:import|export)'), 'static JS from-import extraction must be statement-anchored');

assert.match(sandbox, /PROJECT_INTELLIGENCE_CACHE_VERSION = 9/);
assert.match(sandbox, /MAX_INTEGRITY_FILES = 4_096/);
assert.match(sandbox, /MAX_INTEGRITY_DEPENDENCIES = 4_096/);
assert.match(sandbox, /MAX_INTEGRITY_FINDINGS = 1_024/);
assert.match(sandbox, /MAX_INTEGRITY_PREVIEW = 240/);
assert.match(sandbox, /data class SyntaxIssueRecord\(/);
assert.match(sandbox, /data class IntegrityDependencyResolution\(/);
assert.match(sandbox, /val syntaxMode: String = "unavailable"/);
assert.match(sandbox, /val syntaxValid: Boolean = true/);
assert.match(sandbox, /val syntaxIssues: List<SyntaxIssueRecord> = emptyList\(\)/);

assert.match(sandbox, /\.put\("localIntent", dependency\.localIntent \?: JSONObject\.NULL\)/);
assert.match(sandbox, /\.put\("syntaxMode", indexed\?\.syntaxMode \?: JSONObject\.NULL\)/);
assert.match(sandbox, /\.put\("syntaxValid", indexed\?\.syntaxValid \?: false\)/);
assert.match(sandbox, /\.put\("syntaxIssues", syntaxIssues\)/);
assert.match(sandbox, /syntaxMode = row\.optString\("syntaxMode"\)/);
assert.match(sandbox, /syntaxValid = row\.optBoolean\("syntaxValid", false\)/);
assert.match(sandbox, /dependency\.localIntent/);
assert.match(sandbox, /analysis\.syntax\.issues/);
assert.match(sandbox, /syntaxMode = analysis\.syntax\.mode/);
assert.match(sandbox, /syntaxValid = analysis\.syntax\.valid/);
assert.match(sandbox, /"not-applicable" -> Unit/);
assert.match(sandbox, /"bounded-structural-v4-conservative"/);
assert.match(sandbox, /rift-source-intelligence-v6-bounded-structural-v4/);
assert.match(sandbox, /fun sourcePackagePath\(/);
assert.match(sandbox, /"\/src\/main\/java\/"/);
assert.match(sandbox, /"qualified-package-not-local"/);
assert.match(sandbox, /"qualified-local-symbol-resolved"/);
assert.match(sandbox, /"local-package-symbol-missing"/);
assert.match(sandbox, /"local-package-wildcard"/);
assert.ok(!sandbox.includes('projectQualifiedIntent'), 'path-substring package guessing must not return');
assert.ok(!sandbox.includes('candidate.contains("/" + packagePath + "/")'), 'repository path text must not prove Kotlin/Java package locality');

assert.match(sandbox, /if \(kind == "integrity"\) return projectIntegrity\(path, query\)/);
assert.match(sandbox, /private fun projectIntegrity\(path: String, query: String\): JSONObject/);
assert.match(sandbox, /refreshSymbolIndex\(base, verifyContent = true\)/);
assert.match(sandbox, /"rift-repository-integrity-v1"/);
assert.match(sandbox, /"N1\.8\.1"/);
assert.match(sandbox, /\.put\("complete", complete\)/);
assert.match(sandbox, /\.put\("clean", clean\)/);
assert.match(sandbox, /integritySha256 = RiftPatchManifestV1\.sha256Canonical\(canonicalPayload\)/);
assert.match(sandbox, /"clean-oracle"/);
assert.match(sandbox, /"focused-seed"/);
assert.match(sandbox, /"focused-seed-direct-frontier-v1"/);
assert.match(sandbox, /"fullRepositoryOracleRequiredForPromotion", true/);
assert.match(sandbox, /"integrity-seed-not-indexed"/);
assert.match(sandbox, /"integrity-file-bound"/);
assert.match(sandbox, /"integrity-dependency-bound"/);
assert.match(sandbox, /"integrity-finding-bound"/);
assert.match(sandbox, /"integrity-frontier-bound"/);
assert.match(sandbox, /"integrity-frontier-output-bound"/);

assert.match(sandbox, /private fun integrityResolveDependency\(/);
for (const status of [
  'local-resolved',
  'local-missing',
  'ambiguous-local',
  'external-or-unclassified',
]) {
  assert.ok(sandbox.includes(status), 'missing integrity resolution status: ' + status);
}
assert.match(sandbox, /"n1\.8\.1-local-dependency-missing"/);
assert.match(sandbox, /"n1\.8\.1-local-dependency-ambiguous"/);
assert.match(sandbox, /"n1\.8\.1-syntax-" \+ issue\.code/);

const integrityStart = sandbox.indexOf('private fun projectIntegrity(path: String, query: String): JSONObject {');
const impactStart = sandbox.indexOf('private fun projectImpact(', integrityStart);
assert.ok(integrityStart >= 0 && impactStart > integrityStart, 'integrity view must remain structurally isolated');
const integrityBody = sandbox.slice(integrityStart, impactStart);
for (const forbidden of [
  'commitStaged(',
  'deletePath(',
  'invalidateIndex(',
  'writeText(',
  'copyPath(',
]) {
  assert.ok(!integrityBody.includes(forbidden), 'integrity view gained mutation authority: ' + forbidden);
}

assert.match(observer, /const val PHASE = "N1\.8\.0"/);
assert.ok(
  integrityStart >= 0 && !integrityBody.includes('repositoryConsistencyObserver.foundationView'),
  'N1.8.1 integrity must not mutate the promoted N1.8.0 canonical observer graph before promotion'
);

const check = String(pkg.scripts?.['check:transport'] || '');
assert.ok(
  check.includes('node scripts/test-rift-integrity-v1.mjs'),
  'main source gate must execute the N1.8.1 integrity regression'
);

console.log('ok - N1.8.1 integrity lane keeps N1.8.0 isolated, persists bounded syntax/local-intent evidence, distinguishes complete from clean, and exposes deterministic staged frontier scans');
