import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");
const sha256 = (text) => crypto.createHash("sha256").update(text, "utf8").digest("hex");

const contractText = read("riftarchitecture/n3-contract-v1.json");
const contract = JSON.parse(contractText);
const phase = JSON.parse(read("riftarchitecture/n3-phase-authority.json"));
const roadmap = read("docs/systems/riftcli/N3_ARCHITECTURE_IMPACT_ROADMAP.md");
const sandbox = read("android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt");
const sourceIntelligence = read("android/app/src/main/java/com/riftos/app/RiftSourceIntelligenceV2.kt");
const toolHost = read("android/app/src/main/java/com/riftos/app/RiftToolHost.kt");
const sandboxSource = read("android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt");
const n2Phase = JSON.parse(read("riftmemory/n2-phase-authority.json"));
const rootRoadmap = read("ROADMAP.md");

assert.equal(sha256(contractText), "7a8e4f1e69c76643c5c5093d19615ce70b97eeafed644f1679265b21112094ba");
assert.equal(contract.schema, "rift-architecture-n3-contract-v1");
assert.equal(contract.phase, "N3.0");
assert.equal(contract.status, "source-implemented");
assert.equal(contract.promotion, "pending-builder-install-proof");
assert.equal(contract.runtimeAuthority, false);
assert.equal(contract.plannerAuthority, false);
assert.equal(contract.mutationAuthority, false);
assert.equal(contract.memoryRuntimeActivation, false);
assert.equal(contract.globalBenchmarkRule, "NO_PERFORMANCE_OR_COMPARATIVE_BENCHMARKS_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE");

assert.equal(contract.prerequisites.observerN1_8, "PROMOTED");
assert.match(contract.prerequisites.memoryN2, /N2\.0-N2\.12 PROMOTED/);
assert.match(contract.prerequisites.memoryN2, /CANONICAL MEMORY RUNTIME INACTIVE/);
assert.equal(contract.prerequisites.persistentBatch, "B1+B2A+B2B PROMOTED");
assert.equal(contract.prerequisites.localAgentBatch, "PROMOTED");
assert.equal(contract.prerequisites.firstClassMcpBatch, "PROMOTED");
assert.equal(contract.prerequisites.n3MachineAuthorityPrelude, "PROMOTED");

assert.equal(contract.installedBaseline.riftosSourceSha, "3e9cfb5b7514e3a82d4d739fa0f3d92aba1ba23b");
assert.equal(String(contract.installedBaseline.builderRunNumber), "389");
assert.equal(contract.installedBaseline.mcpToolCount, 24);
assert.equal(contract.installedBaseline.mcpManifestSha256, "78ee72f5865650742a7ad8ab381e22632fd4f295ef74d6e5584fd45519fdfb64");

assert.equal(contract.authority.architectureEngineAuthoritativeForMutation, false);
assert.equal(contract.authority.architectureEngineMayGrantCapabilities, false);
assert.equal(contract.authority.architectureEngineMayActivateMemory, false);
assert.equal(contract.authority.memoryMayOverrideSourceOwnership, false);
assert.equal(contract.authority.plannerMayConsumeOnlyAfterN3Promotion, true);

assert.deepEqual(contract.requiredOutputs, [
  "owningSubsystem","dependencyImpact","architectureInvariants","requiredDocumentation",
  "relevantTests","buildPackageImpact","securityCapabilityBoundaries",
  "completeness","incompleteReasons","evidenceProvenance"
]);

assert.deepEqual(contract.bounds, {
  maxProjects:32,maxChangedFiles:4096,maxChangedSymbols:1000,maxReferenceSymbols:80,
  maxReferences:800,maxDependencies:800,maxDependents:800,maxTests:300,
  maxDocumentation:300,maxAffinityTargets:128,maxPropagationSeeds:64,
  maxPropagationClosureNodes:1024,maxPropagationReverseEdges:4096,maxPropagationDepth:16
});

for (const p of ["riftarchitecture/n3-contract-v1.json","riftarchitecture/n3-phase-authority.json"]) {
  assert.ok(sourceIntelligence.includes(`"${p}"`), "missing N3 machine-authority registration: " + p);
}

assert.ok(toolHost.includes('"rift_cli_project_intelligence" -> "workspace.projectIntelligenceReadOnly"'),
  "hidden N3 driver tool must map to the dedicated read-only sandbox method");
