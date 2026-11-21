import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { landedRequest, publishFindings } from '../review/hosted.mjs';

const sha = 'a'.repeat(40), before = 'b'.repeat(40);
const environment = { GITHUB_ACTIONS: 'true', GITHUB_EVENT_NAME: 'push', GITHUB_REF: 'refs/heads/trunk', GITHUB_SHA: sha, GITHUB_REPOSITORY: 'owner/repo', GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2' };
const event = { ref: 'refs/heads/trunk', before, after: sha, repository: { full_name: 'owner/repo' } };
const finding = (priority = 'P2') => ({ priority, title: 'Saved state loses compatibility', path: 'src/store.kt', line: 12, anchor: 'writeRecord(value)', evidence: 'Existing reader expects the old format; ghp_synthetic_token must be redacted.', impact: 'Saved records cannot reopen.', correction: 'Add a versioned compatible reader.', risk: { blocksPush: true, categories: ['storage-api-protocol'], affectedSurface: 'Saved data', reversibility: 'irreversible', mitigation: 'Version and migrate after backup.', reason: 'Overwritten data cannot be reconstructed.' } });
const review = findings => ({ schema: 1, complete: true, summary: 'Complete fixture review.', findings });
function fixture() {
  const issues = [], calls = [], labels = new Set(); let lost = false, failAt = 0, writes = 0;
  const api = async (path, body, method = body ? 'POST' : 'GET') => {
    calls.push({ path, body, method });
    if (path.startsWith('issues?')) return issues;
    if (path.startsWith('labels/')) { if (labels.has(path.slice(7))) return {}; const e = Error('HTTP 404'); e.status = 404; throw e; }
    if (path === 'labels') { labels.add(body.name); return {}; }
    if (path === 'issues') {
      if (++writes === failAt) throw Error('HTTP 403');
      const issue = { ...body, number: issues.length + 1, state: 'open', html_url: `https://github.com/owner/repo/issues/${issues.length + 1}` }; issues.push(issue);
      if (lost) { lost = false; throw Error('response lost'); } return issue;
    }
    if (path.startsWith('issues/')) { const issue = issues.find(i => i.number === Number(path.split('/')[1])); Object.assign(issue, body); return issue; }
    throw Error(`Unexpected ${path}`);
  };
  const source = async f => ({ sha, path: f.path, line: f.line, fullLine: '  writeRecord(value)  ' });
  const options = { api, repository: 'owner/repo', sourceSha: sha, before, ref: 'refs/heads/trunk', provider: 'codex', model: 'fixture-model', runId: '123', attempt: 2, source, secrets: ['ghp_synthetic_token'] };
  return { api, issues, calls, options, loseCreate: () => { lost = true; }, failWrite: n => { failAt = n; } };
}

test('landed review freezes the complete event before-after range instead of latest trunk', () => {
  const request = landedRequest(environment, event, 'trunk');
  assert.equal(request.before, before); assert.equal(request.after, sha);
  assert.equal(request.updates[0].head, sha); assert.equal(request.updates[0].base, before);
  for (const wrong of [{ ...event, repository: { full_name: 'foreign/repo' } }, { ...event, after: 'c'.repeat(40) }, { ...event, ref: 'refs/tags/receipt' }]) assert.throws(() => landedRequest(environment, wrong, 'trunk'));
  assert.equal(landedRequest(environment, { ...event, before: '0'.repeat(40) }, 'trunk').before, '0'.repeat(40));
});

test('actionable P1 P2 P3 and urgent P0 publish separate owned issues without a delivery hold', async () => {
  const f = fixture();
  const findings = ['P0', 'P1', 'P2', 'P3'].map((priority, i) => ({ ...finding(priority), path: `src/store${i}.kt` }));
  const result = await publishFindings({ ...f.options, review: review(findings) });
  assert.equal(f.issues.length, 4); assert.equal(result.published, 4);
  assert.match(f.issues[0].title, /P0.*URGENT/);
  assert.ok(f.issues[0].labels.includes('urgent'));
  for (const issue of f.issues) {
    assert.match(issue.body, /fixture-model/); assert.match(issue.body, /attempts\/2/); assert.match(issue.body, /correction|Correction/);
    assert.doesNotMatch(issue.body, /ghp_synthetic_token/);
    assert.ok(!issue.labels.includes('delivery-hold'));
  }
  assert.equal(result.deploymentGate, false);
});

test('reruns provider differences and line movement deduplicate by actual stable code anchor', async () => {
  const f = fixture(); f.loseCreate();
  await publishFindings({ ...f.options, review: review([finding()]) });
  f.issues[0].body += '\nHuman investigation'; f.issues[0].assignees = ['engineer']; f.issues[0].labels.push('triaged');
  await publishFindings({ ...f.options, provider: 'claude', model: 'another-model', attempt: 3, source: async changed => ({ sha, path: changed.path, line: 44, fullLine: 'writeRecord(value)' }), review: review([{ ...finding('P1'), line: 44, anchor: 'Record(value)', title: 'Different provider wording' }]) });
  assert.equal(f.issues.length, 1);
  assert.match(f.issues[0].body, /Human investigation/); assert.match(f.issues[0].body, /claude/);
  assert.deepEqual(f.issues[0].assignees, ['engineer']); assert.ok(f.issues[0].labels.includes('triaged'));
});

test('absence never closes an issue and deliberate closed decisions are preserved', async () => {
  const f = fixture(); await publishFindings({ ...f.options, review: review([finding()]) });
  await publishFindings({ ...f.options, review: review([]) });
  assert.equal(f.issues[0].state, 'open');
  f.issues[0].state = 'closed'; const body = f.issues[0].body;
  const result = await publishFindings({ ...f.options, review: review([finding()]) });
  assert.equal(f.issues.length, 1); assert.equal(f.issues[0].state, 'closed'); assert.equal(f.issues[0].body, body); assert.equal(result.suppressed, 1);
});

test('malformed incomplete and partial publication results remain visible operation errors', async () => {
  const f = fixture();
  await assert.rejects(publishFindings({ ...f.options, review: { ...review([]), complete: false } }), /incomplete/);
  await assert.rejects(publishFindings({ ...f.options, review: { ...review([]), findings: 'invalid' } }));
  assert.equal(f.issues.length, 0);
  f.failWrite(2);
  await assert.rejects(publishFindings({ ...f.options, review: review([finding(), { ...finding(), path: 'src/other.kt' }]) }), /partial|403/);
  assert.equal(f.issues.length, 1);
  const result = await publishFindings({ ...f.options, review: review([finding(), { ...finding(), path: 'src/other.kt' }]) });
  assert.equal(f.issues.length, 2); assert.equal(result.deploymentGate, false);
});

test('credential assignment redaction preserves structured metadata and never copies the value', async () => {
  const f = fixture();
  await publishFindings({ ...f.options, review: review([{ ...finding(), evidence: 'TOKEN=syntheticcredential' }]) });
  assert.equal(f.issues.length, 1);
  assert.doesNotMatch(f.issues[0].body, /syntheticcredential/);
});

test('real hosted phase entry points prepare isolated config then expose provider failure without changing source', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'hosted-review-fixture-'));
  try {
    const root = join(directory, 'repository'); await mkdir(root);
    const baseEnv = { PATH: process.env.PATH, HOME: directory, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' };
    const git = (...args) => execFileSync('git', ['-C', root, ...args], { env: baseEnv, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
    git('init', '-b', 'trunk'); git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@example.test');
    await writeFile(join(root, 'app.txt'), 'before\n'); git('add', '.'); git('commit', '-m', 'Before'); const first = git('rev-parse', 'HEAD');
    await writeFile(join(root, 'app.txt'), 'after\n'); git('commit', '-am', 'After'); const last = git('rev-parse', 'HEAD');
    const eventFile = join(directory, 'event.json'), output = join(directory, 'output'), summary = join(directory, 'summary');
    await writeFile(eventFile, JSON.stringify({ ...event, before: first, after: last })); await writeFile(output, ''); await writeFile(summary, '');
    const env = { ...baseEnv, ...environment, GITHUB_SHA: last, GITHUB_EVENT_PATH: eventFile, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary, RUNNER_TEMP: directory, AI_REVIEW_PROVIDER: 'codex', AI_REVIEW_MODEL: 'fixture-model', AI_REVIEW_KEY_PRESENT: 'true', AI_REVIEW_BRANCH: 'trunk' };
    const script = fileURLToPath(new URL('../review/hosted.mjs', import.meta.url));
    execFileSync(process.execPath, [script, 'prepare'], { cwd: root, env, stdio: 'pipe' });
    const match = (await readFile(output, 'utf8')).match(/^plan<<([^\n]+)\n([^\n]+)\n\1$/m); assert.ok(match);
    const planPath = match[2], plan = JSON.parse(await readFile(planPath, 'utf8'));
    assert.equal(plan.request.before, first); assert.equal(plan.request.after, last);
    const config = await readFile(join(plan.home, 'config.toml'), 'utf8');
    assert.match(config, /shell_tool = false/); assert.match(config, /view_image = false/); assert.match(config, /required = true/);
    const failed = spawnSync(process.execPath, [script, 'publish'], { cwd: root, env: { ...env, AI_REVIEW_PLAN: planPath, AI_REVIEW_PROVIDER_OUTCOME: 'failure' }, encoding: 'utf8' });
    assert.equal(failed.status, 1); assert.match(await readFile(summary, 'utf8'), /Provider\/authentication\/setup\/timeout failed/);
    assert.equal(git('rev-parse', 'HEAD'), last);
    await assert.rejects(access(plan.directory));
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('native Connector findings use the same anchored deduplication and preserve human closure',async()=>{
 const {publishNativeFindings}=await import('../review/connector-publish.mjs');
 const f=fixture();const native={complete:true,model:'connector-managed',sourceSha:sha,findings:[{id:22,reviewId:11,priority:'P2',title:'Preserve saved state',body:'Old readers lose saved records; keep a compatible reader.\n\n<!-- codex-code-review-finding:v1 -->',path:'src/store.kt',line:12,side:'RIGHT',sourceSha:sha}]};
 const input={...f.options,number:3,view:{before,after:sha},evidence:native,readLine:async()=> '  writeRecord(value)  '};
 await publishNativeFindings(input);assert.equal(f.issues.length,1);assert.match(f.issues[0].body,/connector-managed/);assert.match(f.issues[0].body,/discussion_r22/);assert.match(f.issues[0].body,/keep a compatible reader/);assert.doesNotMatch(f.issues[0].body,/Risk context:|Impact:|Correction:/);
 f.issues[0].body+='\nHuman investigation';await publishNativeFindings(input);assert.equal(f.issues.length,1);assert.match(f.issues[0].body,/Human investigation/);
 f.issues[0].state='closed';const body=f.issues[0].body;const r=await publishNativeFindings(input);assert.equal(r.suppressed,1);assert.equal(f.issues[0].body,body);
});
test('native publication validates all committed locations before writing any issue',async()=>{
 const {publishNativeFindings}=await import('../review/connector-publish.mjs');const f=fixture();
 const finding={id:22,reviewId:11,priority:'P0',title:'Preserve saved state',body:'Native evidence.\n<!-- codex-code-review-finding:v1 -->',path:'src/store.kt',line:12,side:'LEFT',sourceSha:before};
 const evidence={complete:true,model:'connector-managed',sourceSha:sha,findings:[finding]};
 const input={...f.options,number:3,view:{before,after:sha},evidence,readLine:async location=>{assert.equal(location.sourceSha,before);return 'writeRecord(value)';}};
 await publishNativeFindings(input);assert.match(f.issues[0].title,/P0.*URGENT/);assert.match(f.issues[0].body,new RegExp(`blob/${before}`));
 for(const changed of [{complete:false},{sourceSha:before},{model:'invented-model'},{findings:[{...finding,sourceSha:'c'.repeat(40)}]},{findings:[{...finding,priority:'P4'}]},{findings:[{...finding,body:'x'.repeat(2100)}]}]){
  const fresh=fixture();await assert.rejects(publishNativeFindings({...input,...fresh.options,evidence:{...evidence,...changed}}));assert.equal(fresh.issues.length,0);
 }
 const fresh=fixture();await assert.rejects(publishNativeFindings({...input,...fresh.options,evidence:{...evidence,findings:[finding,{...finding,id:23,line:13}]},readLine:async location=>{if(location.line===13)throw Error('missing exact source');return 'valid source';}}));assert.equal(fresh.issues.length,0);
});
