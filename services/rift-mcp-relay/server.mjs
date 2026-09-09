import { createServer } from 'node:http';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { createMcpHandler, McpServer } from '@modelcontextprotocol/server';
import { toNodeHandler } from '@modelcontextprotocol/node';
import { WebSocketServer, WebSocket } from 'ws';
import * as z from 'zod/v4';

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const PAIRING_KEY = String(process.env.RIFT_PAIRING_KEY || '');
const CALL_TIMEOUT_MS = Number(process.env.RIFT_TOOL_TIMEOUT_MS || 20_000);

if (!/^[A-Za-z0-9_-]{24,128}$/.test(PAIRING_KEY)) {
  throw new Error('RIFT_PAIRING_KEY must be a 24-128 character base64url-style secret');
}

let deviceSocket = null;
const pending = new Map();

function constantTimeEqual(a, b) {
  const left = Buffer.from(String(a || ''));
  const right = Buffer.from(String(b || ''));
  return left.length === right.length && timingSafeEqual(left, right);
}

function rejectPending(message) {
  for (const [id, entry] of pending) {
    clearTimeout(entry.timer);
    entry.reject(new Error(message));
    pending.delete(id);
  }
}

function callDevice(name, args = {}) {
  if (!deviceSocket || deviceSocket.readyState !== WebSocket.OPEN) {
    return Promise.reject(new Error('RiftOS device is not connected to the relay'));
  }
  const id = randomUUID();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`Rift tool ${name} timed out`));
    }, CALL_TIMEOUT_MS);
    pending.set(id, { resolve, reject, timer });
    deviceSocket.send(JSON.stringify({ type: 'tool_call', id, name, args }));
  });
}

function textResult(value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
  return { content: [{ type: 'text', text: text ?? 'null' }] };
}

function errorResult(error) {
  return {
    isError: true,
    content: [{ type: 'text', text: String(error?.message || error || 'Unknown Rift tool error') }]
  };
}

async function run(name, args = {}) {
  try {
    return textResult(await callDevice(name, args));
  } catch (error) {
    return errorResult(error);
  }
}

const mcpHandler = createMcpHandler(() => {
  const server = new McpServer(
    { name: 'riftos-sandbox', version: '0.1.0' },
    {
      instructions: 'Operate only on the paired RiftOS app-private sandbox. Paths are sandbox-relative. Prefer read operations before writes and never invent filesystem results.'
    }
  );

  server.registerTool('rift_info', {
    description: 'Inspect the paired RiftOS sandbox capabilities and storage information.',
    inputSchema: z.object({}),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  }, async () => run('info'));

  server.registerTool('rift_stat', {
    description: 'Get metadata for one sandbox-relative file or directory path.',
    inputSchema: z.object({ path: z.string().describe('Sandbox-relative path, or an empty string for the sandbox root.') }),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  }, async ({ path }) => run('stat', { path }));

  server.registerTool('rift_list', {
    description: 'List files and folders under a sandbox-relative directory.',
    inputSchema: z.object({
      path: z.string().default('').describe('Sandbox-relative directory path.'),
      recursive: z.boolean().default(false).describe('Whether to recursively include descendants.')
    }),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  }, async ({ path, recursive }) => run('list', { path, recursive }));

  server.registerTool('rift_read_text', {
    description: 'Read a UTF-8 text file from the RiftOS sandbox.',
    inputSchema: z.object({ path: z.string().min(1).describe('Sandbox-relative file path.') }),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  }, async ({ path }) => run('readText', { path }));

  server.registerTool('rift_write_text', {
    description: 'Create or replace a UTF-8 text file inside the RiftOS sandbox.',
    inputSchema: z.object({
      path: z.string().min(1).describe('Sandbox-relative file path.'),
      text: z.string().describe('Complete UTF-8 file contents to write.')
    }),
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false }
  }, async ({ path, text }) => run('writeText', { path, text }));

  server.registerTool('rift_mkdir', {
    description: 'Create a directory inside the RiftOS sandbox, including missing parent directories.',
    inputSchema: z.object({ path: z.string().min(1).describe('Sandbox-relative directory path.') }),
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false }
  }, async ({ path }) => run('mkdir', { path }));

  server.registerTool('rift_remove', {
    description: 'Delete one sandbox-relative file or directory. Directories are removed recursively. The sandbox root cannot be deleted.',
    inputSchema: z.object({ path: z.string().min(1).describe('Sandbox-relative file or directory path.') }),
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false }
  }, async ({ path }) => run('remove', { path }));

  server.registerTool('rift_move', {
    description: 'Move or rename a sandbox-relative file or directory.',
    inputSchema: z.object({
      from: z.string().min(1).describe('Existing sandbox-relative source path.'),
      to: z.string().min(1).describe('Sandbox-relative destination path.'),
      overwrite: z.boolean().default(false).describe('Whether an existing destination may be replaced.')
    }),
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false }
  }, async ({ from, to, overwrite }) => run('move', { from, to, overwrite }));

  return server;
});

const nodeMcpHandler = toNodeHandler(mcpHandler);
const wsServer = new WebSocketServer({ noServer: true });

wsServer.on('connection', socket => {
  if (deviceSocket && deviceSocket.readyState === WebSocket.OPEN) {
    deviceSocket.close(4001, 'Replaced by a newer RiftOS device connection');
  }
  deviceSocket = socket;
  socket.send(JSON.stringify({ type: 'relay_ready', protocol: 'rift-mcp-device-v1' }));

  socket.on('message', data => {
    let message;
    try { message = JSON.parse(data.toString()); } catch { return; }
    if (message?.type !== 'tool_result' || typeof message.id !== 'string') return;
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id);
    clearTimeout(entry.timer);
    if (message.ok) entry.resolve(message.value ?? null);
    else entry.reject(new Error(String(message.error || 'RiftOS tool call failed')));
  });

  socket.on('close', () => {
    if (deviceSocket === socket) deviceSocket = null;
    rejectPending('RiftOS device disconnected from the relay');
  });

  socket.on('error', () => {
    if (deviceSocket === socket) deviceSocket = null;
    rejectPending('RiftOS device relay connection failed');
  });
});

const httpServer = createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (url.pathname === '/healthz') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, deviceConnected: deviceSocket?.readyState === WebSocket.OPEN }));
    return;
  }
  if (url.pathname === `/mcp/${PAIRING_KEY}`) {
    req.url = '/mcp';
    nodeMcpHandler(req, res);
    return;
  }
  res.writeHead(404, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ error: 'not_found' }));
});

httpServer.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (url.pathname !== '/device' || !constantTimeEqual(url.searchParams.get('key'), PAIRING_KEY)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  wsServer.handleUpgrade(req, socket, head, ws => wsServer.emit('connection', ws, req));
});

httpServer.listen(PORT, HOST, () => {
  console.error(`[rift-mcp-relay] listening on ${HOST}:${PORT}`);
  console.error('[rift-mcp-relay] MCP endpoint is /mcp/<pairing-key>; device websocket is /device?key=<pairing-key>');
});

function shutdown() {
  rejectPending('Rift MCP relay is shutting down');
  if (deviceSocket?.readyState === WebSocket.OPEN) deviceSocket.close(1001, 'Server shutdown');
  wsServer.close();
  httpServer.close(() => process.exit(0));
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
