import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../config';
import { Me } from './models';

@Injectable({ providedIn: 'root' })
export class IdentityApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(APP_CONFIG).apiUrl}/api/v1`;

  /** Current user's profile; the backend creates it on the first call (SG-10). */
  me(): Observable<Me> {
    return this.http.get<Me>(`${this.base}/me`);
  }
}
