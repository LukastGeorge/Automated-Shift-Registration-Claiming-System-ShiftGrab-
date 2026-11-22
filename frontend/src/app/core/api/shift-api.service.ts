import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../config';
import { NewShift, Page, Shift, ShiftChanges, ShiftStatus } from './models';

export interface ShiftQuery {
  from?: string;
  to?: string;
  status?: ShiftStatus[];
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class ShiftApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).apiUrl}/api/v1/shifts`;

  /** Sorted by start time. Staff only ever receive OPEN/FULL shifts that have not started (enforced by the backend). */
  search(query: ShiftQuery = {}): Observable<Page<Shift>> {
    let params = new HttpParams()
      .set('page', query.page ?? 0)
      .set('size', query.size ?? 20);
    if (query.from) {
      params = params.set('from', query.from);
    }
    if (query.to) {
      params = params.set('to', query.to);
    }
    for (const status of query.status ?? []) {
      params = params.append('status', status);
    }
    return this.http.get<Page<Shift>>(this.base, { params });
  }

  create(shift: NewShift): Observable<Shift> {
    return this.http.post<Shift>(this.base, shift);
  }

  /**
   * Optimistic locking (SG-21): `version` is the one the manager was looking at. If someone changed the shift
   * since, the backend answers 409 CONCURRENT_MODIFICATION instead of silently overwriting their change.
   */
  update(id: number, version: number, changes: ShiftChanges): Observable<Shift> {
    return this.http.patch<Shift>(`${this.base}/${id}`, changes, { headers: { 'If-Match': `"${version}"` } });
  }

  /** Cancels the shift and every registration on it in one transaction (BR-07). */
  cancel(id: number, reason?: string): Observable<Shift> {
    return this.http.post<Shift>(`${this.base}/${id}/cancel`, reason ? { reason } : null);
  }
}
