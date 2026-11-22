import { computed, inject, Injectable, InjectionToken, signal } from '@angular/core';
import Keycloak from 'keycloak-js';

import { Role } from '../api/models';
import { APP_CONFIG } from '../config';

const APPLICATION_ROLES: readonly Role[] = ['MANAGER', 'STAFF'];

/** The part of keycloak-js this app uses; tests provide a fake with the same shape. */
export type KeycloakClient = Pick<
  Keycloak,
  'init' | 'login' | 'logout' | 'updateToken' | 'token' | 'tokenParsed' | 'onTokenExpired'
>;

export const KEYCLOAK = new InjectionToken<KeycloakClient>('KEYCLOAK', {
  providedIn: 'root',
  factory: () => {
    const { keycloak } = inject(APP_CONFIG);
    return new Keycloak({ url: keycloak.url, realm: keycloak.realm, clientId: keycloak.clientId });
  },
});

export interface CurrentUser {
  username: string;
  name: string;
  roles: Role[];
}

/**
 * Login with Keycloak (Authorization Code + PKCE). The browser never sees a password or a client secret:
 * Keycloak shows the login page and hands back short-lived tokens, which are refreshed before each API call.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = inject(KEYCLOAK);
  private readonly current = signal<CurrentUser | null>(null);

  readonly user = this.current.asReadonly();
  readonly roles = computed(() => this.current()?.roles ?? []);
  readonly isManager = computed(() => this.roles().includes('MANAGER'));
  readonly isStaff = computed(() => this.roles().includes('STAFF'));

  /** Runs before the app starts; redirects to the Keycloak login page when there is no session. */
  async init(): Promise<void> {
    await this.keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false });
    this.keycloak.onTokenExpired = () => {
      this.keycloak.updateToken(30).catch(() => this.keycloak.login());
    };
    this.readUser();
  }

  /** A token valid for at least 30 more seconds; sends the user to login when the session has ended. */
  async token(): Promise<string> {
    try {
      const refreshed = await this.keycloak.updateToken(30);
      if (refreshed) {
        this.readUser();
      }
    } catch {
      await this.keycloak.login();
    }
    return this.keycloak.token ?? '';
  }

  hasRole(role: Role): boolean {
    return this.roles().includes(role);
  }

  logout(): Promise<void> {
    return this.keycloak.logout({ redirectUri: window.location.origin });
  }

  private readUser(): void {
    const claims = this.keycloak.tokenParsed;
    if (!claims) {
      this.current.set(null);
      return;
    }
    const realmRoles = claims.realm_access?.roles ?? [];
    const username = String(claims['preferred_username'] ?? claims.sub ?? '');
    this.current.set({
      username,
      name: String(claims['name'] ?? username),
      roles: APPLICATION_ROLES.filter((role) => realmRoles.includes(role)),
    });
  }
}
