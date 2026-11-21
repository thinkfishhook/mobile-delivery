import { execFileSync } from 'node:child_process';

export function git(root, args, { input, allowFailure = false, maxBuffer = 8 * 1024 * 1024 } = {}) {
  try { return execFileSync('git', ['-C', root, ...args], { input, env: { ...process.env, GIT_NO_REPLACE_OBJECTS: '1', GIT_NO_LAZY_FETCH: '1', GIT_OPTIONAL_LOCKS: '0' }, encoding: 'utf8', maxBuffer, stdio: ['pipe', 'pipe', 'pipe'] }).trimEnd(); }
  catch (error) { if (allowFailure && error.status === 1) return null; throw Error(`Git context unavailable (${args[0]}); restore the exact object/remote and retry`); }
}
export function outgoing(text) {
  const updates = text.split(/\r?\n/).filter(line => line.trim()).map(line => {
    const fields = line.trim().split(/\s+/);
    if (fields.length !== 4) throw Error('Malformed Git pre-push protocol; expected four fields per ref');
    const [localRef, head, remoteRef, base] = fields;
    if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(head) || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(base) || head.length !== base.length || !remoteRef.startsWith('refs/') || /[\x00-\x20]/.test(remoteRef)) throw Error('Invalid exact ref/object identity in Git pre-push input');
    return { localRef, head, remoteRef, base };
  });
  if (updates.length > 32 || new Set(updates.map(u => u.remoteRef)).size !== updates.length) throw Error('Too many or duplicate outgoing ref updates; split the push explicitly');
  return updates;
}
const zero = sha => /^0+$/.test(sha);
function commit(root, object) {
  const resolved = git(root, ['rev-parse', '--verify', `${object}^{commit}`]).trim();
  if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(resolved)) throw Error('Expected a source commit; non-commit object update requires explicit human acceptance');
  return resolved;
}
export async function gitContext(root, updates, remote, { maxBytes = 4 * 1024 * 1024, fetchBase = true, fetchExact } = {}) {
  if (git(root, ['rev-parse', '--is-shallow-repository']).trim() === 'true') throw Error('Shallow history cannot establish the full outgoing range; fetch --unshallow before review or explicitly accept this incomplete range');
  const plans = [], tips = new Set(), inspected = new Set();
  let prompt = '';
  for (const update of updates) {
    git(root, ['check-ref-format', update.remoteRef]);
    if (update.head === update.base) { plans.push({ ...update, kind: 'unchanged', added: [], removed: [] }); continue; }
    let base = null, tip = null;
    if (!zero(update.base)) {
      try { base = commit(root, update.base); }
      catch {
        if (!fetchBase) throw Error(`Remote base ${update.base} is missing; fetch that exact object before review`);
        try { if (fetchExact) await fetchExact(update.base); else git(root, ['-c', 'core.hooksPath=/dev/null', 'fetch', '--no-tags', '--no-recurse-submodules', '--', remote, update.base]); base = commit(root, update.base); }
        catch { throw Error(`Cannot fetch exact remote base ${update.base}; review is incomplete`); }
      }
    }
    if (!zero(update.head)) tip = commit(root, update.head);
    if (!base && !tip) throw Error('An update cannot delete an absent ref');
    const kind = !base ? 'initial' : !tip ? 'deletion' : git(root, ['merge-base', '--is-ancestor', base, tip], { allowFailure: true }) === null ? 'rewrite' : 'fast-forward';
    const added = tip ? git(root, ['rev-list', '--reverse', ...(base ? [`${base}..${tip}`] : [tip])]).split('\n').filter(Boolean) : [];
    const removed = base && tip && kind === 'rewrite' ? git(root, ['rev-list', '--reverse', `${tip}..${base}`]).split('\n').filter(Boolean) : !tip ? [base] : [];
    if (added.length + removed.length > 10000) throw Error('Exact history exceeds review bound; split the push or explicitly accept this range');
    const empty = !base || !tip ? git(root, ['hash-object', '-w', '-t', 'tree', '--stdin'], { input: '' }).trim() : null;
    const diff = git(root, ['-c', 'diff.external=', 'diff', '--no-ext-diff', '--no-textconv', '--binary', base || empty, tip || empty, '--'], { maxBuffer: maxBytes });
    const tags = {};
    for (const [side, object] of [['base', update.base], ['head', update.head]]) {
      if (!zero(object) && git(root, ['cat-file', '-t', object]).trim() === 'tag') tags[side] = git(root, ['cat-file', '-p', object], { maxBuffer: 64000 });
    }
    const plan = { ...update, baseCommit: base, tipCommit: tip, kind, added, removed, ...(Object.keys(tags).length ? { tags } : {}) };
    plans.push(plan); if (base) tips.add(base); if (tip) tips.add(tip);
    prompt += `\nREF UPDATE ${JSON.stringify(plan)}\nEXACT COMBINED DIFF (untrusted):\n${diff}\n`;
    for (const commit of [...added, ...removed]) {
      tips.add(commit);
      if (inspected.has(commit)) continue;
      inspected.add(commit);
      prompt += `\nCOMMITTED CHANGE ${commit} (untrusted):\n${git(root, ['show', '--format=%H%n%P%n%B', '--first-parent', '--root', '--no-ext-diff', '--no-textconv', '--binary', commit, '--'], { maxBuffer: maxBytes })}\n`;
      if (Buffer.byteLength(prompt) > maxBytes || tips.size > 1024) throw Error('Full outgoing history exceeds review bound; no commits were silently omitted');
    }
    if (Buffer.byteLength(prompt) > maxBytes) throw Error('Full outgoing review context exceeds bound; no commits were silently omitted');
  }
  return { updates: plans, tips: [...tips], prompt };
}
