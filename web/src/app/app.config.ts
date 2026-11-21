import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { MatIconRegistry } from '@angular/material/icon';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { client } from './api/generated/client.gen';
import { provideHeyApiClient } from './api/generated/client/client.gen';
import { routes } from './app.routes';
import { apiKeyInterceptor } from './core/api-key.interceptor';
import { httpErrorInterceptor } from './core/http-error.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([apiKeyInterceptor, httpErrorInterceptor])),
    // The generated API client sends its requests through Angular's HttpClient, so the
    // interceptors above apply to it too.
    provideHeyApiClient(client),
    provideAppInitializer(() => {
      inject(MatIconRegistry).setDefaultFontSetClass('material-symbols-outlined');
    }),
  ],
};
