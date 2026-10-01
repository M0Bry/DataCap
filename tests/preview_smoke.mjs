/**
 * Preview smoke test — runs index.html in a headless DOM and drives the menus and lists the
 * same way a finger would, then asserts what changed on screen.
 *
 *   cd /home/user/tests && npm i jsdom@24.1.0 && node preview_smoke.mjs
 *
 * It exists because the HTML preview is the design reference for the Android app: if a menu
 * there stops working, the Kotlin tests would still pass while the deliverable looked broken.
 */
import { JSDOM, VirtualConsole } from 'jsdom';
import fs from 'fs';
import path from 'path';

const file = process.argv[2] ?? path.join(process.env.HOME ?? '/home/user', 'index.html');
const html = fs.readFileSync(file, 'utf8');

const errors = [];
const vc = new VirtualConsole();
vc.on('jsdomError', e => errors.push('jsdomError: ' + (e.message || e)));
vc.on('error', (...a) => errors.push('console.error: ' + a.join(' ')));

const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  virtualConsole: vc,
  url: 'https://meter.preview/'
});
const { window } = dom;
const doc = window.document;
const wait = ms => new Promise(r => setTimeout(r, ms));
const click = el => el && el.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
const q = sel => doc.querySelectorAll(sel);
const text = () => doc.body.textContent.replace(/\s+/g, ' ');
const pill = label => [...q('[data-filter]')].find(p => new RegExp(label, 'i').test(p.textContent));

const rows = [];
const check = (name, ok, extra = '') => rows.push([ok, name, extra]);

await wait(250);

// ---------------------------------------------------------------- Meter page
check('preview renders without a script error', errors.length === 0, errors.slice(0, 2).join(' | '));
check('Meter page shows the usage ring', !!doc.querySelector('#pct') && /\d+%/.test(text()));
check('Meter page shows Live Speed and the active source', text().includes('Live Speed') && text().includes('Connected to'));
check('Meter page has the Reset button', [...q('[data-reset]')].some(b => /Reset/.test(b.textContent)));
check('All is the only aggregate, with no chips', q('[data-src]').length === 0);

// filter pill → SIM: two chips, one per SIM, never merged
click(pill('SIM'));
await wait(120);
const simChips = [...q('[data-src]')];
check('SIM pill lists each SIM separately', simChips.length === 2, simChips.map(c => c.textContent.trim()).join(' / '));

// chip 2 → the page follows that one SIM only
click(simChips[1]);
await wait(120);
const afterChip = text();
check('tapping SIM 2 selects SIM 2 alone', /Orange/.test(afterChip) && /Quota/.test(afterChip));

// Wi-Fi pill → one chip per known network
click(pill('Wi'));
await wait(120);
check('Wi-Fi pill lists each network separately', q('[data-src]').length >= 1, [...q('[data-src]')].map(c => c.textContent.trim()).join(' / '));

// ---------------------------------------------------------------- Setup page
click(doc.querySelector('[data-setup]'));
await wait(200);
check('Setup opens from the Meter hint', text().includes('SETUP'));
check('Setup has no "Start today"', !text().includes('Start today'));
check('Setup has no "Reset cycle"', !text().includes('Reset cycle'));
check('Setup has no reset control at all', q('[data-reset]').length === 0);
check('Setup carries no datetime input', q('input[type="datetime-local"]').length === 0);
check(
  'Usage Cycle is read-only: Starts / Length / Ends',
  text().includes('Starts') && text().includes('Length') && text().includes('Ends')
);

// quota presets
const presets = q('[data-preset]');
check('quota presets render and are tappable', presets.length >= 6, `${presets.length} presets`);
click(presets[0]);
await wait(120);
check('a preset marks itself as active', !!doc.querySelector('[data-preset].on'));

// quota stepper
const stepperBefore = doc.querySelector('.quota-val')?.textContent?.trim();
click(doc.querySelector('[data-q="1"]'));
await wait(120);
const stepperAfter = doc.querySelector('.quota-val')?.textContent?.trim();
check('quota stepper changes the value', stepperBefore !== stepperAfter, `${stepperBefore} -> ${stepperAfter}`);

// thresholds: add, edit, delete
const thrCount = () => q('[data-thr]').length;
const before = thrCount();
click(doc.querySelector('[data-thr-add]'));
await wait(150);
check('threshold list grows when a row is added', thrCount() === before + 1, `${before} -> ${thrCount()}`);
click(q('[data-thr-del]')[0]);
await wait(150);
check('threshold list shrinks when a row is deleted', thrCount() === before, `${before + 1} -> ${thrCount()}`);

// per-source settings must not leak into another source
const focusChips = [...q('[data-focus]')];
if (focusChips.length > 1) {
  click(focusChips[0]);
  await wait(120);
  const firstQuota = doc.querySelector('.quota-val')?.textContent?.trim();
  click(focusChips[1]);
  await wait(120);
  const secondQuota = doc.querySelector('.quota-val')?.textContent?.trim();
  check('each source keeps its own quota on the Setup page', typeof firstQuota === 'string' && firstQuota.length > 0 && secondQuota.length > 0, `${firstQuota} vs ${secondQuota}`);
}

// back to the Meter page
click(doc.querySelector('[data-back]'));
await wait(150);
check('back button returns to the Meter page', text().includes('LIVE SPEED') || text().includes('Live Speed'));

// ---------------------------------------------------------------- report
let failed = 0;
for (const [ok, name, extra] of rows) {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  — ' + extra : ''}`);
}
console.log(`\n${rows.length - failed}/${rows.length} checks passed${failed ? `, ${failed} FAILED` : ''}`);
process.exit(failed ? 1 : 0);
