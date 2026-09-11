import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync('android/app/src/main/assets/riftbrowser-mcp-app.js', 'utf8');

function extractFunction(name) {
  const needle = `function ${name}(`;
  const start = source.indexOf(needle);
  if (start < 0) throw new Error(`Missing ${name}`);
  const brace = source.indexOf('{', start);
  let depth = 0;
  let quote = '';
  let escaped = false;
  for (let i = brace; i < source.length; i += 1) {
    const ch = source[i];
    if (escaped) { escaped = false; continue; }
    if (quote) {
      if (ch === '\\') escaped = true;
      else if (ch === quote) quote = '';
      continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue; }
    if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  throw new Error(`Unclosed ${name}`);
}

const names = [
  'splitRawTokens',
  'parseRawScalar',
  'setRawPath',
  'parseRawCallBlock',
  'parseRawCallEnvelopes',
  'formatRawValue',
  'rawResultMessage'
];
const context = {
  CALL_OPEN: '[RIFT_CALL]',
  CALL_CLOSE: '[RIFT_END]',
  RESULT_MARKER: '[RIFT_RESULT]',
  MAX_RESULT_CHARS: 48000
};
vm.createContext(context);
vm.runInContext(names.map(extractFunction).join('\n\n'), context);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const simple = context.parseRawCallEnvelopes(`[RIFT_CALL]\ncall list-1 rift_list\nset path workspace/RiftOS-main\nset recursive true\n[RIFT_END]`);
assert(simple.errors.length === 0 && !simple.incomplete, 'simple command should parse');
assert(simple.calls[0].call_id === 'list-1', 'call id should survive');
assert(simple.calls[0].name === 'rift_list', 'tool name should survive');
assert(simple.calls[0].args.recursive === true, 'boolean should be typed');

const nested = context.parseRawCallEnvelopes(`[RIFT_CALL]\ncall batch-1 rift_workspace_exec\nset finish false\nset operations.0.op read_range\nset operations.0.path "workspace/My Project/Main.java"\nset operations.0.startLine 10\nset operations.0.endLine 40\nset operations.1.op write\nset operations.1.path workspace/out.txt\nset operations.1.text <<RIFT_TEXT\nhello\nworld\nRIFT_TEXT\n[RIFT_END]`);
assert(nested.errors.length === 0 && !nested.incomplete, 'nested command should parse');
assert(Array.isArray(nested.calls[0].args.operations), 'numeric dotted paths should create arrays');
assert(nested.calls[0].args.operations[0].path === 'workspace/My Project/Main.java', 'quoted string should preserve spaces');
assert(nested.calls[0].args.operations[1].text === 'hello\nworld', 'heredoc should preserve multiline text');

const partial = context.parseRawCallEnvelopes(`[RIFT_CALL]\ncall write-2 rift_write_text\nset text <<END\nabc\n[RIFT_END]`);
assert(partial.incomplete, 'missing heredoc delimiter should be incomplete');

const rendered = context.rawResultMessage({
  result_id: 'result-1', call_id: 'list-1', name: 'rift_list', ok: true, final: false,
  result: { path: 'workspace', entries: [{ name: 'a.java', size: 12 }] }
});
assert(rendered.includes('[RIFT_RESULT]'), 'result marker should be present');
assert(rendered.includes('status: ok'), 'result status should be plain text');
assert(!rendered.includes('"result_id"'), 'result should not be JSON');

const huge = context.rawResultMessage({ result_id: 'r', call_id: 'c', name: 'rift_read_text', ok: true, result: 'x'.repeat(60000) });
assert(huge.includes('Rift result truncated'), 'large result should be bounded');
assert(huge.length < 50000, 'bounded result should stay below the composer ceiling budget');

console.log('ok - raw Rift command parser');
console.log('ok - nested dotted paths and heredocs');
console.log('ok - plain bounded Rift results');
