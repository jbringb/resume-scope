import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App shell', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('renders the navigation', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    // whenStable() waits for the pending /health call, so answer it.
    TestBed.inject(HttpTestingController).expectOne('/health').flush({ status: 'UP' });
    await fixture.whenStable();
    const links = [...(fixture.nativeElement as HTMLElement).querySelectorAll('mat-nav-list a')];
    expect(links.map((a) => a.textContent?.trim())).toEqual([
      expect.stringContaining('Job roles'),
      expect.stringContaining('Usage'),
      expect.stringContaining('Settings'),
    ]);
  });

  it('shows the API as online when /health reports UP', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/health').flush({ status: 'UP' });
    await fixture.whenStable();
    const status = (fixture.nativeElement as HTMLElement).querySelector('.api-status');
    expect(status?.textContent).toContain('API online');
  });

  it('shows the API as offline when /health fails', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/health')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await fixture.whenStable();
    const status = (fixture.nativeElement as HTMLElement).querySelector('.api-status');
    expect(status?.textContent).toContain('API offline');
  });
});
