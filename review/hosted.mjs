import { readFile, writeFile, appendFile, mkdtemp, mkdir, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { outgoing, gitContext, git } from './ranges.mjs';
import { validateReview, sha256, schemaPath } from './core.mjs';
import { contextReader } from './context-server.mjs';
import { codexDisabledFeatures, runProvider, normalizeProvider } from './providers.mjs';
import { githubApi, redact } from '../report-failure/report.mjs';

const oid = value => typeof value === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(value);
const zero = value => /^0+$/.test(value);
const safe = value => String(value ?? '').replace(/[&<>@`|\[\]*_\\]/g, c => `&#${c.charCodeAt(0)};`).replace(/[\x00-\x1f\x7f]/g, ' ');
const start = '<!-- mobile-delivery-ai-status:start -->', end = '<!-- mobile-delivery-ai-status:end -->';
const dataPrefix = '<!-- mobile-delivery-ai-data:';
const encoded = value => JSON.stringify(value).replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026');
function redactData(value, secrets) {
  if (typeof value === 'string') return redact(value, secrets);
  if (Array.isArray(value)) return value.map(item => redactData(item, secrets));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, redactData(item, secrets)]));
  return value;
}
function expect(condition, message) { if (!condition) throw Error(message); }

export function landedRequest(env, event, branch) {
  expect(env.GITHUB_ACTIONS === 'true' && env.GITHUB_EVENT_NAME === 'push', 'Hosted review requires a landed integration push');
  expect(event.repository?.full_name === env.GITHUB_REPOSITORY && event.ref === `refs/heads/${branch}` && env.GITHUB_REF === event.ref, 'Review repository/integration ref differs from the original push');
  expect(oid(event.before) && oid(event.after) && event.before.length === event.after.length && ![event.before, event.after].every(zero), 'Review needs the full exact before/after object IDs');
  expect(zero(event.after) || event.after === env.GITHUB_SHA, 'Push after SHA differs from the immutable Actions event SHA');
  return { repository: env.GITHUB_REPOSITORY, ref: event.ref, before: event.before, after: event.after, sourceSha: zero(event.after) ? event.before : event.after, updates: outgoing(`${event.ref} ${event.after} ${event.ref} ${event.before}\n`) };
}
async function pages(api, path) {
  const rows = [];
  for (let page = 1; page <= 100; page++) {
    const result = await api(`${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`);
    expect(Array.isArray(result), 'Incomplete issue inventory; no clean publication claimed'); rows.push(...result);
    if (result.length < 100) return rows;
  }
  throw Error('Issue inventory exceeds review publication bound; reconcile before retrying');
}
async function label(api, name) {
  try { await api(`labels/${encodeURIComponent(name)}`); return; } catch (error) { if (error.status !== 404) throw error; }
  try { await api('labels', { name, color: name === 'urgent' ? 'B60205' : '5319E7', description: 'Asynchronous AI review; never a delivery hold' }); }
  catch (error) { try { await api(`labels/${encodeURIComponent(name)}`); } catch { throw error; } }
}
function data(body) {
  if (!body?.includes(dataPrefix)) return { schema: 1, observations: [] };
  try { const result = JSON.parse(body.split(dataPrefix)[1].split(' -->')[0]); expect(result.schema === 1 && Array.isArray(result.observations), 'Malformed owned review metadata'); return result; }
  catch { throw Error('Owned AI review metadata is damaged; preserve human text and repair before publication'); }
}
function replace(body, block) {
  const a = body.indexOf(start), b = body.indexOf(end);
  expect((a < 0 && b < 0) || (a >= 0 && b > a && body.indexOf(start, a + start.length) < 0 && body.indexOf(end, b + end.length) < 0), 'Ambiguous AI-owned status block; human repair required');
  return a < 0 ? `${body}\n\n${block}\n` : body.slice(0, a) + block + body.slice(b + end.length);
}

