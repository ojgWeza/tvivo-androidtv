#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const ledgerPath = path.join(root, 'vault/parity/ledger.json');
const platforms = ['android', 'winui', 'tv'];
const statuses = new Set(['HAS', 'PARTIAL', 'MISSING', 'N-A']);

export function validateLedger(ledger) {
  const errors = [];
  if (!ledger || ledger.schemaVersion !== 1 || !Array.isArray(ledger.entries)) {
    return ['ledger must contain schemaVersion: 1 and an entries array'];
  }
  const ids = new Set();
  for (const [index, item] of ledger.entries.entries()) {
    const label = `entries[${index}]`;
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      errors.push(`${label} must be an object`);
      continue;
    }
    for (const key of ['id', 'title', 'origin', 'type']) {
      if (typeof item[key] !== 'string' || !item[key].trim()) errors.push(`${label}.${key} must be a non-empty string`);
    }
    if (!['fix', 'ux-perk', 'lesson'].includes(item.type)) errors.push(`${label}.type must be fix, ux-perk, or lesson`);
    if (typeof item.id === 'string') {
      if (ids.has(item.id)) errors.push(`${label}.id duplicates ${item.id}`);
      ids.add(item.id);
    }
    if (!item.status || typeof item.status !== 'object' || Array.isArray(item.status)) {
      errors.push(`${label}.status must be an object`);
    } else {
      for (const platform of platforms) {
        if (!statuses.has(item.status[platform])) errors.push(`${label}.status.${platform} must be HAS, PARTIAL, MISSING, or N-A`);
      }
      for (const key of Object.keys(item.status)) if (!platforms.includes(key)) errors.push(`${label}.status has unknown platform ${key}`);
    }
    if (!Array.isArray(item.links) || item.links.some(link => typeof link !== 'string' || !link.trim())) {
      errors.push(`${label}.links must be an array of non-empty strings`);
    }
  }
  return errors;
}

export function checkLedger(ledger) {
  const errors = validateLedger(ledger);
  if (errors.length) return { errors, missing: {} };
  const missing = Object.fromEntries(platforms.map(platform => [platform,
    ledger.entries.filter(item => item.status[platform] === 'MISSING').map(({ id, title }) => ({ id, title }))]));
  return { errors, missing };
}

function readLedger() {
  return JSON.parse(fs.readFileSync(ledgerPath, 'utf8'));
}

function printCheck(ledger) {
  const result = checkLedger(ledger);
  if (result.errors.length) {
    console.error('Parity ledger errors:');
    for (const error of result.errors) console.error(`- ${error}`);
    return 1;
  }
  console.log(`Parity ledger valid (${ledger.entries.length} entries).`);
  for (const platform of platforms) {
    const items = result.missing[platform];
    console.log(`${platform}: ${items.length} MISSING`);
    for (const item of items) console.log(`  ${item.id} — ${item.title}`);
  }
  return 0;
}

function markdownReport(ledger) {
  const lines = ['# Parity ledger report', '', `Entries: ${ledger.entries.length}`, ''];
  for (const platform of platforms) {
    lines.push(`## ${platform}`, '', '| Status | Count |', '| --- | ---: |');
    for (const status of statuses) lines.push(`| ${status} | ${ledger.entries.filter(item => item.status[platform] === status).length} |`);
    lines.push('', '| ID | Status | Type | Origin | Title | Links |', '| --- | --- | --- | --- | --- | --- |');
    for (const item of ledger.entries) {
      const links = item.links.map(link => `\`${link}\``).join(', ');
      lines.push(`| ${item.id} | ${item.status[platform]} | ${item.type} | ${item.origin} | ${item.title.replaceAll('|', '\\|')} | ${links} |`);
    }
    lines.push('');
  }
  return `${lines.join('\n')}\n`;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, ...args] = process.argv.slice(2);
  try {
    if (command === 'check') process.exitCode = printCheck(readLedger());
    else if (command === 'add') {
      if (!args.length) throw new Error('usage: parity.mjs add \'{"id":"...", ...}\'');
      const ledger = readLedger();
      const item = JSON.parse(args.join(' '));
      ledger.entries.push(item);
      const errors = validateLedger(ledger);
      if (errors.length) throw new Error(errors.join('\n'));
      fs.writeFileSync(ledgerPath, `${JSON.stringify(ledger, null, 2)}\n`);
      console.log(`Added ${item.id}.`);
    } else if (command === 'report') {
      const ledger = readLedger();
      const errors = validateLedger(ledger);
      if (errors.length) throw new Error(errors.join('\n'));
      process.stdout.write(markdownReport(ledger));
    } else {
      throw new Error('usage: parity.mjs <check|add|report>');
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
