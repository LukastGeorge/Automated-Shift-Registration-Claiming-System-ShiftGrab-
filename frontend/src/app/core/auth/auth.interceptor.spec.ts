import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { testProviders } from '../../../testing/test-support';
import { authInterceptor, isApiRequest } from './auth.interceptor';

describe('authInterceptor', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ...testProviders(),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('adds the bearer token to backend calls', async () => {
    const http = TestBed.inject(HttpClient);
    const controller = TestBed.inject(HttpTestingController);

    const response = firstValueFrom(http.get('http://api.test/api/v1/me'));
    await new Promise((resolve) => setTimeout(resolve)); // the token is fetched asynchronously
    const req = controller.expectOne('http://api.test/api/v1/me');
    req.flush({ ok: true });

    expect(req.request.headers.get('Authorization')).toBe('Bearer token-123');
    await expect(response).resolves.toEqual({ ok: true });
  });

  it('never sends the token to other hosts', () => {
    const http = TestBed.inject(HttpClient);
    const controller = TestBed.inject(HttpTestingController);

    http.get('https://evil.example/api/v1/me').subscribe();
    const req = controller.expectOne('https://evil.example/api/v1/me');
    req.flush({});

    expect(req.request.headers.has('Authorization')).toBe(false);
  });

  it('recognises backend URLs for absolute and same-origin setups', () => {
    expect(isApiRequest('http://api.test/api/v1/shifts', 'http://api.test')).toBe(true);
    expect(isApiRequest('/api/v1/shifts', '')).toBe(true);
    expect(isApiRequest('http://api.test.evil.example/api/v1', 'http://api.test')).toBe(false);
    expect(isApiRequest('/config.json', '')).toBe(false);
  });
});
