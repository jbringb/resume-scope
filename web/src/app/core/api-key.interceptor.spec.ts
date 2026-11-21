import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiKeyStore } from './api-key-store';
import { apiKeyInterceptor } from './api-key.interceptor';

describe('apiKeyInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiKeyInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('adds X-API-Key to /api calls when a key is set', () => {
    TestBed.inject(ApiKeyStore).set('secret');
    http.get('/api/job-roles').subscribe();
    expect(backend.expectOne('/api/job-roles').request.headers.get('X-API-Key')).toBe('secret');
  });

  it('sends no header when no key is set', () => {
    http.get('/api/job-roles').subscribe();
    expect(backend.expectOne('/api/job-roles').request.headers.has('X-API-Key')).toBe(false);
  });

  it('never sends the key outside /api', () => {
    TestBed.inject(ApiKeyStore).set('secret');
    http.get('/health').subscribe();
    http.get('https://example.com/api/job-roles').subscribe();
    expect(backend.expectOne('/health').request.headers.has('X-API-Key')).toBe(false);
    expect(
      backend.expectOne('https://example.com/api/job-roles').request.headers.has('X-API-Key'),
    ).toBe(false);
  });
});
