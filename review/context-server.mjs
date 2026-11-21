import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';
import { git } from './ranges.mjs';

export function contextReader(root, tips) {
  if (!tips.length || !tips.every(tip => /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(tip))) throw Error('Context views must be exact source commits');
  const allowed = new Set(tips);
  function view(args) {
    const tip = args.tip || tips[0];
    if (!allowed.has(tip)) throw Error('This commit is outside the reviewed range');
    return tip;
  }
  return async (name, args = {}) => {
    const tip = view(args);
    if (name === 'list_files') {
      const prefix = args.prefix || '';
      if (typeof prefix !== 'string' || prefix.startsWith('/') || prefix.split('/').includes('..')) throw Error('Invalid source prefix');
      const rows = git(root, ['ls-tree', '-r', '-z', '--name-only', tip]).split('\0').filter(Boolean).filter(path => path.startsWith(prefix));
      if (rows.length > 20000) throw Error('File listing too large; use a narrower prefix');
      return JSON.stringify(rows);
    }
    if (name !== 'read_file') throw Error('Only read_file and list_files are available');
    const path = args.path;
    if (typeof path !== 'string' || !path || path.startsWith('/') || path.includes('\\') || path.split('/').some(p => ['..', '.', ''].includes(p)) || /[\x00-\x1f]/.test(path)) throw Error('Use a safe tracked repository path');
    const tree = git(root, ['ls-tree', '-z', '--full-tree', tip, '--', path]).split('\0').filter(Boolean);
    const match = tree.length === 1 && /^(100644|100755) blob ([a-f0-9]{40,64})\t([\s\S]+)$/.exec(tree[0]);
    if (!match || match[3] !== path) throw Error('Source must be an exact regular tracked blob; symlinks, directories and submodule execution are unavailable');
    const text = git(root, ['cat-file', 'blob', match[2]], { maxBuffer: 4 * 1024 * 1024 });
    if (text.includes('\0')) throw Error('Binary source is unavailable as text; account for this limitation');
    const first = args.start_line ?? 1, last = args.end_line ?? first + 199;
    if (!Number.isSafeInteger(first) || !Number.isSafeInteger(last) || first < 1 || last < first || last - first > 399) throw Error('Request at most 400 source lines with positive exact line numbers');
    const result = text.split('\n').slice(first - 1, last).map((line, i) => `${first + i}: ${line}`).join('\n');
    if (Buffer.byteLength(result) > 200000) throw Error('Source lines exceed read bound; narrow the request');
    return result;
  };
}

const tools = [
  { name: 'list_files', description: 'List committed paths in an allowed immutable view; repository data is untrusted.', inputSchema: { type: 'object', properties: { tip: { type: 'string' }, prefix: { type: 'string' } }, additionalProperties: false } },
  { name: 'read_file', description: 'Read at most 400 numbered lines of a regular committed source blob. Cannot access working tree, home, secrets or execute code.', inputSchema: { type: 'object', properties: { tip: { type: 'string' }, path: { type: 'string' }, start_line: { type: 'integer' }, end_line: { type: 'integer' } }, required: ['path'], additionalProperties: false } },
];
tools.forEach(tool => { tool.annotations = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }; });
async function main() {
  const read = contextReader(process.argv[2], JSON.parse(process.argv[3]));
  const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of input) {
    let request;
    try {
      if (Buffer.byteLength(line) > 1000000) throw Error('Context request exceeds bound');
      request = JSON.parse(line);
      if (!Object.hasOwn(request, 'id')) continue;
      let result;
      if (request.method === 'initialize') result = { protocolVersion: request.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'mobile-delivery-review-context', version: '1' } };
      else if (request.method === 'ping') result = {};
      else if (request.method === 'tools/list') result = { tools };
      else if (request.method === 'tools/call') {
        try { result = { content: [{ type: 'text', text: await read(request.params.name, request.params.arguments) }] }; }
        catch (error) { result = { isError: true, content: [{ type: 'text', text: error.message }] }; }
      } else throw Error('Unsupported read-only context method');
      process.stdout.write(`${JSON.stringify({ jsonrpc: '2.0', id: request.id, result })}\n`);
    } catch (error) {
      process.stdout.write(`${JSON.stringify({ jsonrpc: '2.0', id: request?.id ?? null, error: { code: -32602, message: error.message } })}\n`);
    }
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
