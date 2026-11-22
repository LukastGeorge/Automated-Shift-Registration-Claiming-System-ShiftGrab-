import { HttpErrorResponse } from '@angular/common/http';

/** RFC 7807 error from the backend, with ShiftGrab's stable `code` (see backend ErrorCode). */
export interface Problem {
  status: number;
  code: string;
  detail?: string;
  errors?: { field: string; message: string }[];
  /** From the `Retry-After` header of 503 responses (CLAIM_BUSY, LOCK_UNAVAILABLE). */
  retryAfterSeconds?: number;
}

export function toProblem(error: unknown): Problem {
  if (!(error instanceof HttpErrorResponse)) {
    return { status: 0, code: 'UNKNOWN' };
  }
  if (error.status === 0) {
    return { status: 0, code: 'NETWORK_ERROR' };
  }
  const body = (typeof error.error === 'object' && error.error !== null ? error.error : {}) as Partial<Problem>;
  const retryAfter = Number(error.headers?.get('Retry-After'));
  return {
    status: error.status,
    code: typeof body.code === 'string' ? body.code : `HTTP_${error.status}`,
    detail: body.detail,
    errors: body.errors,
    retryAfterSeconds: Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
  };
}
