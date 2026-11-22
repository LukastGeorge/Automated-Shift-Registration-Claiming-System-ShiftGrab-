import { Provider } from '@angular/core';
import { vi } from 'vitest';

import { KEYCLOAK, KeycloakClient } from '../app/core/auth/auth.service';
import { APP_CONFIG, AppConfig } from '../app/core/config';

export const TEST_CONFIG: AppConfig = {
  apiUrl: 'http://api.test',
  keycloak: { url: 'http://kc.test', realm: 'shiftgrab', clientId: 'shiftgrab-frontend' },
  timeZone: 'Asia/Ho_Chi_Minh',
};

export type FakeKeycloak = KeycloakClient & {
  init: ReturnType<typeof vi.fn>;
  login: ReturnType<typeof vi.fn>;
  logout: ReturnType<typeof vi.fn>;
  updateToken: ReturnType<typeof vi.fn>;
};

/** A keycloak-js stand-in: logged in as `username` with the given realm roles. */
export function fakeKeycloak(roles: string[] = ['STAFF'], username = 'staff1', name = 'An Nguyen'): FakeKeycloak {
  return {
    init: vi.fn().mockResolvedValue(true),
    login: vi.fn().mockResolvedValue(undefined),
    logout: vi.fn().mockResolvedValue(undefined),
    updateToken: vi.fn().mockResolvedValue(false),
    token: 'token-123',
    tokenParsed: { sub: 'sub-1', preferred_username: username, name, realm_access: { roles } },
    onTokenExpired: undefined,
  } as unknown as FakeKeycloak;
}

export function testProviders(keycloak: FakeKeycloak = fakeKeycloak()): Provider[] {
  return [
    { provide: APP_CONFIG, useValue: TEST_CONFIG },
    { provide: KEYCLOAK, useValue: keycloak },
  ];
}
