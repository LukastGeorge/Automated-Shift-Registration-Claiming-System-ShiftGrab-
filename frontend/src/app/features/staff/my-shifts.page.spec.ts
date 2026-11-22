import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { myShift, myShifts } from '../../../testing/fixtures';
import { testProviders } from '../../../testing/test-support';
import { MyShiftsPage } from './my-shifts.page';

describe('MyShiftsPage', () => {
  let confirmed: boolean;
  let snack: ReturnType<typeof vi.fn>;

  async function render() {
    localStorage.clear();
    confirmed = true;
    snack = vi.fn();
    TestBed.configureTestingModule({
      imports: [MyShiftsPage],
      providers: [
        ...testProviders(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(confirmed) }) } },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    });
    const fixture = TestBed.createComponent(MyShiftsPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http
      .expectOne((r) => r.url === 'http://api.test/api/v1/me/registrations' && r.params.get('scope') === 'upcoming')
      .flush(myShifts([myShift(), myShift({ id: 11, cancellable: false })], 36));
    await fixture.whenStable();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows this week against the weekly limit', async () => {
    const { el } = await render();
    const week = el.querySelector('[data-testid="week"]');
    expect(week?.textContent).toContain('36/40 giờ');
    expect(week?.textContent).toContain('Còn có thể giành 4 giờ');
    expect(week?.querySelector('.bar span')?.classList).toContain('high');
  });

  it('offers cancel only before the deadline', async () => {
    const { el } = await render();
    expect(el.querySelector('[data-testid="cancel-10"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="cancel-11"]')).toBeNull();
    expect(el.querySelector('[data-testid="reg-11"]')?.textContent).toContain('Đã quá hạn hủy');
  });

  it('cancels after confirmation and reloads', async () => {
    const { fixture, http, el } = await render();

    (el.querySelector('[data-testid="cancel-10"]') as HTMLButtonElement).click();
    http.expectOne({ method: 'DELETE', url: 'http://api.test/api/v1/registrations/10' }).flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne((r) => r.url === 'http://api.test/api/v1/me/registrations').flush(myShifts([], 32));
    await fixture.whenStable();

    expect(snack).toHaveBeenCalledWith('Đã hủy đăng ký.', 'Đóng', expect.anything());
    expect(el.textContent).toContain('Chưa có ca nào.');
  });

  it('does nothing when the user keeps the shift', async () => {
    const { http, el } = await render();
    confirmed = false;
    (el.querySelector('[data-testid="cancel-10"]') as HTMLButtonElement).click();
    http.expectNone({ method: 'DELETE' });
  });

  it('explains a refused cancel and refreshes the list', async () => {
    const { fixture, http, el } = await render();

    (el.querySelector('[data-testid="cancel-10"]') as HTMLButtonElement).click();
    http
      .expectOne({ method: 'DELETE', url: 'http://api.test/api/v1/registrations/10' })
      .flush({ code: 'CANCEL_DEADLINE_PASSED' }, { status: 409, statusText: 'Conflict' });
    http.expectOne((r) => r.url === 'http://api.test/api/v1/me/registrations').flush(myShifts([myShift({ cancellable: false })]));
    await fixture.whenStable();

    expect(snack).toHaveBeenCalledWith('Đã quá hạn hủy đăng ký.', 'Đóng', expect.anything());
  });

  it('switches to past shifts', async () => {
    const { fixture, http, el } = await render();

    (el.querySelector('[data-testid="scope-past"] button') as HTMLButtonElement).click();
    http.expectOne((r) => r.params.get('scope') === 'past' && r.params.get('page') === '0').flush(myShifts([]));
    await fixture.whenStable();

    expect(el.querySelector('a[href="/shifts"]')).toBeNull();
  });
});
