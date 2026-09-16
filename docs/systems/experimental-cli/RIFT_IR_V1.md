# Rift IR V1 Contract

> **EXPERIMENTAL — INSPECTION ONLY — NON-EXECUTABLE.**

Rift IR V1 is the first language-independent intermediate representation for the future RiftCLI/Rift++ execution stack. Its schema is `rift.ir/1` with profile `swarm-core`.

Rift IR V1 does **not** replace `rift.swarm-ir/0`. Rift++ V0 continues compiling to its existing Swarm IR for compatibility. The new `RiftIrV1` lowering pass validates that Swarm IR again and translates it into the durable runtime-facing contract that future coordinators will consume.

## Why this layer exists

The long-term architecture is:

```text
Rift++ / future frontends
        ↓
frontend-specific validated representation
        ↓
Rift IR
        ↓
coordinator / planner
        ↓
BrainBackend
        ↓
capability gate
        ↓
deterministic RiftCLI authorities
```

Keeping the core IR separate from Rift++ syntax allows future producers such as a GUI graph editor, other Rift compilers, or a validated planner to target the same runtime contract without duplicating coordinator semantics.

## Current producer

The only V1 producer is the Rift++ V0 frontend:

```text
.riftpp
  ↓
RiftPlusPlusV0
  ↓
rift.swarm-ir/0
  ↓
RiftIrV1.lowerFromSwarmIr(...)
  ↓
rift.ir/1 profile=swarm-core
```

`RiftPlusPlusV0.compileWorkspace(...)` is the bounded workspace-only frontend entry used by the thin `RiftIrCliV1` adapter.

## Top-level contract

A current Rift IR document contains:

```text
schema          rift.ir/1
irVersion       1
profile         swarm-core
experimental    true
executable      false
identity
backends
actors
  brains
  agents
graphs
  swarms
tasks
resources
policy
execution
validation
compatibility
```

### Identity

Identity records the producer (`rift++/0`), original source schema (`rift.swarm-ir/0`), workspace source path and exact source SHA-256.

### Actors and backends

Brains and agents are normalized into `actors`. Brains reference declared backends. Agents retain role, context budget, tools, allow and deny capability sets. V1 independently revalidates role vocabulary, brain memory scopes, and these capability sets instead of trusting the frontend's validation flag. It also recomputes the exact declared-capability union from actor policy and rejects drift in the summary registry.

### Graphs

Each swarm graph records lead, workers, edges, reviewer requirement and a canonical deterministic schedule. Rift IR recomputes the topological schedule itself, rejects duplicate edges, and rejects a V0 source schedule that does not exactly match the graph.

### Tasks

Tasks record id, swarm, goal, bounded retry budget and required validation gates. Requirements are rechecked against actual worker roles in the selected swarm.

### Resources

V1 records actor/brain/agent/swarm/task counts plus declared context-token totals. This is the first resource-accounting surface; it does not yet allocate memory or model context at runtime.

### Policy

The `swarm-core` profile carries the current finite capability registry, declared capabilities, mutation classification and read-only roles. The IR validator independently enforces:

- tools must be allowed;
- allow and deny are disjoint;
- tools cannot be denied;
- `security` and `review` cannot receive mutating capability authority;
- `mutationAllowed=false`;
- `rawShellAllowed=false`;
- `arbitraryProcessAllowed=false`.

### Execution metadata

V1 is deliberately not an executor. It pins:

```text
mode                  inspect-only
scheduler             deterministic-topological-v1
defaultConcurrency    1
backendInvocation     false
toolInvocation        false
localAgentInvocation  false
mutation              false
```

These fields are architectural assertions, not dormant permissions. A future coordinator promotion must intentionally update the owning contract/tests rather than silently interpreting V1 as executable.

### Validation gates

Rift IR records the union of task requirements such as tests, security, review, performance, docs and build. V1 verifies that this set matches the actual tasks and that each role-backed requirement is satisfiable by the selected swarm.

## Defense-in-depth validation

`RiftIrV1.validate(...)` revalidates the IR itself. It checks schema/profile/version, exact source identity format, backend/actor references, context ranges, capability policy, graph membership and acyclicity, canonical schedules, reviewer cardinality, task requirements, resource totals, policy registries, execution-off invariants and compatibility metadata.

The validator must not trust `compilerValidated`, `graphAcyclic`, `scheduleValidated` or `policyValidated` merely because those flags are present.

## CLI development surface

The minimal development adapter is intentionally small:

```text
rift-cli ir help
rift-cli ir compile <workspace-script.riftpp>
rift-cli ir validate <workspace-script.riftpp>
rift-cli ir inspect <workspace-script.riftpp>
```

`help` is descriptive. Compile/validate/inspect require the same explicit process-local experimental RiftCLI enable gate as Rift++ compilation. There is **no** `run` command in this phase.

`compile` returns the full `rift.ir/1` document. `validate` returns `rift.ir-validation/1`. `inspect` returns a bounded `rift.ir-inspection/1` summary including counts, declared context total, validation gates and canonical schedules.

## Non-goals for this phase

- no BrainBackend invocation;
- no agent/model invocation;
- no tool execution;
- no Local Agent invocation;
- no workspace/Git/build mutation;
- no raw shell/process execution;
- no persistent task state;
- no parallel swarm runtime;
- no generalized systems/compute/kernel nodes yet;
- no direct `.riftir` source format or arbitrary IR file import.

## Future extension rule

New Rift++ syntax should first define what durable meaning it adds to Rift IR. Rift IR should grow through explicit version/profile changes rather than ad-hoc coordinator-only JSON. Future V1 swarm execution should consume validated `rift.ir/1`, not reach backward into parser internals.

## Failure signatures

- `rift.ir/1` accepts a cyclic or tampered schedule -> graph validation regressed.
- resource totals differ from actor context declarations -> accounting regressed.
- Security/Reviewer gains mutation authority -> policy validation regressed.
- compile/validate/inspect invokes a backend/tool/Local Agent or mutates state -> inspection-only boundary regressed.
- a new MCP tool is added for Rift IR -> layering regressed.
- Rift++ V0 stops emitting `rift.swarm-ir/0` merely because Rift IR exists -> compatibility boundary regressed.
- coordinator code starts consuming parser-private structures instead of Rift IR -> IR boundary regressed.

## Validation

Run `npm run check`. `scripts/test-rift-ir-v1.mjs` locks schema/profile identity, V0 compatibility, required IR sections, schedule recomputation, independent capability validation, non-executable execution policy, CLI gating, Gradle source inclusion and no MCP expansion. Native Kotlin changes still require the normal Android build before runtime promotion.
