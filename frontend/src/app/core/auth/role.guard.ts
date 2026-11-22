import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';

import { Role } from '../api/models';
import { AuthService } from './auth.service';

/**
 * Hides a whole area of the app from users without the role. This is only for a better experience:
 * the backend checks the role again on every call (`@PreAuthorize`), so a modified browser gains nothing.
 */
export function roleGuard(role: Role): CanMatchFn {
  return () => inject(AuthService).hasRole(role) || inject(Router).createUrlTree(['/forbidden']);
}

/** Start page per role. Users without a ShiftGrab role see why instead of a broken page. */
export function homeFor(auth: AuthService): string {
  if (auth.isManager()) {
    return 'profile';
  }
  return auth.isStaff() ? 'shifts' : 'forbidden';
}
