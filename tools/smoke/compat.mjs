// Turns a smoke report into marketing/source/compat.json, the data behind the compatibility card.
// Usage: node tools/smoke/compat.mjs [report.json]   (default: the newest report in $AUCTIONLAB_HOME/reports)
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..', '..');
const lab = process.env.AUCTIONLAB_HOME || path.join(os.homedir(), '.cache', 'donutauction-smoke');
const reports = path.join(lab, 'reports');
const file = process.argv[2] || path.join(reports, fs.readdirSync(reports).filter((f) => f.endsWith('.json')).sort().at(-1));
const results = JSON.parse(fs.readFileSync(file, 'utf8'));
const pkg = fs.readFileSync(path.join(repo, 'pom.xml'), 'utf8').match(/<artifactId>donutauction<\/artifactId>\s*<version>([^<]+)<\/version>/);

const compat = {
  plugin: pkg ? pkg[1] : null,
  date: new Date(fs.statSync(file).mtimeMs).toISOString().slice(0, 10),
  targets: results.map((r) => {
    const [project, version] = r.target.split(/-(.+)/);
    return { project, version, java: r.java, client: r.bot, economy: r.economy, passed: r.steps.filter((s) => s.ok).length, steps: r.steps.length,
      errors: r.errors.length, ok: r.ok };
  }),
};
const out = path.join(repo, 'marketing', 'source', 'compat.json');
fs.writeFileSync(out, JSON.stringify(compat, null, 2) + '\n');
console.log(`${path.relative(repo, out)}: ${compat.targets.filter((t) => t.ok).length}/${compat.targets.length} targets pass (from ${file})`);
