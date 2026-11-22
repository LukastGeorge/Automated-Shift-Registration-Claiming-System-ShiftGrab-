import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { fakeKeycloak, testProviders } from '../../testing/test-support';
import { AuthService } from '../core/auth/auth.service';
import { ShellComponent } from './shell.component';

describe('ShellComponent', () => {
  async function render(roles: string[]) {
    localStorage.clear();
    const keycloak = fakeKeycloak(roles, 'manager', 'Lan Tran');
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [...testProviders(keycloak), provideRouter([])],
    });
    await TestBed.inject(AuthService).init();
    const fixture = TestBed.createComponent(ShellComponent);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement, keycloak };
  }

  it('shows who is logged in and their role', async () => {
    const { el } = await render(['MANAGER']);
    expect(el.querySelector('.name')?.textContent).toContain('Lan Tran');
    expect(el.querySelector('.role')?.textContent).toContain('Quản lý');
  });

  it('switches the language of the whole screen', async () => {
    const { fixture, el } = await render(['STAFF']);
    expect(el.textContent).toContain('Đăng xuất');

    (el.querySelector('[data-testid="lang-toggle"]') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(el.textContent).toContain('Log out');
  });

  it('logs out', async () => {
    const { el, keycloak } = await render(['STAFF']);
    const logout = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Đăng xuất'));
    logout?.click();
    expect(keycloak.logout).toHaveBeenCalled();
  });
});
