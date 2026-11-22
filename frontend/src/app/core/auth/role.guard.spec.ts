import { TestBed } from '@angular/core/testing';
import { Route, Router, UrlSegment, UrlTree } from '@angular/router';

import { fakeKeycloak, testProviders } from '../../../testing/test-support';
import { AuthService } from './auth.service';
import { homeFor, roleGuard } from './role.guard';

describe('roleGuard', () => {
  async function loginAs(roles: string[]) {
    TestBed.configureTestingModule({ providers: testProviders(fakeKeycloak(roles)) });
    await TestBed.inject(AuthService).init();
  }

  function run(role: 'MANAGER' | 'STAFF') {
    const guard = roleGuard(role) as unknown as (route: Route, segments: UrlSegment[]) => unknown;
    return TestBed.runInInjectionContext(() => guard({} as Route, [] as UrlSegment[]));
  }

  it('lets a user with the role in', async () => {
    await loginAs(['MANAGER']);
    expect(run('MANAGER')).toBe(true);
  });

  it('sends a user without the role to the forbidden page', async () => {
    await loginAs(['STAFF']);
    const result = run('MANAGER');
    expect(result instanceof UrlTree).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/forbidden');
  });

  it('picks the start page from the role', async () => {
    await loginAs(['STAFF']);
    expect(homeFor(TestBed.inject(AuthService))).toBe('shifts');
  });

  it('explains instead of failing when the user has no ShiftGrab role', async () => {
    await loginAs(['offline_access']);
    expect(homeFor(TestBed.inject(AuthService))).toBe('forbidden');
  });
});
