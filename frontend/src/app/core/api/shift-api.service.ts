import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../config';
import { Page, Shift, ShiftStatus } from './models';

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
}
