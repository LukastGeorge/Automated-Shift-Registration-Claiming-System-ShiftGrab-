import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { page, shift } from '../../../testing/fixtures';
import { testProviders } from '../../../testing/test-support';
import { ShiftTemplate, UserSummary } from '../../core/api/models';
import { ShiftsAdminPage } from './shifts-admin.page';
import { StaffPage } from './staff.page';
import { TemplatesPage } from './templates.page';

let dialogResult: unknown;
let snack: ReturnType<typeof vi.fn>;

async function render<T>(component: Type<T>) {
  localStorage.clear();
  snack = vi.fn();
  TestBed.configureTestingModule({
    imports: [component],
    providers: [
      ...testProviders(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(dialogResult) }) } },
      { provide: MatSnackBar, useValue: { open: snack } },
    ],
  });
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, http: TestBed.inject(HttpTestingController) };
}

function click(el: HTMLElement, testId: string) {
  (el.querySelector(`[data-testid="${testId}"]`) as HTMLElement).click();
}

describe('ShiftsAdminPage', () => {
  it('lists shifts of the next two weeks, whole days in the organisation zone', async () => {
    const { fixture, el, http } = await render(ShiftsAdminPage);
    const req = http.expectOne((r) => r.url === 'http://api.test/api/v1/shifts');
    const from = req.request.params.get('from') ?? '';
    const to = req.request.params.get('to') ?? '';
    expect(from).toMatch(/T17:00:00\.000Z$/); // local midnight = 17:00 UTC the day before
    expect((Date.parse(to) - Date.parse(from)) / 86_400_000).toBe(14);
    req.flush(page([shift({ id: 1 }), shift({ id: 2, status: 'CANCELLED' })]));
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="row-1"]')?.textContent).toContain('2/5');
    expect(el.querySelector('[data-testid="edit-1"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="edit-2"]')).toBeNull();
  });

  it('cancels a shift with the reason for the staff', async () => {
    const { fixture, el, http } = await render(ShiftsAdminPage);
    http.expectOne((r) => r.url === 'http://api.test/api/v1/shifts').flush(page([shift({ id: 1 })]));
    await fixture.whenStable();

    dialogResult = { reason: 'Store closed for repairs' };
    click(el, 'cancel-1');
    const req = http.expectOne({ method: 'POST', url: 'http://api.test/api/v1/shifts/1/cancel' });
    expect(req.request.body).toEqual({ reason: 'Store closed for repairs' });
    req.flush(shift({ id: 1, status: 'CANCELLED' }));
    http.expectOne((r) => r.url === 'http://api.test/api/v1/shifts').flush(page([]));

    expect(snack).toHaveBeenCalledWith('Đã hủy ca "Morning bar".', 'Đóng', expect.anything());
  });

  it('reloads after creating a shift and filters by status', async () => {
    const { fixture, el, http } = await render(ShiftsAdminPage);
    http.expectOne((r) => r.url === 'http://api.test/api/v1/shifts').flush(page([]));
    await fixture.whenStable();
    expect(el.textContent).toContain('Không có ca nào trong khoảng này.');

    dialogResult = shift();
    click(el, 'new-shift');
    http.expectOne((r) => r.url === 'http://api.test/api/v1/shifts').flush(page([shift()]));

    (el.querySelector('mat-button-toggle button') as HTMLButtonElement).click();
    const filtered = http.expectOne((r) => r.url === 'http://api.test/api/v1/shifts');
    expect(filtered.request.params.getAll('status')).toEqual(['OPEN']);
  });
});

describe('TemplatesPage', () => {
  const template: ShiftTemplate = {
    id: 4,
    name: 'Weekday mornings',
    weekdays: [1, 2, 3, 4, 5],
    startTime: '08:00:00',
    endTime: '12:00:00',
    overnight: false,
    maxSlots: 3,
    openBefore: 'PT168H',
    validFrom: '2030-01-01',
    validTo: '2030-01-31',
  };

  it('shows templates and reports what generation did', async () => {
    const { fixture, el, http } = await render(TemplatesPage);
    http.expectOne('http://api.test/api/v1/shift-templates').flush([template]);
    await fixture.whenStable();

    const card = el.querySelector('[data-testid="tpl-4"]');
    expect(card?.textContent).toContain('T2');
    expect(card?.textContent).toContain('08:00 – 12:00');
    expect(card?.textContent).toContain('Mở đăng ký trước 7 ngày');

    click(el, 'generate-4');
    http
      .expectOne({ method: 'POST', url: 'http://api.test/api/v1/shift-templates/4/generate' })
      .flush({ templateId: 4, createdShiftIds: [1, 2, 3], skippedExisting: 2, skippedPast: 1 });

    expect(snack).toHaveBeenCalledWith('Đã tạo 3 ca mới, bỏ qua 2 ca đã có và 1 ca đã qua.', 'Đóng', expect.anything());
  });

  it('creates a template with the opening lead time as an ISO duration', async () => {
    const { fixture, el, http } = await render(TemplatesPage);
    http.expectOne('http://api.test/api/v1/shift-templates').flush([]);
    await fixture.whenStable();

    click(el, 'new-template');
    await fixture.whenStable();
    const name = el.querySelector('[data-testid="tpl-name"]') as HTMLInputElement;
    name.value = 'Weekday mornings';
    name.dispatchEvent(new Event('input'));
    el.querySelector('form')?.dispatchEvent(new Event('submit'));

    const req = http.expectOne({ method: 'POST', url: 'http://api.test/api/v1/shift-templates' });
    expect(req.request.body).toEqual(
      expect.objectContaining({ name: 'Weekday mornings', weekdays: [1, 2, 3, 4, 5], openBefore: 'P7D', maxSlots: 3 }),
    );
    req.flush(template);
    http.expectOne('http://api.test/api/v1/shift-templates').flush([template]);
    expect(snack).toHaveBeenCalledWith('Đã tạo mẫu "Weekday mornings".', 'Đóng', expect.anything());
  });
});

describe('StaffPage', () => {
  const user: UserSummary = { id: 7, fullName: 'An Nguyen', email: 'staff1@shiftgrab.local', role: 'STAFF', hourlyRate: 25000 };

  it('updates an hourly rate', async () => {
    const { fixture, el, http } = await render(StaffPage);
    const list = http.expectOne((r) => r.url === 'http://api.test/api/v1/users');
    expect(list.request.params.get('role')).toBe('STAFF');
    list.flush(page([user]));
    await fixture.whenStable();

    const save = el.querySelector('[data-testid="save-rate-7"]') as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    const input = el.querySelector('[data-testid="rate-7"]') as HTMLInputElement;
    input.value = '30000';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    expect(save.disabled).toBe(false);

    save.click();
    const req = http.expectOne({ method: 'PUT', url: 'http://api.test/api/v1/users/7/hourly-rate' });
    expect(req.request.body).toEqual({ hourlyRate: 30000 });
    req.flush({ ...user, hourlyRate: 30000 });
    await fixture.whenStable();

    expect(el.textContent).toContain('30.000');
    expect(snack).toHaveBeenCalledWith('Đã cập nhật đơn giá của An Nguyen.', 'Đóng', expect.anything());
  });

  it('refuses rates outside the allowed range', async () => {
    const { fixture, el, http } = await render(StaffPage);
    http.expectOne((r) => r.url === 'http://api.test/api/v1/users').flush(page([user]));
    await fixture.whenStable();

    const input = el.querySelector('[data-testid="rate-7"]') as HTMLInputElement;
    input.value = '-5';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect((el.querySelector('[data-testid="save-rate-7"]') as HTMLButtonElement).disabled).toBe(true);
  });
});
