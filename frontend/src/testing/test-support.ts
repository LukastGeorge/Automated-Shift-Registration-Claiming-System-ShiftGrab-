import { Provider } from '@angular/core';
import { Client, IFrame, IMessage, StompConfig, StompSubscription } from '@stomp/stompjs';
import { vi } from 'vitest';

import { KEYCLOAK, KeycloakClient } from '../app/core/auth/auth.service';
import { APP_CONFIG, AppConfig } from '../app/core/config';
import { SlotUpdate, STOMP_CLIENT_FACTORY, StompClient } from '../app/core/realtime.service';

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

/** Stands in for the STOMP client: records what the app does and lets a test push server messages. */
export class FakeStomp {
  config?: StompConfig;
  connectHeaders?: Record<string, string>;
  destination?: string;
  activated = 0;
  deactivated = 0;
  private handler?: (message: IMessage) => void;

  readonly factory = (config: StompConfig): StompClient => {
    this.config = config;
    return {
      activate: () => {
        this.activated++;
        void (async () => {
          const client = {} as Client;
          await config.beforeConnect?.(client);
          this.connectHeaders = client.connectHeaders as Record<string, string>;
          config.onConnect?.({} as IFrame);
        })();
      },
      deactivate: () => {
        this.deactivated++;
        return Promise.resolve();
      },
      subscribe: (destination: string, callback: (message: IMessage) => void) => {
        this.destination = destination;
        this.handler = callback;
        return { id: 'sub-0', unsubscribe: () => undefined } as StompSubscription;
      },
    };
  };

  emit(update: SlotUpdate): void {
    this.handler?.({ body: JSON.stringify(update) } as IMessage);
  }

  drop(): void {
    this.config?.onWebSocketClose?.({} as CloseEvent);
  }
}

export function testProviders(keycloak: FakeKeycloak = fakeKeycloak(), stomp: FakeStomp = new FakeStomp()): Provider[] {
  return [
    { provide: APP_CONFIG, useValue: TEST_CONFIG },
    { provide: KEYCLOAK, useValue: keycloak },
    { provide: STOMP_CLIENT_FACTORY, useValue: stomp.factory },
  ];
}
