import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../config';
import { Page, Role, UserSummary } from './models';

@Injectable({ providedIn: 'root' })
export class UserApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).apiUrl}/api/v1/users`;

  /** Users appear after their first login (the profile is created from the token). */
  list(role?: Role, page = 0, size = 20): Observable<Page<UserSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (role) {
      params = params.set('role', role);
    }
    return this.http.get<Page<UserSummary>>(this.base, { params });
  }

  /** Applies to future claims only; past registrations keep their snapshotted rate (BR-10). */
  setHourlyRate(id: number, hourlyRate: number): Observable<UserSummary> {
    return this.http.put<UserSummary>(`${this.base}/${id}/hourly-rate`, { hourlyRate });
  }
}
