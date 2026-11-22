import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { HttpErrorResponse } from '@angular/common/http';

import { NotifyService } from './notify.service';

describe('NotifyService', () => {
  let open: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    open = vi.fn();
    TestBed.configureTestingModule({ providers: [{ provide: MatSnackBar, useValue: { open } }] });
  });

  it('translates known error codes', () => {
    const notify = TestBed.inject(NotifyService);
    const problem = notify.error(new HttpErrorResponse({ status: 409, error: { code: 'SHIFT_FULL' } }));

    expect(problem.code).toBe('SHIFT_FULL');
    expect(open).toHaveBeenCalledWith('Ca đã hết suất.', 'Đóng', expect.objectContaining({ panelClass: 'snack-error' }));
  });

  it('adds field errors and the retry delay', () => {
    const notify = TestBed.inject(NotifyService);
    expect(
      notify.messageFor({ status: 400, code: 'VALIDATION_FAILED', errors: [{ field: 'name', message: 'must not be blank' }] }),
    ).toBe('Dữ liệu chưa hợp lệ. name: must not be blank');
    expect(notify.messageFor({ status: 503, code: 'CLAIM_BUSY', retryAfterSeconds: 1 })).toBe(
      'Rất nhiều người đang giành ca này. Thử lại sau 1 giây.',
    );
  });

  it('shows the raw code for errors it does not know', () => {
    expect(TestBed.inject(NotifyService).messageFor({ status: 418, code: 'HTTP_418' })).toBe(
      'Đã có lỗi xảy ra (mã HTTP_418).',
    );
  });

  it('shows success messages', () => {
    TestBed.inject(NotifyService).success('app.save');
    expect(open).toHaveBeenCalledWith('Lưu', 'Đóng', { duration: 4000 });
  });
});
