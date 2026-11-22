import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { testProviders } from '../../../testing/test-support';
import { ProfilePage } from './profile.page';

describe('ProfilePage', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [ProfilePage],
      providers: [...testProviders(), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('shows the profile from the backend', async () => {
    const fixture = TestBed.createComponent(ProfilePage);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('http://api.test/api/v1/me')
      .flush({ id: 1, fullName: 'An Nguyen', email: 'staff1@shiftgrab.local', role: 'STAFF', hourlyRate: 25000 });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="profile-name"]')?.textContent).toContain('An Nguyen');
    expect(el.textContent).toContain('staff1@shiftgrab.local');
    expect(el.textContent).toContain('Nhân viên');
  });

  it('offers a retry when loading fails', async () => {
    const fixture = TestBed.createComponent(ProfilePage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('http://api.test/api/v1/me').flush(null, { status: 0, statusText: 'Unknown Error' });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Không kết nối được máy chủ');

    (el.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    http.expectOne('http://api.test/api/v1/me');
  });
});
