import { readFile, writeFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateReview, reviewerEnvironment, schemaPath } from './core.mjs';

// Keep the CLI transport host: stdio MCP reads need it. Model code execution stays disabled.
export const codexDisabledFeatures = ['shell_tool', 'hooks', 'apps', 'plugins', 'browser_use', 'computer_use', 'image_generation', 'view_image', 'multi_agent', 'js_repl', 'code_mode'];

export function providerCommand(provider, options) {
  const { model, directory, schema, output, server, repository, tips, node, mcpConfig } = options;
  if (!/^[A-Za-z0-9._:/-]{1,128}$/.test(model)) throw Error('Configure an explicit current provider model, without shell expressions');
  if (provider === 'codex') {
    const disabled = codexDisabledFeatures;
    return ['codex', 'exec', '--model', model, '--ephemeral', '--ignore-user-config', '--ignore-rules', '--sandbox', 'read-only', '--skip-git-repo-check', '--cd', directory, ...disabled.flatMap(feature => ['--disable', feature]), '-c', 'web_search="disabled"', '-c', 'mcp_servers.review_context.required=true', '-c', `mcp_servers.review_context.command=${JSON.stringify(node)}`, '-c', `mcp_servers.review_context.args=${JSON.stringify([server, repository, JSON.stringify(tips)])}`, '--output-schema', schema, '--output-last-message', output, '-'];
  }
  if (provider === 'claude') return ['claude', '-p', '--model', model, '--bare', '--restricted', '--strict-mcp-config', '--mcp-config', mcpConfig, '--tools', '', '--allowedTools', 'mcp__review_context__*', '--permission-mode', 'dontAsk', '--permission-prompts', 'none', '--disable-slash-commands', '--no-chrome', '--no-session-persistence', '--output-format', 'json', '--json-schema', readFileSync(schema, 'utf8')];
  throw Error('Select exactly one provider: codex or claude');
}
export function normalizeProvider(provider, text) {
  let data = JSON.parse(text);
  if (provider === 'claude') {
    if (data.is_error || !data.structured_output) throw Error('Claude review incomplete or missing structured output');
    data = data.structured_output;
  }
  return validateReview(data);
}
export async function executeProvider(args, { cwd, env, input, timeoutSeconds = 600 }) {
  return new Promise((resolve, reject) => {
    const child = spawn(args[0], args.slice(1), { cwd, env, stdio: ['pipe', 'pipe', 'pipe'], detached: process.platform !== 'win32' });
    let stdout = '', size = 0, timedOut = false, oversized = false;
    const stop = () => { try { if (process.platform === 'win32') child.kill('SIGKILL'); else process.kill(-child.pid, 'SIGKILL'); } catch { /* Already exited. */ } };
    const timer = setTimeout(() => { timedOut = true; stop(); }, timeoutSeconds * 1000);
    child.stdout.on('data', bytes => { size += bytes.length; if (size > 4 * 1024 * 1024) { oversized = true; stop(); } else stdout += bytes.toString(); });
    child.stderr.on('data', bytes => { size += bytes.length; if (size > 4 * 1024 * 1024) { oversized = true; stop(); } });
    child.stdin.on('error', () => {});
    child.on('error', () => { clearTimeout(timer); reject(Error(`${args[0]} unavailable; install/authenticate the configured CLI or explicitly accept this exact review key`)); });
    child.on('close', code => {
      clearTimeout(timer);
      if (code !== 0 || timedOut || oversized) reject(Error(`${args[0]} review incomplete (${timedOut ? 'timeout' : oversized ? 'output bound exceeded' : `exit ${code}`}); check provider authentication/service and retry`));
      else resolve(stdout);
    });
    child.stdin.end(input);
  });
}
export async function runProvider({ provider, model, directory, repository, tips, prompt, env = process.env, timeoutSeconds = 600, execute = executeProvider }) {
  if (provider === 'claude' && !env.ANTHROPIC_API_KEY) throw Error('Claude bare review requires ANTHROPIC_API_KEY; configure it only for the review process');
  const schema = join(directory, 'review-schema.json'), output = join(directory, 'review-result.json'), mcpConfig = join(directory, 'context.json');
  const server = fileURLToPath(new URL('./context-server.mjs', import.meta.url));
  await writeFile(schema, await readFile(schemaPath), { mode: 0o600 });
  await writeFile(mcpConfig, JSON.stringify({ mcpServers: { review_context: { command: process.execPath, args: [server, repository, JSON.stringify(tips)] } } }), { mode: 0o600 });
  const args = providerCommand(provider, { model, directory, schema, output, server, repository, tips, node: process.execPath, mcpConfig });
  const text = await execute(args, { cwd: directory, env: reviewerEnvironment(env, provider), input: prompt, timeoutSeconds });
  return normalizeProvider(provider, provider === 'codex' ? await readFile(output, 'utf8') : text);
}
