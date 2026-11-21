import { createHash } from 'node:crypto';
import { readFile, appendFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const start = '<!-- mobile-delivery-status:start -->';
const end = '<!-- mobile-delivery-status:end -->';
const dataPrefix = '<!-- mobile-delivery-report-data:';
const failed = new Set(['failure', 'timed_out', 'startup_failure', 'action_required']);
const hash = value => createHash('sha256').update(value).digest('hex');
function requireValue(condition, message) { if (!condition) throw Error(message); }

export function redact(value, secrets = []) {
  let text = String(value ?? '');
  for (const secret of secrets.filter(s => s && s.length >= 4)) text = text.split(secret).join('[REDACTED]');
  return text.replace(/-----BEGIN [^-]*PRIVATE KEY-----[\s\S]*?-----END [^-]*PRIVATE KEY-----/g, '[REDACTED]')
    .replace(/\b(?:gh[pousr]_[\w]+|github_pat_[\w]+|sk-[\w-]+|eyJ[\w-]+\.[\w-]+\.[\w-]+)\b/g, '[REDACTED]')
    .replace(/\b((?:[A-Z_]*(?:TOKEN|PASSWORD|SECRET|PRIVATE_KEY)|authorization)\s*[:=]\s*)[^\s,;]+/gi, '$1[REDACTED]');
}
const cell = value => String(value).replace(/[&<>@`|\[\]*_\\]/g, c => `&#${c.charCodeAt(0)};`).replace(/[\r\n]+/g, ' ');
const encoded = value => JSON.stringify(value).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026');

async function pages(api, path, field) {
  const result = [];
  for (let page = 1; page <= 100; page++) {
    const data = await api(`${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`);
    const rows = field ? data[field] : data;
    requireValue(Array.isArray(rows), `Incomplete GitHub inventory: ${path}`);
    result.push(...rows);
    if (rows.length < 100) return result;
  }
  throw Error(`GitHub inventory exceeded reporting bound: ${path}; no issue was created`);
}

async function ensureOwnerLabel(api) {
  try { await api('labels/needs-owner'); return; }
  catch (error) { if (error.status !== 404) throw error; }
  try { await api('labels', { name: 'needs-owner', color: 'B60205', description: 'Owner credential or record repair required' }); }
  catch (error) { try { await api('labels/needs-owner'); } catch { throw error; } }
}

function ownedData(body = '') {
  if (!body.includes(dataPrefix)) return null;
  try { return JSON.parse(body.split(dataPrefix)[1].split(' -->')[0]); }
  catch { throw Error('Owned incident metadata is damaged; repair it before rerunning reporting'); }
}
function replaceBlock(body, block) {
  const a = body.indexOf(start), b = body.indexOf(end);
  requireValue((a < 0 && b < 0) || (a >= 0 && b > a && body.indexOf(start, a + start.length) < 0 && body.indexOf(end, b + end.length) < 0), 'Ambiguous owned incident status block; human repair required');
  return a < 0 ? `${body.trimEnd()}\n\n${block}\n` : body.slice(0, a) + block + body.slice(b + end.length);
}

export async function reportDelivery({ api, repository, runId, attempt, role, operationJob, integrationBranch, event = {}, secrets = [] }) {
  requireValue(/^[\w.-]+\/[\w.-]+$/.test(repository) && /^[1-9]\d*$/.test(String(runId)) && Number.isSafeInteger(attempt) && attempt > 0, 'Expected repository and exact numeric run/attempt');
  requireValue(['ci', 'deploy'].includes(role) && operationJob && integrationBranch, 'Set role, operation-job and integration-branch');
  const run = await api(`actions/runs/${runId}`);
  if (run.run_attempt !== attempt) return { outcome: 'skipped', summary: 'A newer attempt superseded this reporter; no incident changed.' };
  requireValue(run.id === Number(runId) && /^[a-f0-9]{40}$/.test(run.head_sha), 'Authoritative run identity is incomplete');
  const jobs = await pages(api, `actions/runs/${runId}/attempts/${attempt}/jobs`, 'jobs');
  const targets = jobs.filter(j => j.name === operationJob);
  requireValue(targets.length === 1, `Expected exactly one completed operation job named ${operationJob}; matrix jobs require separate reporter calls`);
  const job = targets[0];
  if (!failed.has(job.conclusion) && job.conclusion !== 'success') return { outcome: 'skipped', summary: `Operation ${cell(job.conclusion || 'unfinished')}; cancelled/superseded work is not a test failure.` };
  const mode = role === 'ci' ? 'ci' : run.event === 'workflow_dispatch' ? 'manual' : 'automatic';
  requireValue(role !== 'ci' || (run.event === 'push' && run.head_branch === integrationBranch), 'CI reporting requires the configured integration push');
  let source = { sha: run.head_sha, branch: run.head_branch, upstream: null };
  if (mode === 'automatic') {
    const upstream = event.workflow_run;
    requireValue(upstream && /^[1-9]\d*$/.test(String(upstream.id)), 'Automatic deployment reporting needs the original workflow_run event, not a second workflow_run observer');
    const authoritative = await api(`actions/runs/${upstream.id}`);
    requireValue(authoritative.id === upstream.id && authoritative.head_sha === upstream.head_sha && authoritative.head_repository?.full_name === repository, 'Upstream source differs from authoritative caller-repository run');
    source = { sha: authoritative.head_sha, branch: authoritative.head_branch, upstream: authoritative.id };
  }
  requireValue(/^[a-f0-9]{40}$/.test(source.sha) && typeof source.branch === 'string' && source.branch.length > 0, 'Expected an exact source SHA and branch');
  const runUrl = `https://github.com/${repository}/actions/runs/${runId}/attempts/${attempt}`;
  const identity = `${role}\n${mode}\n${run.path}\n${source.branch}`;
  const marker = `<!-- mobile-delivery-incident:${hash(identity)} -->`;
  const issues = (await pages(api, 'issues?state=all')).filter(i => !i.pull_request && i.body?.includes(marker));
  const open = issues.filter(i => i.state === 'open');
  requireValue(open.length <= 1, 'Duplicate owned incidents; reconcile them before reporting');
  let issue = open[0];
  const operationKey = `${runId}/${attempt}`;
  if (!issue && issues.some(i => ownedData(i.body)?.history?.some(h => h.key === operationKey))) return { outcome: 'skipped', summary: 'This exact attempt was deliberately resolved; its decision is preserved.' };
  const annotations = job.check_run_url ? await pages(api, (() => {
    const path = new URL(job.check_run_url).pathname;
    const prefix = `/repos/${repository}/check-runs/`;
    requireValue(path.startsWith(prefix) && /^\d+$/.test(path.slice(prefix.length)), 'Unexpected check-run annotation source');
    return `check-runs/${path.slice(prefix.length)}/annotations`;
  })()) : [];
  const ownerSecrets = [...new Set(annotations.filter(a => a.title === 'mobile-delivery-owner' && /^[A-Z][A-Z0-9_]+$/.test(a.message)).map(a => a.message))];
  const owner = ownerSecrets.length > 0;
  const signals = annotations.filter(a => ['failure', 'warning'].includes(a.annotation_level)).slice(0, 30).map(a => ({
    title: redact(a.title || 'Diagnostic', secrets).slice(0, 180),
    message: redact(a.message, secrets).slice(0, 400),
    location: redact(a.path ? `${a.path}:${a.start_line || 1}` : '', secrets).slice(0, 180),
  }));
  if (job.conclusion === 'success' && !owner) {
    if (!issue) return { outcome: 'healthy', summary: 'Checked operation succeeded; no owned incident is open.' };
    if (mode !== 'manual') {
      const tip = await api(`git/ref/heads/${encodeURIComponent(integrationBranch)}`);
      if (source.sha !== tip.object?.sha || source.branch !== integrationBranch) return { outcome: 'skipped', summary: 'Success is not current integration head; no incident resolved.' };
    }
    if (role === 'deploy' && !annotations.some(a => a.title === 'mobile-delivery-confirmed' && a.message === source.sha)) return { outcome: 'skipped', summary: 'No confirmed internal TestFlight availability; no deployment incident resolved.' };
    const data = ownedData(issue.body);
    requireValue(data?.history?.length, 'Incident has no checked operation history; human reconciliation required');
    const used = new Set(annotations.filter(a => a.title === 'mobile-delivery-credential-used').map(a => a.message));
    if ((data.ownerSecrets || []).some(secret => !used.has(secret))) return { outcome: 'skipped', summary: 'Owner alert remains open: this run did not exercise every affected credential.' };
    const block = `${start}\nState: RESOLVED\n\nRecovery: [checked ${role} success](${runUrl}) for ${source.sha}.\n\n${dataPrefix}${encoded(data)} -->\n${end}`;
    await api(`issues/${issue.number}`, { body: replaceBlock(issue.body, block), state: 'closed', state_reason: 'completed' }, 'PATCH');
    return { outcome: 'resolved', summary: `Resolved checked ${role} incident: ${issue.html_url || `https://github.com/${repository}/issues/${issue.number}`}` };
  }
  const phases = (job.steps || []).filter(s => failed.has(s.conclusion)).map(s => redact(s.name, secrets).slice(0, 180));
  const previous = issue ? ownedData(issue.body) : null;
  const entry = { key: operationKey, sha: source.sha, run: runUrl, conclusion: job.conclusion, phases };
  const history = [...(previous?.history || []).filter(h => h.key !== operationKey), entry].slice(-20);
  const data = { schema: 1, role, mode, branch: source.branch, history, ownerSecrets: [...new Set([...(previous?.ownerSecrets || []), ...ownerSecrets])] };
  const next = owner ? 'Owner: repair the named credential or record, then rerun the failed operation.' : role === 'ci' ? 'Reproduce the named command/tests at this exact SHA using the app verification configuration. Fix the failing phase and push to the integration branch.' : 'Inspect the named phase and Apple state before retrying. For an uncertain upload, reconcile the existing build; do not blindly rebuild or upload a new identity.';
  const diagnostics = signals.map(a => `- **${cell(a.title)}** — ${cell(a.message)}${a.location ? ` — ${cell(a.location)}` : ''}`).join('\n') || '- No structured diagnostic survived. Inspect the failed phase in the linked run; no clean result is claimed.';
  const block = `${start}\nState: OPEN\n\nMode: ${mode}; ${role}; repository ${repository}\nSource: refs/heads/${cell(source.branch)} at ${source.sha}\nRun: [${operationKey}](${runUrl})${source.upstream ? `; upstream [CI](https://github.com/${repository}/actions/runs/${source.upstream})` : ''}\nFailed phase: ${phases.map(cell).join(', ') || 'runner/job startup'} (${cell(job.conclusion)})\n${data.ownerSecrets.length ? `Owner checks still required: ${data.ownerSecrets.join(', ')}\n` : ''}\n\n${diagnostics}\n\nNext step: ${next}\n\nHistory (latest 20 attempts):\n${history.map(h => `- [${h.key}](${h.run}): ${h.sha} — ${cell(h.conclusion)}`).join('\n')}\n\n${dataPrefix}${encoded(data)} -->\n${end}`;
  const body = replaceBlock(issue?.body || marker, block);
  requireValue(body.length <= 55000, 'Incident plus human content exceeds safe issue limit; preserve human text and shorten it before retrying');
  if (issue) {
    if (owner) await ensureOwnerLabel(api);
    const labels = (issue.labels || []).map(label => typeof label === 'string' ? label : label.name);
    await api(`issues/${issue.number}`, { body, ...(owner ? { labels: [...new Set([...labels, 'needs-owner'])] } : {}) }, 'PATCH');
  } else {
    if (owner) await ensureOwnerLabel(api);
    try {
      issue = await api('issues', { title: `${owner ? '[needs-owner] ' : ''}${role === 'ci' ? 'CI' : 'TestFlight'} ${mode} operation failed on ${source.branch}`.slice(0, 240), body, labels: owner ? ['bug', 'needs-owner'] : ['bug'] });
    } catch (error) {
      const accepted = (await pages(api, 'issues?state=all')).filter(i => !i.pull_request && i.body?.includes(marker) && ownedData(i.body)?.history?.some(h => h.key === operationKey));
      requireValue(accepted.length <= 1, 'Duplicate incidents after uncertain create; human reconciliation required');
      if (!accepted.length) throw error;
      issue = accepted[0];
    }
  }
  return { outcome: 'recorded', summary: `Recorded ${role} ${mode} failure: ${issue.html_url || `https://github.com/${repository}/issues/${issue.number}`}\n\nFailed phase: ${phases.map(cell).join(', ') || 'runner/job startup'}\n\n${diagnostics}\n\n${next}` };
}

export function githubApi(repository, token, base = 'https://api.github.com') {
  requireValue(token, 'Set github-token with Actions read, Contents read and Issues write');
  return async (path, body, method = body ? 'POST' : 'GET') => {
    const response = await fetch(`${base}/repos/${repository}/${path}`, { method, headers: { authorization: `Bearer ${token}`, accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28', ...(body ? { 'content-type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(30000) });
    if (!response.ok) {
      const error = Error(`GitHub reporting ${method} ${path.split('?')[0]} returned HTTP ${response.status}; restore permission/service and rerun this reporting job`);
      error.status = response.status;
      throw error;
    }
    return response.status === 204 ? null : response.json();
  };
}

async function main() {
  const env = process.env;
  const token = env['INPUT_GITHUB-TOKEN'] || env.GITHUB_TOKEN;
  try {
    const result = await reportDelivery({ api: githubApi(env.GITHUB_REPOSITORY, token, env.GITHUB_API_URL), repository: env.GITHUB_REPOSITORY, runId: env.GITHUB_RUN_ID, attempt: Number(env.GITHUB_RUN_ATTEMPT), role: env.INPUT_ROLE, operationJob: env['INPUT_OPERATION-JOB'], integrationBranch: env['INPUT_INTEGRATION-BRANCH'], event: JSON.parse(await readFile(env.GITHUB_EVENT_PATH, 'utf8')), secrets: [token] });
    if (env.GITHUB_STEP_SUMMARY) await appendFile(env.GITHUB_STEP_SUMMARY, `\n${result.summary}\n`);
    console.log(`Delivery reporting: ${result.outcome}`);
  } catch (error) {
    const message = redact(error.message, [token]);
    if (env.GITHUB_STEP_SUMMARY) await appendFile(env.GITHUB_STEP_SUMMARY, `\nReporting failed; no incident/recovery success claimed. ${cell(message)}\n`);
    console.error(`Reporting failed: ${message}`);
    process.exitCode = 1;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
