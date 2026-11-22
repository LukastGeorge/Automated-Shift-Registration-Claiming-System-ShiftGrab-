import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';

import { shift } from '../../../testing/fixtures';
import { testProviders } from '../../../testing/test-support';
import { Shift } from '../../core/api/models';
import { ShiftFormDialog } from './shift-form.dialog';

describe('ShiftFormDialog', () => {
  let close: ReturnType<typeof vi.fn>;

  async function render(existing?: Shift) {
    localStorage.clear();
    close = vi.fn();
    TestBed.configureTestingModule({
      imports: [ShiftFormDialog],
      providers: [
        ...testProviders(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { shift: existing } },
        { provide: MatDialogRef, useValue: { close } },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    const fixture = TestBed.createComponent(ShiftFormDialog);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    const type = async (testId: string, value: string) => {
      const input = el.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await fixture.whenStable();
    };
    const submit = async () => {
      el.querySelector('form')?.dispatchEvent(new Event('submit'));
      await fixture.whenStable();
    };
    return { fixture, el, type, submit, http: TestBed.inject(HttpTestingController) };
  }

  it('creates a shift with times converted from the organisation zone to UTC', async () => {
    const { type, submit, http } = await render();
    await type('name', '  Morning bar ');
    await type('start', '2031-03-02T08:00');
    await type('end', '2031-03-02T12:00');
    await type('opensAt', '2031-03-01T09:00');
    await type('maxSlots', '4');
    await submit();

    const req = http.expectOne({ method: 'POST', url: 'http://api.test/api/v1/shifts' });
    expect(req.request.body).toEqual({
      name: 'Morning bar',
      startAt: '2031-03-02T01:00:00.000Z',
      endAt: '2031-03-02T05:00:00.000Z',
      registrationOpenAt: '2031-03-01T02:00:00.000Z',
      maxSlots: 4,
    });
    req.flush(shift({ name: 'Morning bar' }));
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ name: 'Morning bar' }));
  });

  it('shows rule errors and sends nothing', async () => {
    const { el, type, submit, http } = await render();
    await type('name', 'Too long');
    await type('start', '2031-03-02T06:00');
    await type('end', '2031-03-02T20:00');
    await submit();

    expect(el.textContent).toContain('Một ca dài tối đa 12 giờ.');
    http.expectNone({ method: 'POST' });
  });

  it('edits with If-Match and sends only the changed fields', async () => {
    const { type, submit, http } = await render(shift({ id: 5, version: 3, bookedSlots: 0 }));
    await type('maxSlots', '8');
    await submit();

    const req = http.expectOne({ method: 'PATCH', url: 'http://api.test/api/v1/shifts/5' });
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    expect(req.request.body).toEqual({ maxSlots: 8 });
  });

  it('locks the times and the minimum slots once staff hold the shift (BR-08, BR-09)', async () => {
    const { el, type, submit, http } = await render(shift({ bookedSlots: 2 }));
    expect(el.querySelector('[data-testid="times-locked"]')).not.toBeNull();
    expect((el.querySelector('[data-testid="start"]') as HTMLInputElement).disabled).toBe(true);

    await type('maxSlots', '1');
    await submit();

    expect(el.textContent).toContain('Không được ít hơn số người đã giành (2).');
    http.expectNone({ method: 'PATCH' });
  });

  it('says so when nothing changed', async () => {
    const { el, submit, http } = await render(shift());
    await submit();
    expect(el.textContent).toContain('Chưa có thay đổi nào.');
    http.expectNone({ method: 'PATCH' });
  });

  it('closes as stale when someone else edited the shift meanwhile', async () => {
    const { type, submit, http } = await render(shift({ id: 5, version: 3 }));
    await type('name', 'Renamed');
    await submit();

    http
      .expectOne({ method: 'PATCH' })
      .flush({ code: 'CONCURRENT_MODIFICATION' }, { status: 409, statusText: 'Conflict' });

    expect(close).toHaveBeenCalledWith('stale');
  });
});
