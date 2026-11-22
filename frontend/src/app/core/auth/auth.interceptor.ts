import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';

import { APP_CONFIG } from '../config';
import { AuthService } from './auth.service';

/**
 * Adds `Authorization: Bearer <token>` to backend calls only. Requests to any other host (fonts, Keycloak,
 * third parties) never receive the token, so it cannot leak.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const { apiUrl } = inject(APP_CONFIG);
  if (!isApiRequest(req.url, apiUrl)) {
    return next(req);
  }
  const auth = inject(AuthService);
  return from(auth.token()).pipe(
    switchMap((token) => next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))),
  );
};

export function isApiRequest(url: string, apiUrl: string): boolean {
  return url.startsWith(`${apiUrl}/api/`);
}