assert.ok(toolHost.includes('"rift_cli_project_intelligence"'),
  "hidden N3 driver tool must be registered internally");
assert.match(toolHost, /forbidden = setOf\([\s\S]*"rift_cli_project_intelligence"/,
  "Batch V2 must forbid the hidden N3 Project Intelligence tool");
const publicToolSection = toolHost.slice(toolHost.indexOf('fun tools()'), toolHost.indexOf('fun callAsync('));
assert.ok(!publicToolSection.includes('rift_cli_project_intelligence'),
  "hidden N3 Project Intelligence bridge must not appear in the public MCP tool catalog");
assert.ok(sandboxSource.includes('"workspace.projectIntelligenceReadOnly" -> projectIntelligenceReadOnly(args)'),
  "sandbox must expose the dedicated read-only N3 dispatch method");
assert.match(sandboxSource, /"candidate-impact" -> candidateImpact\(\)/,
  "N3 evidence bridge must use the canonical candidateImpact authority");
assert.match(sandboxSource, /"propagation" -> \{[\s\S]*projectPropagation\(path, query, limit\)/,
  "N3 evidence bridge must use the canonical bounded propagation authority");
for (const kind of ["contracts", "claims", "proofs"]) {
  assert.ok(toolHost.includes('"candidate-impact", "propagation", "contracts", "claims", "proofs"'),
    "hidden N3 bridge must freeze the full five-kind evidence allowlist");
  assert.ok(sandboxSource.includes('"contracts", "claims", "proofs" ->'),
    "hidden N3 bridge must route contracts/claims/proofs through the canonical project analyzers");
}
assert.match(sandboxSource, /projectOverview\(path, 240, kind, ""\)/,
  "contracts/claims/proofs must reuse canonical read-only Project Intelligence views");
assert.ok(!sandboxSource.includes('workspace.projectIntelligenceReadOnly" -> workspaceExec'),
  "N3 evidence bridge must never alias back to workspace.exec");

for (const [literal, expected] of [
  ["MAX_CANDIDATE_PROJECTS",32],["MAX_CANDIDATE_CHANGED_FILES",4096],["MAX_CANDIDATE_CHANGED_SYMBOLS",1000],
  ["MAX_CANDIDATE_REFERENCE_SYMBOLS",80],["MAX_CANDIDATE_REFERENCES",800],["MAX_CANDIDATE_DEPENDENCIES",800],
  ["MAX_CANDIDATE_DEPENDENTS",800],["MAX_CANDIDATE_TESTS",300],["MAX_CANDIDATE_DOCS",300],
  ["MAX_CANDIDATE_AFFINITY_TARGETS",128],["MAX_PROPAGATION_SEEDS",64],["MAX_PROPAGATION_CLOSURE_NODES",1024],
  ["MAX_PROPAGATION_CLOSURE_EDGES",4096],["MAX_PROPAGATION_DEPTH",16]
]) {
  const m = sandbox.match(new RegExp(`private const val ${literal} = ([0-9_]+)`));
  assert.ok(m, "missing substrate bound " + literal);
  assert.equal(Number(m[1].replaceAll("_","")), expected, literal + " drifted");
}

assert.deepEqual(contract.correctnessCorpus.map(x=>x.id), [
  "ambiguous-owner","api-change-propagates","dependency-cycle","cross-project-same-symbol",
  "build-config-change","security-boundary-change","stale-or-truncated-pi","docs-conflict-with-source",
  "memory-conflicts-with-source","bound-exhaustion","restart-determinism"
]);

for (const [k,v] of Object.entries(contract.zeroTolerance)) {
  if (k === "performanceComparativeBenchmarks") assert.equal(v, "DEFERRED_UNTIL_FULL_RIFTCLI_COMPLETE_AND_LIVE");
  else assert.equal(v, 0, "non-zero N3 zero-tolerance counter " + k);
}

assert.equal(phase.schema, "rift-architecture-n3-phase-authority-v1");
assert.match(phase.programStatus, /N3\.0 PROMOTED/);
assert.match(phase.programStatus, /hidden PI bridge PROMOTED on run 391/);
assert.match(phase.programStatus, /N3-M1 \(N3\.1\+N3\.2\) PROMOTED/);
assert.match(phase.programStatus, /N3-M2 \(N3\.3\+N3\.4\) READY/);
assert.match(phase.runtimeStatus, /N3-M1 LOCAL INTELLIGENCE PROMOTED IN \/workspace\/\.riftcli/);
assert.match(phase.runtimeStatus, /N3-M2 LOCAL IMPLEMENTATION READY/);
assert.match(phase.runtimeStatus, /N2 CANONICAL MEMORY RUNTIME REMAINS INACTIVE/);
assert.equal(phase.prerequisites.N3MachineAuthorityPrelude, "SATISFIED");
assert.equal(phase.phases.length, 7);
assert.equal(phase.phases[0].status, "promoted");
assert.equal(phase.phases[0].sourceSha, "001354552af5a0a8034af82a4341187a8abeda52");
assert.equal(String(phase.phases[0].builderRunNumber), "390");
assert.equal(phase.phases[1].status, "promoted");
assert.equal(phase.phases[2].status, "promoted");
assert.equal(phase.phases[1].packageVersion, "0.2.0");
assert.equal(phase.phases[2].packageVersion, "0.2.0");
assert.equal(phase.phases[1].packageModuleSha256, "9ff52a40d9fc1cf7c366ea30f50ed7bdf273bed0276eb41b9958b06ab052a4de");
assert.equal(phase.phases[2].semanticImpactSha256, "770f07853e190fc40b77751aa5dc0229e48a6b7d6404b09bddf8a4cf52429000");
assert.equal(phase.phases[2].propagationSha256, "1ce267e7aeff8680971c6e1e73960234ed2b74c49f96944c9d79064dd6e377c5");
assert.equal(phase.phases[3].status, "ready");
assert.equal(phase.phases[4].status, "ready");
assert.ok(phase.phases.slice(5).every(x=>x.status === "queued"));
assert.equal(phase.macroImplementationPlan[0].status, "promoted");
assert.equal(phase.macroImplementationPlan[1].status, "ready");
assert.equal(phase.macroImplementationPlan[2].status, "queued-after-n3-m2");
assert.equal(phase.n3M1LocalEvidence.packageVersion, "0.2.0");
assert.equal(phase.n3M1LocalEvidence.fileSha256["runtime/main.js"], "03c8d153716a6da9459b5f031d9506a6d291e5d6dff736e30499add43be73ff4");
assert.equal(phase.n3M1LocalEvidence.fileSha256["observer/n3-m1.js"], "9ff52a40d9fc1cf7c366ea30f50ed7bdf273bed0276eb41b9958b06ab052a4de");
assert.equal(phase.n3M1LocalEvidence.liveImpactSha256, "770f07853e190fc40b77751aa5dc0229e48a6b7d6404b09bddf8a4cf52429000");
assert.equal(phase.n3M1LocalEvidence.livePropagationSha256, "1ce267e7aeff8680971c6e1e73960234ed2b74c49f96944c9d79064dd6e377c5");
assert.deepEqual(phase.macroImplementationPlan.map(x=>x.phases), [["N3.1","N3.2"],["N3.3","N3.4"],["N3.5"]]);
assert.match(phase.macroPlanRule, /N3\.6 remains a separate final adversarial\/restart promotion gate/);

assert.equal(n2Phase.phases.at(-1)?.phase, "N2.12");
assert.equal(n2Phase.phases.at(-1)?.status, "promoted");
assert.match(n2Phase.runtimeStatus, /N2 CANONICAL MEMORY RUNTIME INACTIVE/);

for (const required of [
  "N3.0 CONTRACT/BASELINE PROMOTED",
  "N3 is analysis only",
  "N4 may consume N3 only after N3.6 promotion",
  "### N3.0 — Contract and baseline freeze",
  "### N3.6 — Adversarial / restart / final promotion gate",
  "Comparative/performance benchmarks remain deferred until the entire RiftCLI stack is complete and live"
]) assert.ok(roadmap.includes(required), "roadmap drift: " + required);

assert.ok(rootRoadmap.includes("FIRST-CLASS MCP BATCH PROMOTED"));

console.log("ok - N3.0 architecture/impact contract is frozen, bounded, analysis-only, and prerequisite-gated");
