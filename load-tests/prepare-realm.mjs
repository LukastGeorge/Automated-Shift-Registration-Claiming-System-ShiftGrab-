// Generates the Keycloak realm used ONLY for load tests (SG-80): 1000 staff + 1 manager, the password-grant
// client k6 uses to get tokens, and a deliberately cheap password hash so importing and logging in 1000 users
// takes seconds. Never use this realm for anything else.
//
//   node load-tests/prepare-realm.mjs [users]   → load-tests/.generated/realm-loadtest.json
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';

const users = Number(process.argv[2] ?? 1000);
const base = JSON.parse(readFileSync(new URL('../infra/keycloak/realm-shiftgrab.json', import.meta.url)));

const realm = {
  ...base,
  // One cheap hash iteration: fine for throwaway test accounts, never for real ones.
  passwordPolicy: 'hashAlgorithm(pbkdf2-sha256) and hashIterations(1)',
  bruteForceProtected: false,
  accessTokenLifespan: 1800,
  users: [
    {
      username: 'manager', email: 'manager@load.test', firstName: 'Load', lastName: 'Manager', enabled: true,
      emailVerified: true, credentials: [{ type: 'password', value: 'manager123', temporary: false }],
      realmRoles: ['MANAGER'],
    },
    ...Array.from({ length: users }, (_, i) => ({
      username: `staff${String(i).padStart(4, '0')}`,
      email: `staff${String(i).padStart(4, '0')}@load.test`,
      firstName: 'Load', lastName: `Staff ${i}`, enabled: true, emailVerified: true,
      credentials: [{ type: 'password', value: 'load123', temporary: false }],
      realmRoles: ['STAFF'],
    })),
  ],
};

mkdirSync(new URL('./.generated/', import.meta.url), { recursive: true });
writeFileSync(new URL('./.generated/realm-loadtest.json', import.meta.url), JSON.stringify(realm));
console.log(`realm-loadtest.json: ${users} staff + 1 manager`);
