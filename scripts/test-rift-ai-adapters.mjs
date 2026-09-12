import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync('android/app/src/main/assets/adapters/ai-adapter-registry.js', 'utf8');

function registryFor(hostname) {
  const context = { window: {}, location: { hostname } };
  vm.createContext(context);
  vm.runInContext(source, context);
  return context.window.RiftAIAdapters;
}

const expected = new Map([
  ['chatgpt.com', 'chatgpt'],
  ['gemini.google.com', 'gemini'],
  ['www.google.com', 'google'],
  ['claude.ai', 'claude'],
  ['copilot.microsoft.com', 'copilot'],
  ['github.com', 'copilot']
]);

for (const [host, name] of expected) {
  const registry = registryFor(host);
  const adapter = registry.current();
  assert.equal(registry.resolve(host), name, `${host} should resolve to ${name}`);
  assert.equal(adapter.name, name);
  for (const field of ['composer', 'send', 'stop', 'assistant', 'user']) {
    assert.ok(Array.isArray(adapter[field]) && adapter[field].length, `${name}.${field} must expose selectors`);
  }
}

assert.ok(registryFor('gemini.google.com').current().assistant.includes('model-response'));
assert.ok(registryFor('chatgpt.com').current().assistant.includes('[data-message-author-role="assistant"]'));
console.log('ok - multi-AI adapter registry resolves complete DOM contracts');
