import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { decision, validateReview, reviewKey, reviewerEnvironment } from '../review/core.mjs';
import { outgoing, gitContext } from '../review/ranges.mjs';
import { providerCommand, normalizeProvider, runProvider } from '../review/providers.mjs';
import { installHook, uninstallHook } from '../review/hooks.mjs';
import { contextReader } from '../review/context-server.mjs';
import { prePush, acceptReview } from '../review/cli.mjs';

const clean = () => ({ schema: 1, complete: true, summary: 'Contained reversible change.', findings: [] });
const finding = blocksPush => ({ priority: 'P1', title: 'Changes persistence semantics', path: 'app.txt', line: 1, anchor: 'hello', evidence: 'Old readers interpret the new storage differently.', impact: 'Existing records become unreadable.', correction: 'Version the storage and add a compatible reader.', risk: { blocksPush, categories: blocksPush ? ['storage-api-protocol'] : [], affectedSurface: 'Saved records', reversibility: blocksPush ? 'irreversible' : 'reversible', mitigation: 'Compatible reader and tested rollback.', reason: blocksPush ? 'Rollback cannot restore overwritten records.' : 'Contained behavior has a tested rollback.' } });
const git = (root, ...args) => execFileSync('git', ['-C', root, ...args], { env: { ...process.env, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' }, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
async function repository(fn) {
  const root = await mkdtemp(join(tmpdir(), 'review-fixture-'));
  try {
    git(root, 'init', '-b', 'trunk'); git(root, 'config', 'user.email', 'fixture@example.test'); git(root, 'config', 'user.name', 'Fixture');
    await writeFile(join(root, 'app.txt'), 'hello\n'); git(root, 'add', '.'); git(root, 'commit', '-m', 'Initial');
    const base = git(root, 'rev-parse', 'HEAD');
    await writeFile(join(root, 'app.txt'), 'hello next\n'); git(root, 'commit', '-am', 'Next');
    const tip = git(root, 'rev-parse', 'HEAD');
    await fn({ root, base, tip });
  } finally { await rm(root, { recursive: true, force: true }); }
}

test('risk decision is independent from priority and incomplete or malformed output never passes', () => {
  assert.equal(decision(validateReview(clean())), 'clean');
  assert.equal(decision(validateReview({ ...clean(), findings: [finding(false)] })), 'advisory');
  assert.equal(decision(validateReview({ ...clean(), findings: [{ ...finding(true), priority: 'P3' }] })), 'blocked');
  assert.equal(decision(validateReview({ ...clean(), complete: false })), 'incomplete');
  for (const bad of [{ ...clean(), complete: 'true' }, { ...clean(), extra: true }, { ...clean(), findings: [{ ...finding(true), line: 0 }] }, { ...clean(), findings: [{ ...finding(true), risk: { ...finding(true).risk, reason: '' } }] }]) assert.throws(() => validateReview(bad));
});

test('cache and scoped acceptance keys change with content refs model and rubric', () => {
  const input = { remote: 'origin', updates: [{ localRef: 'refs/heads/topic', head: 'a'.repeat(40), remoteRef: 'refs/heads/topic', base: 'b'.repeat(40) }], provider: 'codex', model: 'configured-model', cliVersion: 'fixture', contract: 'v1' };
  const key = reviewKey(input);
  assert.equal(key, reviewKey(structuredClone(input)));
  for (const altered of [{ ...input, model: 'new-model' }, { ...input, contract: 'v2' }, { ...input, updates: [{ ...input.updates[0], head: 'c'.repeat(40) }] }, { ...input, remote: 'another-remote' }]) assert.notEqual(key, reviewKey(altered));
});

test('exact outgoing tip differs from dirty working tree and multiple refs do not truncate commits', async () => repository(async ({ root, base, tip }) => {
  await writeFile(join(root, 'app.txt'), 'dirty data never reviewed\n');
  const plans = outgoing(`refs/heads/topic ${tip} refs/heads/topic ${base}\nrefs/heads/other ${tip} refs/heads/other ${base}\n`);
  const context = await gitContext(root, plans, 'origin');
  assert.equal(context.updates.length, 2);
  assert.match(context.prompt, /hello next/);
  assert.doesNotMatch(context.prompt, /dirty data never reviewed/);
  assert.deepEqual(context.updates[0].added, [tip]);
  assert.equal(git(root, 'rev-parse', 'HEAD'), tip);
}));

test('initial creation deletion rewrite and missing bases are explicit', async () => repository(async ({ root, base, tip }) => {
  const zero = '0'.repeat(40);
  const initial = await gitContext(root, outgoing(`refs/heads/topic ${tip} refs/heads/topic ${zero}\n`), 'origin');
  assert.equal(initial.updates[0].kind, 'initial');
  assert.equal(initial.updates[0].added.length, 2);
  const deletion = await gitContext(root, outgoing(`(delete) ${zero} refs/heads/topic ${tip}\n`), 'origin');
  assert.equal(deletion.updates[0].kind, 'deletion');
  git(root, 'checkout', '--detach', base);
  await writeFile(join(root, 'app.txt'), 'alternative\n'); git(root, 'commit', '-am', 'Rewrite');
  const rewritten = git(root, 'rev-parse', 'HEAD');
  const rewrite = await gitContext(root, outgoing(`refs/heads/topic ${rewritten} refs/heads/topic ${tip}\n`), 'origin');
  assert.equal(rewrite.updates[0].kind, 'rewrite');
  assert.deepEqual(rewrite.updates[0].removed, [tip]);
  await assert.rejects(gitContext(root, outgoing(`refs/heads/topic ${tip} refs/heads/topic ${'c'.repeat(40)}\n`), 'missing-remote'), /base|fetch/i);
}));

test('both providers receive only the strict contract and read-only context tools without delivery credentials', () => {
  const env = reviewerEnvironment({ PATH: '/usr/bin', HOME: '/local/home', OPENAI_API_KEY: 'selected-key', ANTHROPIC_API_KEY: 'other-key', GH_TOKEN: 'github-secret', ASC_PRIVATE_KEY: 'apple-secret', IOS_PROFILE_BASE64: 'signing-secret', GIT_DIR: '/wrong/repository' }, 'codex');
  assert.equal(env.OPENAI_API_KEY, 'selected-key');
  for (const key of ['ANTHROPIC_API_KEY', 'GH_TOKEN', 'ASC_PRIVATE_KEY', 'IOS_PROFILE_BASE64', 'GIT_DIR']) assert.equal(env[key], undefined);
  const options = { model: 'configured-model', directory: '/isolated', schema: fileURLToPath(new URL('../review/schema.json', import.meta.url)), output: '/result.json', server: '/trusted/context.mjs', repository: '/source', tips: ['a'.repeat(40)], node: '/usr/bin/node', mcpConfig: '/context.json' };
  const codex = providerCommand('codex', options);
  assert.ok(codex.includes('read-only')); assert.ok(codex.includes('shell_tool')); assert.ok(codex.includes('view_image')); assert.ok(codex.includes('--ignore-user-config'));
  const claude = providerCommand('claude', options);
  assert.ok(claude.includes('--restricted')); assert.ok(claude.includes('--strict-mcp-config'));
  assert.deepEqual(normalizeProvider('codex', JSON.stringify(clean())), clean());
  assert.deepEqual(normalizeProvider('claude', JSON.stringify({ is_error: false, structured_output: clean() })), clean());
  for (const bad of ['{"complete":', JSON.stringify({ is_error: true, structured_output: clean() })]) assert.throws(() => normalizeProvider('claude', bad));
});

test('hook installation composes existing Beads behavior and uninstall restores exact prior configuration', async () => repository(async ({ root }) => {
  const hooks = join(root, '.githooks'); await mkdir(hooks);
  const original = '#!/bin/sh\n# Beads fixture\ncat > "$PWD/prior-input"\nprintf "%s\\n" "$*" > "$PWD/prior-args"\n';
  await writeFile(join(hooks, 'pre-push'), original, { mode: 0o755 });
  await writeFile(join(hooks, 'post-commit'), '#!/bin/sh\nexit 0\n', { mode: 0o755 });
  git(root, 'config', '--local', 'core.hooksPath', '.githooks');
  const cli = join(root, 'fake-review.mjs');
  await writeFile(cli, `import {writeFileSync} from 'node:fs'; let input=''; for await (const chunk of process.stdin) input+=chunk; writeFileSync('review-input', input); writeFileSync('review-args', process.argv.slice(2).join(' '));`);
  await installHook(root, cli);
  const installed = git(root, 'config', '--local', 'core.hooksPath');
  assert.notEqual(installed, '.githooks');
  assert.match(await readFile(join(installed, 'pre-push'), 'utf8'), /pre-push/);
  assert.match(await readFile(join(installed, 'post-commit'), 'utf8'), /post-commit/);
  await installHook(root, cli);
  assert.equal(git(root, 'config', '--local', 'core.hooksPath'), installed);
  execFileSync(join(installed, 'pre-push'), ['origin', 'ssh://example.test/repo'], { cwd: root, input: 'exact protocol input\n' });
  assert.equal(await readFile(join(root, 'prior-input'), 'utf8'), 'exact protocol input\n');
  assert.equal(await readFile(join(root, 'review-input'), 'utf8'), 'exact protocol input\n');
  assert.equal((await readFile(join(root, 'prior-args'), 'utf8')).trim(), 'origin ssh://example.test/repo');
  assert.equal(await readFile(join(root, 'review-args'), 'utf8'), 'hook origin ssh://example.test/repo');
  await uninstallHook(root);
  assert.equal(git(root, 'config', '--local', 'core.hooksPath'), '.githooks');
  assert.equal(await readFile(join(hooks, 'pre-push'), 'utf8'), original);
}));

test('context tools read only permitted committed blobs and reject home traversal other commits and execution', async () => repository(async ({ root, base, tip }) => {
  const read = contextReader(root, [tip]);
  await writeFile(join(root, 'app.txt'), 'working tree secret');
  assert.equal(await read('read_file', { path: 'app.txt', start_line: 1, end_line: 1 }), '1: hello next');
  assert.match(await read('list_files'), /app.txt/);
  for (const args of [{ path: '../.codex/auth.json' }, { path: '/etc/passwd' }, { path: 'app.txt', tip: base }, { path: 'app.txt', start_line: 0 }]) await assert.rejects(read('read_file', args));
  await assert.rejects(read('execute', { command: 'echo forbidden' }));
}));

test('one provider call covers multi-ref push and explicit acceptance cannot survive changed outgoing content', async () => repository(async ({ root, base, tip }) => {
  let calls = 0;
  const invoke = async options => { calls++; assert.match(options.prompt, /refs\/heads\/other/); return { ...clean(), findings: [finding(true)] }; };
  const options = { root, protocol: `refs/heads/topic ${tip} refs/heads/topic ${base}\nrefs/heads/other ${tip} refs/heads/other ${base}\n`, remote: 'origin', provider: 'codex', model: 'fixture-model', cliVersion: 'fixture', contract: 'fixture', invoke };
  const first = await prePush(options);
  assert.equal(first.outcome, 'blocked'); assert.equal(first.allowed, false); assert.equal(calls, 1);
  assert.equal((await prePush(options)).cached, true); assert.equal(calls, 1);
  await assert.rejects(acceptReview(root, first.key, 'short'));
  await acceptReview(root, first.key, 'Owner accepts this exact storage change after backup verification.');
  assert.equal((await prePush(options)).outcome, 'accepted'); assert.equal(calls, 1);
  await writeFile(join(root, 'app.txt'), 'hello another change\n'); git(root, 'commit', '-am', 'Another');
  const next = git(root, 'rev-parse', 'HEAD');
  const changed = await prePush({ ...options, protocol: options.protocol.replaceAll(tip, next) });
  assert.notEqual(changed.key, first.key); assert.equal(changed.allowed, false); assert.equal(calls, 2);
}));

test('provider errors and unanchored findings are incomplete with explicit scoped acceptance available', async () => repository(async ({ root, base, tip }) => {
  const options = { root, protocol: `refs/heads/topic ${tip} refs/heads/topic ${base}\n`, remote: 'origin', provider: 'claude', model: 'fixture-model', cliVersion: 'fixture', contract: 'fixture' };
  const failure = await prePush({ ...options, invoke: async () => { throw Error('synthetic provider timeout'); } });
  assert.equal(failure.outcome, 'incomplete'); assert.equal(failure.allowed, false);
  const malformed = await prePush({ ...options, invoke: async () => ({ ...clean(), findings: [{ ...finding(true), anchor: 'not in committed source' }] }) });
  assert.equal(malformed.outcome, 'incomplete');
  await acceptReview(root, malformed.key, 'Owner accepts temporary review outage for this exact push.');
  assert.equal((await prePush(options)).allowed, true);
}));

test('human edits to installed hook refuse destructive reinstall and uninstall', async () => repository(async ({ root }) => {
  const directory = await installHook(root, '/trusted/review/cli.mjs');
  const path = join(directory, 'pre-push');
  await writeFile(path, `${await readFile(path, 'utf8')}\n# human customization\n`);
  await assert.rejects(installHook(root, '/trusted/review/new-cli.mjs'), /edited|human/);
  await assert.rejects(uninstallHook(root), /edited|human/);
}));

test('thin provider adapters normalize fake noninteractive CLIs and route only selected authentication', async () => repository(async ({ root, tip }) => {
  const directory = join(root, 'isolated'); await mkdir(directory);
  for (const provider of ['codex', 'claude']) {
    const result = await runProvider({ provider, model: 'fixture-model', directory, repository: root, tips: [tip], prompt: 'exact source fixture', env: { PATH: process.env.PATH, HOME: '/fixture/auth', OPENAI_API_KEY: 'codex-only', ANTHROPIC_API_KEY: 'claude-only', IOS_RECEIPT_TOKEN: 'never-inherit', ASC_PRIVATE_KEY: 'never-inherit' }, execute: async (args, options) => {
      assert.equal(options.cwd, directory); assert.equal(options.input, 'exact source fixture');
      assert.equal(options.env.IOS_RECEIPT_TOKEN, undefined); assert.equal(options.env.ASC_PRIVATE_KEY, undefined);
      if (provider === 'codex') {
        assert.equal(options.env.ANTHROPIC_API_KEY, undefined);
        await writeFile(args[args.indexOf('--output-last-message') + 1], JSON.stringify(clean()));
        return 'transient provider stdout is not retained';
      }
      assert.equal(options.env.OPENAI_API_KEY, undefined);
      return JSON.stringify({ is_error: false, structured_output: clean() });
    } });
    assert.deepEqual(result, clean());
  }
}));

test('read-only context server handles real MCP framing without executing repository content', async () => repository(async ({ root, tip }) => {
  const server = fileURLToPath(new URL('../review/context-server.mjs', import.meta.url));
  const requests = [
    { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26' } },
    { jsonrpc: '2.0', method: 'notifications/initialized' },
    { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'read_file', arguments: { path: 'app.txt', start_line: 1, end_line: 1 } } },
    { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'execute', arguments: { command: 'echo forbidden' } } },
  ];
  const output = execFileSync(process.execPath, [server, root, JSON.stringify([tip])], { input: requests.map(r => JSON.stringify(r)).join('\n') + '\n', encoding: 'utf8' }).trim().split('\n').map(JSON.parse);
  assert.equal(output.length, 3);
  assert.equal(output[0].result.protocolVersion, '2025-03-26');
  assert.equal(output[1].result.content[0].text, '1: hello next');
  assert.equal(output[2].result.isError, true);
}));

test('review includes intermediate committed changes even when final combined tree reverts them', async () => repository(async ({ root, tip }) => {
  await writeFile(join(root, 'temporary.txt'), 'synthetic irreversible exposure marker\n'); git(root, 'add', '.'); git(root, 'commit', '-m', 'Intermediate change');
  const middle = git(root, 'rev-parse', 'HEAD');
  git(root, 'rm', 'temporary.txt'); git(root, 'commit', '-m', 'Revert temporary source');
  const head = git(root, 'rev-parse', 'HEAD');
  const context = await gitContext(root, outgoing(`refs/heads/trunk ${head} refs/heads/trunk ${tip}\n`), 'origin');
  assert.match(context.prompt, /synthetic irreversible exposure marker/);
  assert.ok(context.tips.includes(middle));
  assert.deepEqual(context.updates[0].added, [middle, head]);
}));

test('shallow histories are explicitly incomplete rather than silently truncating initial history', async () => repository(async ({ root, base }) => {
  const parent = await mkdtemp(join(tmpdir(), 'shallow-review-'));
  try {
    const clone = join(parent, 'clone');
    execFileSync('git', ['clone', '--depth', '1', `file://${root}`, clone], { stdio: 'pipe' });
    const head = git(clone, 'rev-parse', 'HEAD');
    await assert.rejects(gitContext(clone, outgoing(`refs/heads/topic ${head} refs/heads/topic ${'0'.repeat(40)}\n`), 'origin'), /shallow/i);
    assert.notEqual(head, base);
  } finally { await rm(parent, { recursive: true, force: true }); }
}));

test('annotated tag metadata and peeled source are both reviewed', async () => repository(async ({ root, tip }) => {
  git(root, 'tag', '-a', 'v-fixture', '-m', 'Synthetic release routing decision', tip);
  const object = git(root, 'rev-parse', 'refs/tags/v-fixture');
  const context = await gitContext(root, outgoing(`refs/tags/v-fixture ${object} refs/tags/v-fixture ${'0'.repeat(40)}\n`), 'origin');
  assert.notEqual(object, tip);
  assert.equal(context.updates[0].tipCommit, tip);
  assert.match(context.updates[0].tags.head, /Synthetic release routing decision/);
}));

test('local replace refs cannot change the committed source being reviewed', async () => repository(async ({ root, base, tip }) => {
  const blob = execFileSync('git', ['-C', root, 'hash-object', '-w', '--stdin'], { input: 'replacement data\n', encoding: 'utf8' }).trim();
  const tree = execFileSync('git', ['-C', root, 'mktree'], { input: `100644 blob ${blob}\tapp.txt\n`, encoding: 'utf8' }).trim();
  const replacement = execFileSync('git', ['-C', root, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.test', 'commit-tree', tree, '-p', base], { input: 'Replacement\n', encoding: 'utf8' }).trim();
  git(root, 'replace', tip, replacement);
  const context = await gitContext(root, outgoing(`refs/heads/topic ${tip} refs/heads/topic ${base}\n`), 'origin');
  assert.match(context.prompt, /hello next/);
  assert.doesNotMatch(context.prompt, /replacement data/);
  assert.equal(await contextReader(root, [tip])('read_file', { path: 'app.txt', start_line: 1, end_line: 1 }), '1: hello next');
}));

test('composed relative hooks continue to resolve from the invoking checkout', async () => repository(async ({ root }) => repository(async ({ root: other }) => {
  for (const [directory, label] of [[root, 'first'], [other, 'second']]) {
    await mkdir(join(directory, '.githooks'));
    await writeFile(join(directory, '.githooks/pre-push'), `#!/bin/sh\nprintf '${label}' > "$PWD/prior-kind"\n`, { mode: 0o755 });
  }
  const cli = join(root, 'fake-review.mjs'); await writeFile(cli, 'process.stdin.resume();');
  git(root, 'config', '--local', 'core.hooksPath', '.githooks');
  const installed = await installHook(root, cli);
  execFileSync(join(installed, 'pre-push'), ['origin', 'url'], { cwd: other, input: '' });
  assert.equal(await readFile(join(other, 'prior-kind'), 'utf8'), 'second');
})));
