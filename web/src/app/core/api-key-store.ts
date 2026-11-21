import { Service, signal } from '@angular/core';

const STORAGE_KEY = 'resumescope.apiKey';

/**
 * The admin's API key, sent as `X-API-Key` on every `/api` call. Kept in sessionStorage so it
 * survives reloads but not closing the tab; storage can be unavailable (private mode, blocked site
 * data), so every access is guarded and the app still works for the current page.
 */
@Service()
export class ApiKeyStore {
  private readonly _key = signal(readStored());
  readonly key = this._key.asReadonly();

  set(key: string): void {
    const trimmed = key.trim();
    this._key.set(trimmed);
    try {
      if (trimmed) {
        sessionStorage.setItem(STORAGE_KEY, trimmed);
      } else {
        sessionStorage.removeItem(STORAGE_KEY);
      }
    } catch {
      // Storage unavailable: keep the key in memory only.
    }
  }

  clear(): void {
    this.set('');
  }
}

function readStored(): string {
  try {
    return sessionStorage.getItem(STORAGE_KEY) ?? '';
  } catch {
    return '';
  }
}
