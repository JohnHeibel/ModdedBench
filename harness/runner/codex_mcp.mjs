/** Persistent MCP stdio connection for an already-running Codex execution session.
 * The normal server, tool schemas, hot reload and interrupt supervisor are used.
 * This does not register new top-level tools in an existing Codex conversation.
 */
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

export async function connectModbench({ python = 'python', userSite = '' } = {}) {
  const server = fileURLToPath(new URL('../mcp/server.py', import.meta.url));
  // Some execution hosts omit APPDATA, so Python cannot discover packages
  // installed in its user site. An explicitly observed site path can restore it.
  const launcher = 'import os,runpy,sys,site; os.environ["MB_BRIDGE_URL"]="ws://127.0.0.1:47223/ws"; p=sys.argv[1]; site.addsitedir(sys.argv[2]) if sys.argv[2] else None; sys.argv=[p,"--profile","gtnh"]; runpy.run_path(p,run_name="__main__")';
  const child = spawn(python, ['-u', '-c', launcher, server, userSite], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  const pending = new Map();
  let nextId = 0, ended = false, logs = '';
  const lines = createInterface({ input: child.stdout });
  function failAll(error) {
    ended = true;
    for (const waiter of pending.values()) { clearTimeout(waiter.timer); waiter.reject(error); }
    pending.clear();
  }
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', value => { logs = (logs + value).slice(-8192); });
  child.on('error', failAll);
  child.stdin.on('error', failAll);
  child.on('exit', (code, signal) => failAll(new Error(`ModdedBench MCP exited (${code ?? signal})`)));
  lines.on('line', line => {
    let message;
    try { message = JSON.parse(line); }
    catch { failAll(new Error('Invalid JSON from MCP server')); child.kill(); return; }
    if ('method' in message) {
      // We advertise no client sampling or roots capabilities.
      if ('id' in message) child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Client method unavailable' } }) + '\n');
      return;
    }
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id); clearTimeout(waiter.timer);
    if (message.error) waiter.reject(new Error(JSON.stringify(message.error)));
    else waiter.resolve(message.result);
  });
  function request(method, params = {}, timeoutMs = 30000) {
    if (ended) return Promise.reject(new Error('ModdedBench MCP connection closed'));
    const id = ++nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/cancelled', params: { requestId: id, reason: 'Execution request deadline' } }) + '\n');
        reject(new Error(`MCP deadline elapsed for ${method}; inspect state before retrying actions`));
      }, timeoutMs);
      pending.set(id, { resolve, reject, timer });
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }
  try {
    const initialized = await request('initialize', { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'codex-modbench-session', version: '1.0' } });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
    return {
      initialized,
      listTools: () => request('tools/list'),
      call: (name, args = {}, timeoutMs = 30000) => request('tools/call', { name, arguments: args }, timeoutMs),
      get connected() { return !ended; },
      get logs() { return logs; },
      close() { child.stdin.end(); },
    };
  } catch (error) { error.logs = logs; child.kill(); throw error; }
}
