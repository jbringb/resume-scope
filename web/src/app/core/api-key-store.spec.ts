import { TestBed } from '@angular/core/testing';
import { ApiKeyStore } from './api-key-store';

describe('ApiKeyStore', () => {
  beforeEach(() => sessionStorage.clear());

  it('trims, keeps and persists the key for the tab session', () => {
    TestBed.inject(ApiKeyStore).set('  secret  ');
    expect(TestBed.inject(ApiKeyStore).key()).toBe('secret');
    expect(sessionStorage.getItem('resumescope.apiKey')).toBe('secret');
  });

  it('restores a stored key on start', () => {
    sessionStorage.setItem('resumescope.apiKey', 'stored');
    expect(TestBed.inject(ApiKeyStore).key()).toBe('stored');
  });

  it('clear removes the key everywhere', () => {
    const store = TestBed.inject(ApiKeyStore);
    store.set('secret');
    store.clear();
    expect(store.key()).toBe('');
    expect(sessionStorage.getItem('resumescope.apiKey')).toBeNull();
  });
});