export async function publishFindings({ api, repository, sourceSha, before, after = sourceSha, ref, provider, model, runId, attempt, source, secrets = [], review }) {
  validateReview(review);
  expect(review.complete, 'Provider review is incomplete; findings are not a completed clean review');
  expect(/^[\w.-]+\/[\w.-]+$/.test(repository) && oid(sourceSha) && oid(before) && /^[1-9]\d*$/.test(String(runId)) && Number.isSafeInteger(attempt) && attempt > 0 && ['codex', 'claude', 'connector'].includes(provider) && /^[A-Za-z0-9._:/-]{1,128}$/.test(model) && (provider !== 'connector' || model === 'connector-managed'), 'Invalid review publication provenance');
  const run = `https://github.com/${repository}/actions/runs/${runId}/attempts/${attempt}`;
  const issues = (await pages(api, 'issues?state=all')).filter(issue => !issue.pull_request);
  let published = 0, suppressed = 0;
  const links = [];
  // Validate every finding against committed source before any issue write.
  const anchored = [];
  for (const finding of review.findings) {
    const location = await source(finding);
    expect(oid(location.sha) && location.path === finding.path && Number.isSafeInteger(location.line) && location.line > 0 && typeof location.fullLine === 'string' && location.fullLine.includes(finding.anchor.trim()), 'Finding has no exact committed source anchor');
    const fingerprint = sha256(`${finding.path}\n${location.fullLine.trim().replace(/\s+/g, ' ')}`);
    anchored.push({ finding, location, fingerprint });
  }
  try {
    for (const { finding, location, fingerprint } of anchored) {
      const marker = `<!-- mobile-delivery-ai:${fingerprint} -->`;
      const matching = issues.filter(issue => issue.body?.includes(marker));
      expect(matching.length <= 1, 'Duplicate owned AI issues; reconcile without overwriting human decisions');
      let issue = matching[0];
      if (issue?.state === 'closed') { suppressed++; links.push(`Preserved closed decision: https://github.com/${repository}/issues/${issue.number}`); continue; }
      const previous = data(issue?.body);
      const observation = redactData({ provider, model, run, sourceSha, before, after, ref, location, finding }, secrets);
      const observationKey = value => `${value.run}\n${value.provider}\n${value.finding.evidence}`;
      const observations = [...previous.observations.filter(value => observationKey(value) !== observationKey(observation)), observation].slice(-6);
      const priority = observations.map(value => value.finding.priority).sort()[0];
      const status = { schema: 1, observations };
      const descriptions = observations.map(value => {
        const item = value.finding, loc = value.location;
        const link = `https://github.com/${repository}/blob/${loc.sha}/${loc.path.split('/').map(encodeURIComponent).join('/')}#L${loc.line}`;
        if (value.provider === 'connector') return `### ${item.priority}: ${safe(item.title)}\n\nSource: [${safe(loc.path)}:${loc.line}](${link}) at ${loc.sha}; landed ${value.sourceSha}.\nProvider/model: connector / connector-managed; [publication run](${value.run}).\n\nNative Connector evidence:\n\n${item.evidence.split('\n').map(line => safe(line)).join('\n')}`;
        return `### ${item.priority}: ${safe(item.title)}\n\nSource: [${safe(loc.path)}:${loc.line}](${link}) at ${loc.sha}; landed ${value.sourceSha}.\nProvider/model: ${safe(value.provider)} / ${safe(value.model)}; [review run](${value.run}).\n\nEvidence/scenario: ${safe(item.evidence)}\n\nImpact: ${safe(item.impact)}\n\nCorrection: ${safe(item.correction)}\n\nRisk context: ${safe(item.risk.affectedSurface)}; ${safe(item.risk.reversibility)}. ${safe(item.risk.reason)} Mitigation: ${safe(item.risk.mitigation)}`;
      }).join('\n\n');
      const block = `${start}\nPriority: **${priority}${priority === 'P0' ? ' — URGENT' : ''}**\n\nRef: ${safe(ref)}; exact landed range ${before} → ${after}.\n\nAsynchronous code finding. This issue never holds CI, package preparation or deployment.\n\n${descriptions}\n\n${dataPrefix}${encoded(status)} -->\n${end}`;
      const body = replace(issue?.body || marker, block);
      expect(body.length <= 55000, 'Review plus human content exceeds safe issue size; publication is incomplete until reconciled');
      const names = ['ai-review', `ai-review:${priority}`, ...(priority === 'P0' ? ['urgent'] : [])];
      for (const name of names) await label(api, name);
      if (issue) {
        const existing = (issue.labels || []).map(value => typeof value === 'string' ? value : value.name);
        await api(`issues/${issue.number}`, { body, labels: [...new Set([...existing, ...names])] }, 'PATCH');
      } else {
        try { issue = await api('issues', { title: `[${priority}${priority === 'P0' ? ' URGENT' : ''}] ${redact(finding.title, secrets)}`.slice(0, 240), body, labels: names }); }
        catch (error) {
          const accepted = (await pages(api, 'issues?state=all')).filter(value => !value.pull_request && value.body?.includes(marker));
          expect(accepted.length <= 1, 'Duplicate AI issue after uncertain create; manual reconciliation required');
          if (!accepted.length) throw error; issue = accepted[0];
        }
        issues.push(issue);
      }
      published++; links.push(`https://github.com/${repository}/issues/${issue.number}`);
    }
  } catch (error) { throw Error(`Review publication incomplete/partial (${published} recorded, ${suppressed} closed decisions preserved): ${redact(error.message, secrets)}. Retry this exact run; owned fingerprints prevent duplicate issues.`); }
  return { published, suppressed, deploymentGate: false, summary: `AI review complete: ${review.findings.length} actionable findings, ${published} issue updates, ${suppressed} closed decisions preserved.\n\n${links.map(link => `- ${link}`).join('\n')}\n\nNo CI/deployment gate was applied.` };
}

