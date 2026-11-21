import { readFile, writeFile, mkdir, readdir, stat, chmod, rm } from 'node:fs/promises';
import { resolve, join, isAbsolute } from 'node:path';
import { git } from './ranges.mjs';
import { createHash } from 'node:crypto';

const marker = '# mobile-delivery-managed-hook-v1';
const quote = text => `'${text.replace(/'/g, "'\\''")}'`;
const hash = text => createHash('sha256').update(text).digest('hex');
const metadataPath = root => resolve(root, git(root, ['rev-parse', '--git-common-dir']).trim(), 'mobile-delivery-hook.json');
async function metadata(root) { try { return JSON.parse(await readFile(metadataPath(root), 'utf8')); } catch (error) { if (error.code === 'ENOENT') return null; throw error; } }
async function config(root, local = false) {
  try { return git(root, ['config', ...(local ? ['--local'] : []), '--get', 'core.hooksPath']).trim(); }
  catch { return null; }
}
async function writeHook(path, text) { await writeFile(path, text, { mode: 0o700 }); await chmod(path, 0o700); }
function priorScript(data, name) {
  return data.relative ? `root=$(git rev-parse --show-toplevel)\nprior="$root"/${quote(join(data.relative, name))}\n` : `prior=${quote(join(data.previous, name))}\n`;
}
async function verifyOwned(data) {
  const names = await readdir(data.directory);
  if (names.length !== Object.keys(data.files || {}).length) throw Error('Installed hooks were edited, removed or human content was added; reconcile before changing them');
  for (const name of names) {
    const text = await readFile(join(data.directory, name), 'utf8');
    if (!data.files?.[name] || hash(text) !== data.files[name]) throw Error('Installed hook was edited or human content was added; preserve the human change before reinstalling/uninstalling');
  }
}

export async function installHook(root, cli, { node = process.execPath } = {}) {
  let data = await metadata(root);
  const effective = await config(root);
  const common = resolve(root, git(root, ['rev-parse', '--git-common-dir']).trim());
  const directory = join(common, 'mobile-delivery-hooks');
  if (data && effective !== data.directory && (data.installed || effective !== data.effective)) throw Error('Hooks configuration changed after installation; preserve it and reconcile before reinstalling');
  if (data) await verifyOwned(data);
  if (!data) {
    try { await stat(directory); throw Error('Unowned mobile-delivery-hooks directory exists; inspect it before installing'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    const local = await config(root, true);
    const previous = effective ? resolve(root, effective) : join(common, 'hooks');
    data = { schema: 1, directory, previous, relative: effective && !isAbsolute(effective) ? effective : null, local, effective, installed: false, files: {} };
    await mkdir(directory, { recursive: true });
    await writeFile(metadataPath(root), JSON.stringify(data), { mode: 0o600 });
  }
  let names = [];
  try { names = await readdir(data.previous); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  for (const name of names.filter(name => /^[a-z][a-z0-9-]*$/.test(name) && name !== 'pre-push')) {
    const prior = join(data.previous, name);
    if ((await stat(prior)).isFile()) await writeHook(join(directory, name), `#!/bin/sh\n${marker}\n${priorScript(data, name)}if [ -x "$prior" ]; then exec "$prior" "$@"; fi\n`);
  }
  await writeHook(join(directory, 'pre-push'), `#!/bin/sh\n${marker}\nset -eu\ninput=$(mktemp)\ntrap 'rm -f "$input"' EXIT HUP INT TERM\ncat > "$input"\n${priorScript(data, 'pre-push')}if [ -x "$prior" ]; then "$prior" "$@" < "$input"; fi\n${quote(node)} ${quote(cli)} hook "$@" < "$input"\n`);
  git(root, ['config', '--local', 'core.hooksPath', directory]);
  data.files = Object.fromEntries(await Promise.all((await readdir(directory)).map(async name => [name, hash(await readFile(join(directory, name), 'utf8'))])));
  data.installed = true;
  await writeFile(metadataPath(root), JSON.stringify(data), { mode: 0o600 });
  return directory;
}
export async function uninstallHook(root) {
  const data = await metadata(root);
  if (!data) return;
  if (await config(root) !== data.directory) throw Error('Hooks configuration changed after installation; refusing to overwrite the newer setting');
  await verifyOwned(data);
  if (data.local === null) git(root, ['config', '--local', '--unset', 'core.hooksPath']);
  else git(root, ['config', '--local', 'core.hooksPath', data.local]);
  await rm(data.directory, { recursive: true }); await rm(metadataPath(root));
}
