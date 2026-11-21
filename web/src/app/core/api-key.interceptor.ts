import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { ApiKeyStore } from './api-key-store';

/** Adds `X-API-Key` to same-origin `/api` calls only, so the key never leaks to other hosts. */
export const apiKeyInterceptor: HttpInterceptorFn = (req, next) => {
  const key = inject(ApiKeyStore).key();
  if (!key || !req.url.startsWith('/api/')) {
    return next(req);
  }
  return next(req.clone({ setHeaders: { 'X-API-Key': key } }));
};
