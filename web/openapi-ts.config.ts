import { defineConfig } from '@hey-api/openapi-ts';

// Typed API client generated from the backend's OpenAPI spec (the source of truth), like jOOQ on the
// backend: output goes to a gitignored folder and is regenerated before every build, serve and test,
// so a spec change that breaks the frontend fails its build instead of drifting silently.
export default defineConfig({
  input: '../src/main/resources/static/openapi.json',
  output: 'src/app/api/generated',
  plugins: [
    // The SPA is served by the API itself (or proxied by `ng serve`), so calls stay same-origin
    // instead of using the spec's `servers` URL (localhost:8086).
    { name: '@hey-api/client-angular', baseUrl: false },
    '@hey-api/sdk',
    '@angular/common',
  ],
});
