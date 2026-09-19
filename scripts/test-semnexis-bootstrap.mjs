import assert from 'node:assert/strict';
import { compileSemnexisV0, inspectSemnexisV0 } from '../src/semnexis-bootstrap.js';

const smokeSource = 'fn main() -> i32 {\n    return 40 + 2;\n}\n';
const expectedGraph = 'SEMNEXIS_PROGRAM_GRAPH_V0\n' +
  'node 0 Module root\n' +
  'node 1 Type i32 width=32 signed=true\n' +
  'node 2 Effect pure observable_effects=none\n' +
  'node 3 Effect time observable_effects=time\n' +
  'node 4 Capability time authority=clock\n' +
  'node 5 Intrinsic clock arity=0\n' +
  'node 6 Function main symbol=fn::main effect_proof=closed_pure_graph_v0 capability_proof=none_required_v0\n' +
  'node 7 Region main.local lifetime=function escape=false proof=no_reference_values_v0\n' +
  'node 8 Constant integer value=40\n' +
  'node 9 Constant integer value=2\n' +
  'node 10 Binary Plus operation=Plus\n' +
  'node 11 Return return\n' +
  'edge 5 -> 1 returns_type\n' +
  'edge 5 -> 3 has_effect\n' +
  'edge 5 -> 4 requires_capability\n' +
  'edge 0 -> 6 contains\n' +
  'edge 6 -> 1 returns_type\n' +
  'edge 6 -> 7 executes_in\n' +
  'edge 8 -> 1 has_type\n' +
  'edge 6 -> 8 contains_expr\n' +
  'edge 9 -> 1 has_type\n' +
  'edge 6 -> 9 contains_expr\n' +
  'edge 10 -> 8 lhs\n' +
  'edge 10 -> 9 rhs\n' +
  'edge 10 -> 1 has_type\n' +
  'edge 6 -> 10 contains_expr\n' +
  'edge 6 -> 11 contains\n' +
  'edge 11 -> 10 returns_value\n' +
  'edge 11 -> 1 has_type\n' +
  'edge 6 -> 2 has_effect\n';

const expectedPlan = 'SEMNEXIS_EXECUTION_PLAN_V0\n' +
  'step 0 enter_function main\n' +
  'step 1 create_region main.local\n' +
  'step 2 return main\n' +
  'step 3 destroy_region main.local\n' +
  'step 4 leave_function main\n';

const smoke = compileSemnexisV0(smokeSource);
assert.equal(smoke.graphText, expectedGraph);
assert.equal(smoke.planText, expectedPlan);

const symbols = 'fn add(a: i32, b: i32) -> i32 {\n' +
  '    return a + b;\n}\n\n' +
  'fn main() -> i32 {\n' +
  '    let base = 40;\n' +
  '    let answer = add(base, 2);\n' +
  '    return answer;\n}\n';
assert.deepEqual(inspectSemnexisV0(symbols).functions, ['add', 'main']);

const effectSource = 'fn sample_time() -> i32 {\n' +
  '    return clock();\n}\n\n' +
  'fn main() -> i32 with time {\n' +
  '    return sample_time();\n}\n';
const effect = compileSemnexisV0(effectSource);
assert.match(effect.graphText, /Function sample_time .*effect_proof=transitive_call_graph_v0 capability_proof=transitive_requirement_v0/);
assert.match(effect.graphText, /Function main .*effect_proof=transitive_call_graph_v0 capability_proof=satisfied_by_grant_v0/);

const rejects = [
  ['fn add(a: i32, b: i32) -> i32 { return a + b; }\nfn main() -> i32 { return add(1); }\n', /expects 2 argument/],
  ['fn main(a: i32) -> i32 { let a = 4; return a; }\n', /duplicate symbol/],
  ['fn sample_time() -> i32 { return clock(); }\nfn main() -> i32 { return sample_time(); }\n', /requires time/],
  ['fn main() -> i32 { return ; }\n', /expected expression/],
  ['fn main() -> i32 with network { return 0; }\n', /unknown capability/],
  ['fn main() -> i32 { return missing + 1; }\n', /unknown name/]
];
for (const [source, pattern] of rejects) assert.throws(() => compileSemnexisV0(source), pattern);

assert.throws(
  () => compileSemnexisV0('fn helper() -> i32 with time { return clock(); }\nfn main() -> i32 with time { return helper(); }\n'),
  /only entry function 'main' may grant capability/,
  'library functions must not mint ambient capability'
);

console.log('ok - Semnexis QuickJS bootstrap compiler');
