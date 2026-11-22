import { TestBed } from '@angular/core/testing';

import { fakeKeycloak, testProviders } from '../../../testing/test-support';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  function setup(roles: string[] = ['STAFF', 'offline_access']) {
    const keycloak = fakeKeycloak(roles);
    TestBed.configureTestingModule({ providers: testProviders(keycloak) });
    return { auth: TestBed.inject(AuthService), keycloak };
  }

  it('logs in with Authorization Code + PKCE and keeps only ShiftGrab roles', async () => {
    const { auth, keycloak } = setup();

    await auth.init();

    expect(keycloak.init).toHaveBeenCalledWith({
      onLoad: 'login-required',
      pkceMethod: 'S256',
      checkLoginIframe: false,
    });
    expect(auth.user()).toEqual({ username: 'staff1', name: 'An Nguyen', roles: ['STAFF'] });
    expect(auth.isStaff()).toBe(true);
    expect(auth.isManager()).toBe(false);
    expect(auth.hasRole('MANAGER')).toBe(false);
  });

  it('refreshes the token before handing it out', async () => {
    const { auth, keycloak } = setup();
    await auth.init();

    await expect(auth.token()).resolves.toBe('token-123');
    expect(keycloak.updateToken).toHaveBeenCalledWith(30);
  });

  it('sends the user back to login when the session cannot be refreshed', async () => {
    const { auth, keycloak } = setup();
    await auth.init();
    keycloak.updateToken.mockRejectedValueOnce(new Error('session expired'));

    await auth.token();

    expect(keycloak.login).toHaveBeenCalled();
  });

  it('refreshes an expiring token in the background', async () => {
    const { auth, keycloak } = setup();
    await auth.init();

    keycloak.onTokenExpired?.();

    expect(keycloak.updateToken).toHaveBeenCalledWith(30);
  });

  it('logs out back to the app', async () => {
    const { auth, keycloak } = setup(['MANAGER']);
    await auth.init();
    expect(auth.isManager()).toBe(true);

    await auth.logout();

    expect(keycloak.logout).toHaveBeenCalledWith({ redirectUri: window.location.origin });
  });
});
