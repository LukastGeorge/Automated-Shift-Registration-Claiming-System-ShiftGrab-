import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';

import { myShift, myShifts, page, shift } from '../../../testing/fixtures';
import { FakeStomp, fakeKeycloak, testProviders } from '../../../testing/test-support';
import { ClockService } from '../../core/clock.service';
import { claimState, OpenShiftsPage } from './open-shifts.page';

describe('claimState', () => {
  const now = Date.parse('2030-01-01T00:00:00Z');

  it('shows my own shift first, then full, then the countdown, then the claim button', () => {
    expect(claimState(shift(), true, now)).toEqual({ kind: 'mine' });
    expect(claimState(shift({ status: 'FULL', availableSlots: 0 }), false, now)).toEqual({ kind: 'full' });
    expect(claimState(shift({ availableSlots: 0 }), false, now)).toEqual({ kind: 'full' });
    expect(claimState(shift({ registrationOpenAt: '2030-01-01T00:01:05Z' }), false, now)).toEqual({
      kind: 'not-open',
      opensInMs: 65_000,
    });
    expect(claimState(shift(), false, now)).toEqual({ kind: 'claimable' });
  });
});

describe('OpenShiftsPage', () => {
  const now = signal(Date.parse('2030-01-01T00:00:00Z'));
  let snack: ReturnType<typeof vi.fn>;

  let stomp: FakeStomp;

  async function render(shifts = [shift(), shift({ id: 2, name: 'Evening', startAt: '2030-01-08T11:00:00Z', endAt: '2030-01-08T15:00:00Z' })], mine = [myShift({ shiftId: 99 })]) {
    localStorage.clear();
    snack = vi.fn();
    stomp = new FakeStomp();
    TestBed.configureTestingModule({
      imports: [OpenShiftsPage],
      providers: [
        ...testProviders(fakeKeycloak(), stomp),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ClockService, useValue: { now } },
        { provide: MatSnackBar, useValue: { open: snack } },
      ],
    });
    const fixture = TestBed.createComponent(OpenShiftsPage);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne((r) => r.url === 'http://api.test/api/v1/shifts').flush(page(shifts));
    http
      .expectOne((r) => r.url === 'http://api.test/api/v1/me/registrations' && r.params.get('status') === 'CONFIRMED')
      .flush(myShifts(mine));
    await fixture.whenStable();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  function claimRequest(http: HttpTestingController, id: number): TestRequest {
    return http.expectOne({ method: 'POST', url: `http://api.test/api/v1/shifts/${id}/registrations` });
  }

  describe('live updates (SG-40)', () => {
    const live = (shiftId: number, bookedSlots: number, observedAt: string, status: 'OPEN' | 'FULL' = 'OPEN') => ({
      shiftId,
      bookedSlots,
      maxSlots: 5,
      availableSlots: 5 - bookedSlots,
      status,
      observedAt,
    });

    it("moves a card's counter when someone else claims, and shows the connection state", async () => {
      const { fixture, el } = await render();
      await new Promise((resolve) => setTimeout(resolve));
      await fixture.whenStable();
      expect(el.querySelector('[data-testid="live"]')?.textContent).toContain('Trực tiếp');

      stomp.emit(live(1, 4, '2030-01-01T00:00:01Z'));
      await fixture.whenStable();
      expect(el.querySelector('[data-testid="shift-1"]')?.textContent).toContain('Còn 1/5 suất');

      stomp.emit(live(1, 5, '2030-01-01T00:00:02Z', 'FULL'));
      await fixture.whenStable();
      expect(el.querySelector('[data-testid="shift-1"]')?.textContent).toContain('Đã đầy');
    });

    it('ignores an update that arrives after a newer one', async () => {
      const { fixture, el } = await render();
      stomp.emit(live(1, 4, '2030-01-01T00:00:02Z'));
      stomp.emit(live(1, 2, '2030-01-01T00:00:01Z'));
      await fixture.whenStable();
      expect(el.querySelector('[data-testid="shift-1"]')?.textContent).toContain('Còn 1/5 suất');
    });

    it('offers a reload when a new shift appears', async () => {
      const { fixture, el } = await render();
      expect(el.querySelector('[data-testid="new-shifts"]')).toBeNull();
      stomp.emit(live(42, 0, '2030-01-01T00:00:01Z'));
      await fixture.whenStable();
      expect(el.querySelector('[data-testid="new-shifts"]')?.textContent).toContain('Có ca mới');
    });

    it('says so when the live connection drops', async () => {
      const { fixture, el } = await render();
      await new Promise((resolve) => setTimeout(resolve));
      stomp.drop();
      await fixture.whenStable();
      expect(el.querySelector('[data-testid="live"]')?.textContent).toContain('Mất kết nối');
    });
  });

  it('groups shifts by day in the organisation time zone', async () => {
    const { el } = await render();
    expect(el.querySelectorAll('section.day').length).toBe(2);
    expect(el.querySelector('[data-testid="shift-1"] .time')?.textContent).toContain('08:00 – 12:00');
    expect(el.querySelector('[data-testid="shift-1"]')?.textContent).toContain('Còn 3/5 suất');
  });

  it('claims a slot and updates the card without reloading', async () => {
    const { fixture, http, el } = await render();

    (el.querySelector('[data-testid="claim-1"]') as HTMLButtonElement).click();
    claimRequest(http, 1).flush({ id: 7, shiftId: 1, status: 'CONFIRMED' });
    await fixture.whenStable();

    const card = el.querySelector('[data-testid="shift-1"]');
    expect(card?.textContent).toContain('Bạn đã giành');
    expect(card?.textContent).toContain('Còn 2/5 suất');
    expect(snack).toHaveBeenCalledWith('Đã giành ca "Morning bar".', 'Đóng', expect.anything());
  });

  it('marks the shift full when someone was faster', async () => {
    const { fixture, http, el } = await render();

    (el.querySelector('[data-testid="claim-1"]') as HTMLButtonElement).click();
    claimRequest(http, 1).flush({ code: 'SHIFT_FULL' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="shift-1"]')?.textContent).toContain('Đã đầy');
    expect(snack).toHaveBeenCalledWith('Ca đã hết suất.', 'Đóng', expect.anything());
  });

  it('tells the user when to retry if the system is busy', async () => {
    const { fixture, http, el } = await render();

    (el.querySelector('[data-testid="claim-1"]') as HTMLButtonElement).click();
    claimRequest(http, 1).flush({ code: 'CLAIM_BUSY' }, { status: 503, statusText: 'Busy', headers: { 'Retry-After': '1' } });
    await fixture.whenStable();

    expect(snack).toHaveBeenCalledWith('Rất nhiều người đang giành ca này. Thử lại sau 1 giây.', 'Đóng', expect.anything());
    expect(el.querySelector('[data-testid="claim-1"]')).not.toBeNull();
  });

  it('counts down to the opening time and enables the button when it is reached', async () => {
    const { fixture, el } = await render([shift({ registrationOpenAt: '2030-01-01T00:00:05Z' })]);
    expect(el.querySelector('.countdown')?.textContent).toContain('Mở sau 00:00:05');

    now.set(Date.parse('2030-01-01T00:00:05Z'));
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="claim-1"]')).not.toBeNull();
    now.set(Date.parse('2030-01-01T00:00:00Z'));
  });

  it('recognises shifts I already hold', async () => {
    const { el } = await render([shift()], [myShift({ shiftId: 1 })]);
    expect(el.querySelector('[data-testid="shift-1"]')?.textContent).toContain('Bạn đã giành');
  });

  it('explains when there is nothing to claim', async () => {
    const { el } = await render([], []);
    expect(el.textContent).toContain('Chưa có ca nào sắp tới.');
  });

  it('loads the next page on demand', async () => {
    const { fixture, http, el } = await render();
    fixture.componentInstance.loadMore();
    http.expectOne((r) => r.url === 'http://api.test/api/v1/shifts' && r.params.get('page') === '1').flush(
      page([shift({ id: 3, name: 'Late', startAt: '2030-01-09T11:00:00Z', endAt: '2030-01-09T15:00:00Z' })], 1, 2),
    );
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="shift-3"]')).not.toBeNull();
  });
});
