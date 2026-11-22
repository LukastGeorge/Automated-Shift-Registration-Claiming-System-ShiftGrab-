import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../config';
import { GenerationResult, NewShiftTemplate, ShiftTemplate } from './models';

@Injectable({ providedIn: 'root' })
export class TemplateApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).apiUrl}/api/v1/shift-templates`;

  list(): Observable<ShiftTemplate[]> {
    return this.http.get<ShiftTemplate[]>(this.base);
  }

  create(template: NewShiftTemplate): Observable<ShiftTemplate> {
    return this.http.post<ShiftTemplate>(this.base, template);
  }

  /** All-or-nothing and safe to repeat: shifts that already exist or are in the past are skipped and counted. */
  generate(id: number): Observable<GenerationResult> {
    return this.http.post<GenerationResult>(`${this.base}/${id}/generate`, null);
  }
}
