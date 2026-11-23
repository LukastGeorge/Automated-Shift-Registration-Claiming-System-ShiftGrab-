import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../config';
import { AuditAction, AuditEntry, HoursReport, Page } from './models';

export interface AuditQuery {
  actorId?: number;
  action?: AuditAction;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class ReportApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).apiUrl}/api/v1`;

  /** SG-50: `from`/`to` are calendar days (YYYY-MM-DD), both included. */
  hours(from: string, to: string): Observable<HoursReport> {
    return this.http.get<HoursReport>(`${this.base}/reports/hours`, { params: { from, to } });
  }

  /** SG-51: the same report as a file; the full response gives access to the file name. */
  download(from: string, to: string, format: 'csv' | 'xlsx', lang: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/reports/hours`, {
      params: { from, to, format, lang },
      responseType: 'blob',
      observe: 'response',
    });
  }

  /** SG-52, newest first. */
  audit(query: AuditQuery): Observable<Page<AuditEntry>> {
    let params = new HttpParams().set('page', query.page ?? 0).set('size', query.size ?? 50);
    for (const key of ['actorId', 'action', 'from', 'to'] as const) {
      const value = query[key];
      if (value !== undefined && value !== '') {
        params = params.set(key, value);
      }
    }
    return this.http.get<Page<AuditEntry>>(`${this.base}/audit-log`, { params });
  }
}

/** `attachment; filename="report.csv"` → `report.csv`. */
export function fileNameFrom(contentDisposition: string | null, fallback: string): string {
  const match = /filename="?([^";]+)"?/.exec(contentDisposition ?? '');
  return match ? match[1] : fallback;
}
