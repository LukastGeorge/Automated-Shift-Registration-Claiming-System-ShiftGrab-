import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { toProblem } from './problem';

describe('toProblem', () => {
  it('reads the backend error code', () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { status: 409, code: 'SHIFT_FULL', detail: 'Shift is full' },
    });
    expect(toProblem(error)).toEqual({
      status: 409,
      code: 'SHIFT_FULL',
      detail: 'Shift is full',
      errors: undefined,
      retryAfterSeconds: undefined,
    });
  });

  it('keeps Retry-After from 503 responses', () => {
    const error = new HttpErrorResponse({
      status: 503,
      headers: new HttpHeaders({ 'Retry-After': '5' }),
      error: { code: 'LOCK_UNAVAILABLE' },
    });
    expect(toProblem(error).retryAfterSeconds).toBe(5);
  });

  it('keeps field errors of validation failures', () => {
    const errors = [{ field: 'maxSlots', message: 'must be greater than or equal to 1' }];
    const error = new HttpErrorResponse({ status: 400, error: { code: 'VALIDATION_FAILED', errors } });
    expect(toProblem(error).errors).toEqual(errors);
  });

  it('falls back when there is no code or no server', () => {
    expect(toProblem(new HttpErrorResponse({ status: 502, error: '<html>Bad gateway</html>' })).code).toBe('HTTP_502');
    expect(toProblem(new HttpErrorResponse({ status: 0 })).code).toBe('NETWORK_ERROR');
    expect(toProblem(new Error('boom')).code).toBe('UNKNOWN');
  });
});
