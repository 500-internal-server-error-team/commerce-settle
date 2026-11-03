import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

// The checked-in wireframe owns case names/content. Evaluate only its static data declaration.
const html = readFileSync(new URL('../../docs/wireframes.html', import.meta.url), 'utf8')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
  .replace(/&#x27;/g, "'").replace(/&amp;/g, '&');
const start = html.indexOf('const C=');
const end = html.indexOf('const definitions=', start);
if (start < 0 || end < start) throw new Error('Wireframe case definitions not found');
const data = runInNewContext(`${html.slice(start, end)}; JSON.stringify(data)`, {}, { timeout: 1000 });
mkdirSync(new URL('../src/generated/', import.meta.url), { recursive: true });
writeFileSync(new URL('../src/generated/catalog.json', import.meta.url), data + '\n');

// Ship only input contracts. Full response schemas stay in the canonical OpenAPI and generated types.
const contract = JSON.parse(readFileSync(new URL('../../docs/openapi.json', import.meta.url), 'utf8'));
const paths = Object.fromEntries(Object.entries(contract.paths).map(([path, methods]) => [path,
  Object.fromEntries(Object.entries(methods).filter(([method, op]) => ['get', 'post', 'put', 'delete'].includes(method)
    && (path.startsWith('/api/') || op['x-actor'] === 'OPERATOR')).map(([method, op]) => [method,
      Object.fromEntries(['operationId', 'summary', 'x-actor', 'x-owner-service', 'parameters', 'requestBody']
        .filter(key => op[key] !== undefined).map(key => [key, op[key]]))]))]));
const schemas = {}; const parameters = contract.components.parameters ?? {};
function collect(value) {
  if (!value || typeof value !== 'object') return;
  if (typeof value.$ref === 'string' && value.$ref.startsWith('#/components/schemas/')) {
    const key = value.$ref.split('/').at(-1);
    if (!schemas[key]) { schemas[key] = contract.components.schemas[key]; collect(schemas[key]); }
  }
  for (const child of Object.values(value)) collect(child);
}
collect(paths); collect(parameters);
writeFileSync(new URL('../src/generated/contract.json', import.meta.url), JSON.stringify({ paths, components: { schemas, parameters } }) + '\n');
