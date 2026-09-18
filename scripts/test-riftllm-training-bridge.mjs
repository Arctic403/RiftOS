import fs from 'node:fs';
import assert from 'node:assert/strict';

const read=file=>fs.readFileSync(file,'utf8');
const runner=read('android/app/src/main/java/com/riftos/app/RiftTrainDataTaskRunner.kt');
const services=read('android/app/src/main/java/com/riftos/app/RiftNativeShellServices.kt');
const client=read('android/app/src/main/java/com/riftos/app/RiftLlmDevClient.kt');
const bridge=read('src/riftllm-bridge.js');
const gradle=read('android/app/build.gradle.kts');

assert.match(runner,/PACK_MAGIC = "RIFT_TRAIN_DATA_V1\\n"/);
assert.match(runner,/TOKENIZER_ID = "rift-token-b-balanced-v2"/);
assert.match(runner,/314e3a732d4cc4c31c40c9b0add3fffcec38c8a4b40e0d228bdc4eed1addbbd1/);
assert.match(runner,/b88b0ab8d3a7dc784e2e4b20d33b5c5fab222542880cea197f529d5c996e9a05/);
assert.match(runner,/9d442860e3ed407fe10f10e72ad41fabc2854cfc3cdcaeb654b35ddad506faba/);
assert.match(runner,/TRAIN_RELATIVE = "tokenizer\/private\/build-v2\/train"/);
assert.match(runner,/OUTPUT_RELATIVE = "training\/private\/canary-v1\/rift-train-data-v1\.rifttok"/);
assert.match(runner,/productionPretrainingEligible", false/);
assert.match(runner,/payloadEncoding", "sample-u32le-count-u16le-token-ids"/);
assert.match(runner,/boundaryPolicy", "bos-text-eos-v1"/);
assert.match(runner,/fun referenceEncode\(/);
assert.match(runner,/encoded\.contentEquals\(reference\)/);
assert.match(runner,/postBuildSourceSha == TOKENIZER_TRAINING_SHA/);
assert.match(runner,/StandardCopyOption\.ATOMIC_MOVE/);
assert.match(runner,/REMOTE_CHUNK_BYTES = 192 \* 1024/);
assert.doesNotMatch(runner,/ProcessBuilder|Runtime\.getRuntime|ServerSocket|DatagramSocket|HttpServer/);
assert.doesNotMatch(runner,/optString\("path"|getString\("path"/);

for(const method of ['train_data_begin','train_data_append','train_data_commit','train_data_status','train_canary_start','train_canary_status']){
  assert.ok(client.includes(`"${method}" to "${method}"`),`missing fixed training provider method ${method}`);
}
for (const pair of [
  ['train-data-status','"status"'], ['train-data-build','"build"'], ['train-data-build-status','"build-status"'],
  ['train-data-upload','"upload"'], ['train-data-remote-status','"remote-status"'],
  ['train-canary-start','"canary-start"'], ['train-canary-status','"canary-status"']
]) {
  assert.ok(services.includes(`"${pair[0]}" -> RiftTrainDataTaskRunner.execute`), `native shell service missing ${pair[0]}`);
  assert.ok(services.includes(`.put("op",${pair[1]})`), `native shell service op mismatch for ${pair[0]}`);
}
assert.match(gradle,/RiftTrainDataTaskRunner\.kt/);
assert.ok(!gradle.includes('riftllm-bridge.js'),'retained RiftLLM training bridge JavaScript must not be packaged');

for(const command of ['train-data-status','train-data-build','train-data-build-status','train-data-upload','train-data-remote-status','train-canary-start','train-canary-status']){
  assert.ok(bridge.includes(command),`missing fixed reference training command ${command}`);
}
assert.doesNotMatch(bridge,/train-data-build <path|train-canary-start <path|train-data-upload <path/);
assert.equal(fs.existsSync('android/app/src/main/java/com/riftos/app/RiftNativeDispatcher.kt'), false);

console.log('RiftLLM fixed training-data/canary native service contract OK');
