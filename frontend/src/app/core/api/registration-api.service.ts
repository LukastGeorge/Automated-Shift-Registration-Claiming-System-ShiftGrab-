import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../config';
import { MyShifts, Registration, RegistrationStatus } from './models';

@Injectable({ providedIn: 'root' })
export class RegistrationApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).apiUrl}/api/v1`;

  /** SG-30. 409 with a business code when a rule refuses; 503 + Retry-After when the lock is busy or down. */
  claim(shiftId: number): Observable<Registration> {
    return this.http.post<Registration>(`${this.base}/shifts/${shiftId}/registrations`, null);
  }

  /** SG-31. Allowed until the cancel deadline; repeating it is harmless. */
  cancel(registrationId: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/registrations/${registrationId}`);
  }

  /** SG-32. Upcoming (soonest first) or past (latest first) registrations and this week's hours. */
  mine(scope: 'upcoming' | 'past', page = 0, size = 20, status?: RegistrationStatus): Observable<MyShifts> {
    let params = new HttpParams().set('scope', scope).set('page', page).set('size', size);
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<MyShifts>(`${this.base}/me/registrations`, { params });
  }
}
