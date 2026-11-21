import { test } from 'node:test';
import assert from 'node:assert/strict';
import { reportDelivery } from '../report-failure/report.mjs';

const sha = 'a'.repeat(40);
function fixture({ conclusion = 'failure', event = 'push', role = 'ci', branch = 'trunk' } = {}) {
  const run = { id: 123, run_attempt: 2, head_sha: sha, head_branch: branch, event, status: 'in_progress', html_url: 'https://github.com/owner/repo/actions/runs/123', path: `.github/workflows/${role}.yml` };
  const jobs = [{ id: 1, name: 'operation', conclusion, check_run_url: 'https://api.github.com/repos/owner/repo/check-runs/1', steps: [{ name: 'Set up job', conclusion: 'failure' }] }];
  const issues = [];
  const calls = [];
  let lost = false, fail = false;
  const annotations = [{ annotation_level: 'failure', title: 'WidgetTest.saves', message: 'expected saved; token=ghp_synthetic_secret_value', path: 'WidgetTest.kt', start_line: 42 }];
  const upstreamRuns = new Map();
  const api = async (path, body, method = body ? 'POST' : 'GET') => {
    calls.push({ path, body, method });
    if (path === 'actions/runs/123') return run;
    if (upstreamRuns.has(path)) return upstreamRuns.get(path);
    if (path.startsWith('actions/runs/123/attempts/2/jobs')) return { jobs };
    if (path.startsWith('check-runs/1/annotations')) return annotations;
    if (path === 'git/ref/heads/trunk') return { object: { type: 'commit', sha } };
    if (path.startsWith('issues?')) return issues;
    if (path.startsWith('labels/')) return {};
    if (path === 'issues') {
      if (fail) throw Error('HTTP 403');
      const issue = { ...body, number: issues.length + 1, state: 'open', html_url: 'https://github.com/owner/repo/issues/1' };
      issues.push(issue);
      if (lost) { lost = false; throw Error('response lost'); }
      return issue;
    }
    if (path.startsWith('issues/')) {
      if (fail) throw Error('HTTP 403');
      const issue = issues.find(i => i.number === Number(path.split('/')[1]));
      Object.assign(issue, body);
      return issue;
    }
    throw Error(`Unexpected API: ${path}`);
  };
  return { api, run, jobs, issues, calls, annotations, upstreamRuns, loseCreate: () => { lost = true; }, failWrites: () => { fail = true; }, options: { api, repository: 'owner/repo', runId: '123', attempt: 2, role, operationJob: 'operation', integrationBranch: 'trunk', event: { ref: `refs/heads/${branch}`, after: sha }, secrets: ['ghp_synthetic_secret_value'] } };
}

test('checkout/setup failure is reported without engine or checkout and names bounded test evidence', async () => {
  const f = fixture();
  const result = await reportDelivery(f.options);
  assert.equal(result.outcome, 'recorded');
  assert.equal(f.issues.length, 1);
  assert.match(f.issues[0].body, /Set up job/);
  assert.match(f.issues[0].body, /WidgetTest.saves/);
  assert.match(result.summary, /WidgetTest.saves/);
  assert.match(f.issues[0].body, /attempts\/2/);
  assert.doesNotMatch(JSON.stringify(f.issues), /ghp_synthetic_secret_value/);
});

test('serialized repeated reporters and a lost create response keep one incident and human content', async () => {
  const f = fixture(); f.loseCreate();
  await reportDelivery(f.options);
  f.issues[0].body += '\nEngineer investigation stays here';
  f.issues[0].labels.push('triaged'); f.issues[0].assignees = ['human'];
  await reportDelivery(f.options);
  assert.equal(f.issues.length, 1);
  assert.match(f.issues[0].body, /Engineer investigation stays here/);
  assert.ok(f.issues[0].labels.includes('triaged'));
  assert.deepEqual(f.issues[0].assignees, ['human']);
  assert.equal(f.calls.filter(c => c.path === 'issues').length, 1);
});

test('cancelled superseded skipped and stale-attempt work creates no false incident', async () => {
  for (const conclusion of ['cancelled', 'skipped', null]) {
    const f = fixture({ conclusion });
    assert.equal((await reportDelivery(f.options)).outcome, 'skipped');
    assert.equal(f.issues.length, 0);
  }
  const stale = fixture(); stale.run.run_attempt = 3;
  assert.equal((await reportDelivery(stale.options)).outcome, 'skipped');
});