export async function findingSource(root, tips, preferred, finding) {
  const read = contextReader(root, tips);
  for (const tip of [...new Set([preferred, ...tips.slice().reverse()])].filter(tip => tips.includes(tip))) {
    try {
      const numbered = await read('read_file', { tip, path: finding.path, start_line: finding.line, end_line: finding.line });
      const fullLine = numbered.slice(numbered.indexOf(': ') + 2);
      if (fullLine.includes(finding.anchor.trim())) return { sha: tip, path: finding.path, line: finding.line, fullLine };
    } catch { /* A finding may describe a removed/intermediate committed source view. */ }
  }
  throw Error(`No committed anchor for ${finding.path}:${finding.line}; review is incomplete`);
}

export function trustedCodexConfig({ node, server, repository, tips }) {
  return `approval_policy = "never"\nweb_search = "disabled"\n\n[features]\n${codexDisabledFeatures.map(name => `${name} = false`).join('\n')}\n\n[mcp_servers.review_context]\ncommand = ${JSON.stringify(node)}\nargs = ${JSON.stringify([server, repository, JSON.stringify(tips)])}\nrequired = true\n`;
}
async function outputs(env, values) {
  if (!env.GITHUB_OUTPUT) return;
  for (const [key, value] of Object.entries(values)) { const delimiter = randomUUID(); await appendFile(env.GITHUB_OUTPUT, `${key}<<${delimiter}\n${value}\n${delimiter}\n`); }
}
async function prepare(env) {
  const root = git(process.cwd(), ['rev-parse', '--show-toplevel']).trim();
  const provider = env.AI_REVIEW_PROVIDER, model = env.AI_REVIEW_MODEL;
  expect(['codex', 'claude'].includes(provider) && /^[A-Za-z0-9._:/-]{1,128}$/.test(model || ''), 'Set one provider and an explicit current model');
  expect(env.AI_REVIEW_KEY_PRESENT === 'true', 'Selected provider API credential is missing; add the named repository secret');
  const request = landedRequest(env, JSON.parse(await readFile(env.GITHUB_EVENT_PATH, 'utf8')), env.AI_REVIEW_BRANCH);
  const context = await gitContext(root, request.updates, 'origin', { fetchExact: async sha => git(root, ['-c', 'core.hooksPath=/dev/null', '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'fetch', '--no-tags', '--no-recurse-submodules', '--', 'origin', sha]) });
  const directory = await mkdtemp(join(env.RUNNER_TEMP, 'mobile-delivery-ai-'));
  const working = join(directory, 'working'), home = join(directory, 'codex-home');
  await mkdir(working); await mkdir(home);
  const plan = { schema: 1, root, directory, working, home, provider, model, request, tips: context.tips, runId: env.GITHUB_RUN_ID, attempt: Number(env.GITHUB_RUN_ATTEMPT) };
  await writeFile(join(directory, 'plan.json'), JSON.stringify(plan), { mode: 0o600 });
  await writeFile(join(directory, 'prompt.txt'), `${await readFile(new URL('./prompt.txt', import.meta.url), 'utf8')}\nPermitted immutable views: ${context.tips.join(', ')}\n${context.prompt}`, { mode: 0o600 });
  await writeFile(join(directory, 'schema.json'), await readFile(schemaPath), { mode: 0o600 });
  await writeFile(join(home, 'config.toml'), trustedCodexConfig({ node: process.execPath, server: fileURLToPath(new URL('./context-server.mjs', import.meta.url)), repository: root, tips: context.tips }), { mode: 0o600 });
  await outputs(env, { ready: 'true', plan: join(directory, 'plan.json'), prompt: join(directory, 'prompt.txt'), schema: join(directory, 'schema.json'), result: join(directory, 'result.json'), working, home });
}
async function main() {
  const env = process.env, phase = process.argv[2];
  let plan;
  try {
    if (phase === 'prepare') return await prepare(env);
    expect(env.AI_REVIEW_PLAN, 'Review preparation did not complete; inspect its failed phase');
    plan = JSON.parse(await readFile(env.AI_REVIEW_PLAN, 'utf8'));
    expect(plan.schema === 1 && plan.runId === env.GITHUB_RUN_ID && plan.attempt === Number(env.GITHUB_RUN_ATTEMPT), 'Review plan differs from this exact run/attempt');
    if (phase === 'claude') {
      const review = await runProvider({ provider: 'claude', model: plan.model, directory: plan.working, repository: plan.root, tips: plan.tips, prompt: await readFile(join(plan.directory, 'prompt.txt'), 'utf8'), env });
      await writeFile(join(plan.directory, 'result.json'), JSON.stringify(review), { mode: 0o600 }); return;
    }
    expect(phase === 'publish', 'Expected prepare, claude or publish');
    expect(env.AI_REVIEW_PROVIDER_OUTCOME === 'success', 'Provider/authentication/setup/timeout failed; review is incomplete, never clean');
    const review = normalizeProvider('codex', await readFile(join(plan.directory, 'result.json'), 'utf8'));
    const result = await publishFindings({ api: githubApi(env.GITHUB_REPOSITORY, env.GH_TOKEN, env.GITHUB_API_URL), ...plan.request, provider: plan.provider, model: plan.model, runId: plan.runId, attempt: plan.attempt, review, source: finding => findingSource(plan.root, plan.tips, plan.request.sourceSha, finding), secrets: [env.GH_TOKEN] });
    if (env.GITHUB_STEP_SUMMARY) await appendFile(env.GITHUB_STEP_SUMMARY, `\n${result.summary}\n`);
    console.log(`AI review: ${result.published} published, ${result.suppressed} deliberately closed decisions preserved.`);
  } catch (error) {
    const message = safe(redact(error.message, [env.GH_TOKEN, env.ANTHROPIC_API_KEY]));
    if (env.GITHUB_STEP_SUMMARY) await appendFile(env.GITHUB_STEP_SUMMARY, `\nAI review operation failed/incomplete: ${message}\n\nInspect and rerun this exact review job after repair. CI/package creation/TestFlight remain independent; no code defect or clean review was fabricated.\n`);
    console.error(`AI review operation failed: ${message}`); process.exitCode = 1;
  } finally { if (phase === 'publish' && plan?.directory) await rm(plan.directory, { recursive: true, force: true }); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
