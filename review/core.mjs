import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const schemaPath = new URL('./schema.json', import.meta.url);
export const schema = JSON.parse(readFileSync(schemaPath, 'utf8'));
export const sha256 = value => createHash('sha256').update(value).digest('hex');
export const contractDigest = () => sha256(['schema.json', 'prompt.txt', 'core.mjs', 'ranges.mjs', 'providers.mjs', 'context-server.mjs', 'hooks.mjs', 'cli.mjs'].map(name => readFileSync(new URL(name, import.meta.url), 'utf8')).join('\n'));

function validate(value, shape, path = 'review') {
  const valid = shape.type === 'object' ? value !== null && typeof value === 'object' && !Array.isArray(value) : shape.type === 'array' ? Array.isArray(value) : shape.type === 'integer' ? Number.isSafeInteger(value) : typeof value === shape.type;
  if (!valid) throw Error(`${path}: expected ${shape.type}`);
  if (shape.enum && !shape.enum.includes(value)) throw Error(`${path}: unsupported value`);
  if (typeof value === 'string' && (value.trim().length < (shape.minLength || 0) || value.length > (shape.maxLength || Infinity))) throw Error(`${path}: invalid string length`);
  if (typeof value === 'number' && (value < (shape.minimum ?? -Infinity) || value > (shape.maximum ?? Infinity))) throw Error(`${path}: number out of range`);
  if (Array.isArray(value)) {
    if (value.length > (shape.maxItems || Infinity)) throw Error(`${path}: too many entries`);
    value.forEach((entry, i) => validate(entry, shape.items, `${path}[${i}]`));
  } else if (shape.type === 'object') {
    if (shape.required.some(key => !Object.hasOwn(value, key)) || Object.keys(value).some(key => !Object.hasOwn(shape.properties, key))) throw Error(`${path}: missing or unexpected fields`);
    for (const [key, field] of Object.entries(shape.properties)) validate(value[key], field, `${path}.${key}`);
  }
}
export function validateReview(value) {
  validate(value, schema);
  for (const finding of value.findings) {
    if (finding.path.startsWith('/') || finding.path.split('/').some(p => ['.', '..', ''].includes(p)) || finding.path.includes('\\') || /[\x00-\x1f]/.test(finding.path)) throw Error('Finding must name a safe repository-relative path');
    if (finding.risk.blocksPush && !finding.risk.categories.length) throw Error('Blocking risk must identify its affected category');
  }
  return value;
}
export function decision(review) { return !review.complete ? 'incomplete' : review.findings.some(f => f.risk.blocksPush) ? 'blocked' : review.findings.length ? 'advisory' : 'clean'; }
export function reviewKey(input) { return sha256(JSON.stringify(input)); }
export function reviewerEnvironment(env, provider) {
  const keys = ['PATH', 'HOME', 'TMPDIR', 'TEMP', 'TMP', 'LANG', 'LC_ALL', 'SystemRoot'];
  keys.push(...(provider === 'codex' ? ['CODEX_HOME', 'OPENAI_API_KEY', 'CODEX_API_KEY'] : ['ANTHROPIC_API_KEY']));
  return Object.fromEntries(keys.filter(key => env[key] !== undefined).map(key => [key, env[key]]));
}
