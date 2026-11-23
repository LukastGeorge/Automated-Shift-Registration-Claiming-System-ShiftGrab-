import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';

import { testProviders } from '../../../testing/test-support';
import { AuditEntry, HoursReport } from '../../core/api/models';
import { fileNameFrom } from '../../core/api/report-api.service';
import { AuditPage, summarizeDetails } from './audit.page';
import { ReportsPage } from './reports.page';

let snack: ReturnType<typeof vi.fn>;

function setup() {
  localStorage.clear();
  snack = vi.fn();
  TestBed.configureTestingModule({
    providers: [...testProviders(), provideHttpClient(), provideHttpClientTesting(), { provide: MatSnackBar, useValue: { open: snack } }],
  });
  return TestBed.inject(HttpTestingController);
}

describe('helpers', () => {
  it('reads the file name of a download', () => {
    expect(fileNameFrom('attachment; filename="shiftgrab-hours-2026-10-01_2026-10-31.csv"', 'x')).toBe(
      'shiftgrab-hours-2026-10-01_2026-10-31.csv',
    );
    expect(fileNameFrom(null, 'fallback.csv')).toBe('fallback.csv');
  });

  it('summarises audit details for people', () => {
    expect(summarizeDetails({ maxSlots: { from: 3, to: 5 }, name: { from: 'A', to: 'B' } })).toEqual([
      'maxSlots: 3 → 5',
      'name: A → B',
    ]);
    expect(summarizeDetails({ affectedUserIds: [2, 3], reason: null })).toEqual(['affectedUserIds: 2, 3', 'reason: –']);
    expect(summarizeDetails(null)).toEqual([]);
  });
});

describe('ReportsPage', () => {
  const report: HoursReport = {
    from: '2026-10-01',
    to: '2026-10-31',
    timeZone: 'Asia/Ho_Chi_Minh',
    generatedAt: '2026-11-23T00:00:00Z',
    rows: [
      { userId: 1, fullName: 'An Nguyen', email: 'an@x', shifts: 2, hours: 12, pay: 340000 },
      { userId: 2, fullName: 'Chi Pham', email: 'chi@x', shifts: 0, hours: 0, pay: 0 },
    ],
    totals: { shifts: 2, hours: 12, pay: 340000 },
  };

  it('shows this month by default, per staff member, with totals', async () => {
    const http = setup();
    const fixture = TestBed.createComponent(ReportsPage);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === 'http://api.test/api/v1/reports/hours');
    expect(req.request.params.get('from')).toMatch(/^\d{4}-\d{2}-01$/);
    req.flush(report);
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    expect(el.querySelector('[data-testid="totals"]')?.textContent).toContain('340.000');
    expect(el.querySelector('tbody tr.zero')?.textContent).toContain('Chi Pham');
  });

  it('downloads the Excel file under the name chosen by the server, in the current language', async () => {
    const http = setup();
    const fixture = TestBed.createComponent(ReportsPage);
    fixture.detectChanges();
    http.expectOne((r) => r.url === 'http://api.test/api/v1/reports/hours').flush(report);
    await fixture.whenStable();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    URL.createObjectURL = vi.fn().mockReturnValue('blob:report');
    URL.revokeObjectURL = vi.fn();

    ((fixture.nativeElement as HTMLElement).querySelector('[data-testid="xlsx"]') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.params.get('format') === 'xlsx');
    expect(req.request.params.get('lang')).toBe('vi');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['x']), { headers: { 'Content-Disposition': 'attachment; filename="october.xlsx"' } });

    expect(click).toHaveBeenCalled();
    expect(snack).toHaveBeenCalledWith('Đã tải october.xlsx.', 'Đóng', expect.anything());
    click.mockRestore();
  });
});

describe('AuditPage', () => {
  const entry = (id: number, overrides: Partial<AuditEntry> = {}): AuditEntry => ({
    id,
    occurredAt: '2026-11-23T01:00:00Z',
    actorId: 7,
    actorName: 'Lan Tran',
    action: 'SHIFT_UPDATED',
    targetType: 'SHIFT',
    targetId: 3,
    details: { maxSlots: { from: 3, to: 5 } },
    ...overrides,
  });

  it('lists changes in words and filters by the person clicked', async () => {
    const http = setup();
    const fixture = TestBed.createComponent(AuditPage);
    fixture.detectChanges();
    http
      .expectOne((r) => r.url === 'http://api.test/api/v1/audit-log')
      .flush({ content: [entry(1), entry(2, { actorId: null, actorName: null, action: 'SHIFT_CLAIMED' })], page: 0, size: 50, totalElements: 2, totalPages: 1 });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="entry-1"]')?.textContent).toContain('Sửa ca');
    expect(el.querySelector('[data-testid="entry-1"]')?.textContent).toContain('maxSlots: 3 → 5');
    expect(el.querySelector('[data-testid="entry-2"]')?.textContent).toContain('Hệ thống');

    (el.querySelector('[data-testid="actor-1"]') as HTMLButtonElement).click();
    const filtered = http.expectOne((r) => r.url === 'http://api.test/api/v1/audit-log');
    expect(filtered.request.params.get('actorId')).toBe('7');
    filtered.flush({ content: [entry(1)], page: 0, size: 50, totalElements: 1, totalPages: 1 });
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="actor-chip"]')?.textContent).toContain('Lan Tran');

    (el.querySelector('[data-testid="actor-chip"]') as HTMLButtonElement).click();
    expect(http.expectOne((r) => r.url === 'http://api.test/api/v1/audit-log').request.params.has('actorId')).toBe(false);
  });
});
