# Rift++ V0 Language Contract

Rift++ V0 is the declarative language for the experimental RiftCLI swarm architecture. V0 is intentionally **not** a general-purpose programming language and **not executable**. It parses bounded text, validates a brain/team/task graph and compiles it into `rift.swarm-ir/0` JSON for inspection and coordinator preview. The separate `riftpp 1` Core bootstrap frontend now lives in `src/riftpp-core.js` and targets RiftVM `.rxe`; that executable Core path does **not** make V0 or RiftCLI executable.

## Design goals

Rift++ makes AI orchestration concepts first-class without giving scripts direct operating-system authority. The language describes backends, brains, specialist agents, permissions, swarm flow and tasks. Deterministic RiftOS authorities remain outside the language and can only be connected later through an explicitly promoted coordinator/runtime.

V0 accepts five declaration types: `backend`, `brain`, `agent`, `swarm`, and `task`. A complete V0 file must contain at least one of each and begin with `riftpp 0`.

```text
riftpp 0

backend RulesBrain {
  type rules
}

brain MainBrain {
  backend RulesBrain
  role lead
  memory project
  context 12000
}

agent Reviewer {
  role review
  tools [workspace.read, workspace.diff]
  allow [workspace.read, workspace.diff]
  deny [workspace.write, git.push]
  context 6000
}

swarm DevTeam {
  lead MainBrain
  workers [Reviewer]
  flow Reviewer -> MainBrain
  review 1
}

task ReviewOnly {
  swarm DevTeam
  goal "Review the proposed patch"
  retry 1
  require [review]
}
```

## Grammar surface

`backend` fields: `type` and optional `model`. V0 recognizes `rules`, `riftllm`, `remote`, and `mock` as declarative backend types, but all compile with `connected=false`.

`brain` fields: `backend`, optional `role`, optional `memory`, optional `context`. Memory is `none`, `session`, or `project`.

`agent` fields: `role`, optional `tools`, `allow`, `deny`, and `context`. Tools are capability names and every tool must also be explicitly allowed. Allow/deny overlap is rejected. `review` and `security` are compile-time read-only roles and cannot allow mutation capabilities.

`swarm` fields: `lead`, `workers`, repeatable `flow A -> B`, and optional `review`. Worker and lead references must exist, the selected brain must declare `role lead`, and the requested reviewer count cannot exceed the number of `review` workers. Flow endpoints must belong to the swarm and the flow graph must be acyclic. The compiler emits a deterministic topological `scheduleOrder`.

`task` fields: `swarm`, `goal`, optional `retry`, and optional `require`. Requirements such as `tests`, `security`, and `review` must have matching worker roles in the selected swarm.

Comments use `#` or `//`. Lists use `[a, b, c]`. Strings use double quotes with bounded `\\`, `\"`, `\n`, `\r`, and `\t` escapes. V0 has no imports, variables, functions, loops, arbitrary expressions, native calls, shell escapes, file writes or dynamic code execution.

## Capability vocabulary

V0 recognizes a finite capability set: workspace read/graph/diff/write; Git status/diff/commit/push; build plan/run; test run; docs read/write; browser inspect; device view/control; web research; and MCP read/write. These are declarations only in V0. Compilation never performs the capability.

Mutation capabilities are rejected for `review` and `security`: `workspace.write`, `git.commit`, `git.push`, `build.run`, `docs.write`, `device.control`, and `mcp.write`.

## Source confinement and limits

`rift-cli riftpp validate|compile|preview` reads only `.riftpp` or `.rift++` files inside RiftFS `workspace/`. Absolute `D:/Workspace/...` and `/workspace/...` aliases are normalized into that one root. Traversal, other drive roots and files larger than 128 KiB are rejected.

V0 limits declarations to 64, list values to 64 entries, context budgets to 65,536 tokens, retries to 5, and reviewer count to 8. Strings are bounded to 4,096 characters.

## Swarm IR

Successful compilation returns `rift.swarm-ir/0` containing source SHA-256, backends, brains, agents, swarms, flows, deterministic schedule order and tasks. The IR explicitly carries `experimental=true` and `executable=false`.

`rift.swarm-ir/0` remains the V0 frontend contract. The separate Rift IR V1 core may lower it into language-independent `rift.ir/1` profile `swarm-core`; this lowering does not make the source executable and does not replace V0 compatibility. See `RIFT_IR_V1.md`. RiftRT's separate `rift-exec-v1` / `riftvm-1` `.rxe` target belongs to the future Core-language bootstrap path; V0 does not emit or execute it.

`RiftSwarmCoordinatorV0.preview(...)` consumes this IR and emits `rift.swarm-preview/0`. It resolves a task, its swarm, lead brain/backend and ordered specialist assignments. Preview never invokes a backend or tool.

## BrainBackend interface

`RiftBrainBackend` is the native future-facing interface. It exposes backend identity/availability and a typed request/response boundary. V0 intentionally has no registered implementation and the coordinator never calls `respond(...)`. A later promoted phase can add backend implementations without changing Rift++ source syntax or Swarm IR identity.

## Shell commands

```text
rift-cli riftpp help
rift-cli riftpp sample
rift-cli riftpp validate <workspace-script.riftpp>
rift-cli riftpp compile <workspace-script.riftpp>
rift-cli riftpp preview <workspace-script.riftpp> [task-name]
```

`help` and `sample` are descriptive. Validate/compile/preview remain behind the RiftCLI experimental process-local enable gate.

## V0 non-goals

- no autonomous execution;
- no model invocation;
- no workspace mutation;
- no Git/build/tool invocation;
- no direct Local Agent invocation;
- no generic script runner;
- no arbitrary backend configuration or network endpoint in source;
- the V0 language/compiler/coordinator itself makes no changes to RiftLLM or its tokenizer/training stack. The separate, explicitly enabled `rift-cli tokenizer` development command family is a different subsystem surface documented by the parent Experimental RiftCLI README and is not authority granted by V0 source.
