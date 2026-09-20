import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const gradleSource=readFileSync('android/app/build.gradle.kts','utf8');
const nativeShellSource=readFileSync('android/app/src/main/java/com/riftos/app/RiftNativeShell.kt','utf8');
const entrySource=readFileSync('src/riftandroid-entry.js','utf8');
const compatShellSource=readFileSync('src/riftos.js','utf8');
const toolHostSource=readFileSync('android/app/src/main/java/com/riftos/app/RiftToolHost.kt','utf8');
const toolSandboxSource=readFileSync('android/app/src/main/java/com/riftos/app/RiftToolSandbox.kt','utf8');

assert.ok(!gradleSource.includes('riftshell-batch.js'),'disabled batch JS must not be packaged explicitly');
assert.ok(!entrySource.includes('import("./riftshell-batch.js")'),'Android entry must not load disabled batch runtime');
assert.ok(!/^\s*"batch"\s*->/m.test(nativeShellSource),'native RiftShell must not expose the retired batch command');
assert.ok(!compatShellSource.includes('RiftShellBatch.run'),'compat RiftShell must not execute the batch runtime');
assert.ok(compatShellSource.includes('batch [DISABLED - DO NOT USE]'),'compat help must label batch as disabled');
assert.ok(toolHostSource.includes('never call batch'),'MCP tool manifest must tell AI callers not to use batch');
assert.ok(toolHostSource.includes('shellCommandName == "batch"'),'MCP shell gateway must reject batch before execution');
assert.ok(toolHostSource.includes('.put("maxItems", 1)'),'MCP workspace schema must allow only one operation per call');
assert.ok(toolHostSource.includes('operationCount > 1'),'MCP workspace gateway must reject multi-op batches');
assert.ok(toolHostSource.includes('MULTI-OP/BATCH MODE IS DISABLED'),'MCP manifest must label multi-op batching disabled');
assert.ok(toolSandboxSource.includes('.put("multiOperationBatch", "DISABLED")'),'workspace metadata must report multi-op batch mode disabled');
assert.ok(toolSandboxSource.includes('.put("maxOperations", 1)'),'workspace metadata must advertise one operation maximum');

const context={window:{RiftOSCore:{}},console};
context.window.window=context.window;
Object.assign(context,context.window);
vm.createContext(context);
vm.runInContext(readFileSync('src/riftshell-batch.js','utf8'),context,{filename:'src/riftshell-batch.js'});

assert.equal(context.window.RiftShellBatch.disabled,true);
assert.equal(context.window.RiftShellBatch.status,'DISABLED');
await assert.rejects(()=>context.window.RiftShellBatch.run('write a b'),/DISABLED: RiftShell batch commands are disabled/);
assert.throws(()=>context.window.RiftShellBatch.split('write a b'),/DISABLED: RiftShell batch commands are disabled/);

console.log('ok - RiftShell batch is disabled at import, compat shell, MCP manifest, and MCP execution gate');
