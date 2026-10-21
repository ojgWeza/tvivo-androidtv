import assert from 'node:assert/strict';
import { checkLedger, validateLedger } from './parity.mjs';

const valid = {
  schemaVersion: 1,
  entries: [{
    id: 'T-1', title: 'Example', type: 'fix', origin: 'android',
    status: { android: 'HAS', winui: 'MISSING', tv: 'N-A' }, links: ['app/Test.kt']
  }]
};

assert.deepEqual(validateLedger(valid), []);
const result = checkLedger(valid);
assert.deepEqual(result.errors, []);
assert.deepEqual(result.missing.winui, [{ id: 'T-1', title: 'Example' }]);
assert.deepEqual(result.missing.android, []);
const invalid = structuredClone(valid);
invalid.entries[0].status.tv = 'UNKNOWN';
assert.ok(validateLedger(invalid).some(error => error.includes('status.tv')));
console.log('parity check tests passed');