test('timeouts are infrastructure failures and publishing failure is visible', async () => {
  const f = fixture({ conclusion: 'timed_out' });
  f.failWrites();
  await assert.rejects(reportDelivery(f.options), /403/);
  assert.equal(f.issues.length, 0);
});

test('green current-head CI resolves its checked incident while manual branch success cannot', async () => {
  const f = fixture(); await reportDelivery(f.options);
  f.jobs[0].conclusion = 'success';
  const manual = { ...f.options, role: 'deploy', event: { ref: 'refs/heads/experiment' } };
  f.run.event = 'workflow_dispatch'; f.run.head_branch = 'experiment';
  await reportDelivery(manual);
  assert.equal(f.issues[0].state, 'open');
  f.run.event = 'push'; f.run.head_branch = 'trunk';
  await reportDelivery(f.options);
  assert.equal(f.issues[0].state, 'closed');
  assert.match(f.issues[0].body, /Recovery/);
});

test('ordinary failure never acquires owner classification and credential annotation does', async () => {
  const ordinary = fixture(); await reportDelivery(ordinary.options);
  assert.ok(!ordinary.issues[0].labels.includes('needs-owner'));
  const credential = fixture({ role: 'deploy', event: 'workflow_dispatch' });
  credential.annotations.push({ annotation_level: 'failure', title: 'mobile-delivery-owner', message: 'IOS_RECEIPT_TOKEN' });
  await reportDelivery(credential.options);
  assert.ok(credential.issues[0].labels.includes('needs-owner'));
});

test('owner recovery requires successful use of the exact credential and skipped deployment cannot resolve', async () => {
  const f = fixture();
  f.annotations.push({ annotation_level: 'failure', title: 'mobile-delivery-owner', message: 'IOS_PROFILE_BASE64' });
  await reportDelivery(f.options);
  f.jobs[0].conclusion = 'success';
  f.annotations.splice(1);
  await reportDelivery(f.options);
  assert.equal(f.issues[0].state, 'open');
  f.annotations.push({ annotation_level: 'notice', title: 'mobile-delivery-credential-used', message: 'IOS_PROFILE_BASE64' });
  await reportDelivery(f.options);
  assert.equal(f.issues[0].state, 'closed');
  const deploy = fixture({ role: 'deploy', event: 'workflow_dispatch' });
  await reportDelivery(deploy.options);
  deploy.jobs[0].conclusion = 'success';
  await reportDelivery(deploy.options);
  assert.equal(deploy.issues[0].state, 'open');
  deploy.annotations.push({ annotation_level: 'notice', title: 'mobile-delivery-confirmed', message: sha });
  await reportDelivery(deploy.options);
  assert.equal(deploy.issues[0].state, 'closed');
});

test('automatic failure reports authoritative upstream source rather than default-branch execution SHA', async () => {
  const f = fixture({ role: 'deploy', event: 'workflow_run' });
  f.run.head_sha = 'b'.repeat(40);
  const upstream = { id: 987, head_sha: sha, head_branch: 'trunk', head_repository: { full_name: 'owner/repo' } };
  f.options.event = { workflow_run: upstream };
  f.upstreamRuns.set('actions/runs/987', upstream);
  await reportDelivery(f.options);
  assert.match(f.issues[0].body, new RegExp(sha));
  assert.doesNotMatch(f.issues[0].body, /b{40}/);
  assert.match(f.issues[0].body, /runs\/987/);
  f.upstreamRuns.set('actions/runs/987', { ...upstream, head_repository: { full_name: 'foreign/repo' } });
  await assert.rejects(reportDelivery(f.options), /Upstream source differs/);
});

test('owner classification added after ordinary failure preserves triage and a manually resolved attempt stays closed', async () => {
  const f = fixture(); await reportDelivery(f.options);
  f.issues[0].labels.push('triaged');
  f.annotations.push({ annotation_level: 'failure', title: 'mobile-delivery-owner', message: 'IOS_RECEIPT_TOKEN' });
  await reportDelivery(f.options);
  assert.ok(f.issues[0].labels.includes('needs-owner'));
  assert.ok(f.issues[0].labels.includes('triaged'));
  f.issues[0].state = 'closed';
  await reportDelivery(f.options);
  assert.equal(f.issues.length, 1);
  assert.equal(f.issues[0].state, 'closed');
});
