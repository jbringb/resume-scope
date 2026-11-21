# CLAUDE.md — ResumeScope web (Angular)

The repo-root `CLAUDE.md` covers the backend and the API. This file adds the frontend rules. The generic Angular guidance below came from `ng new --ai-config claude-code`.

## Project rules

- **API calls go through the generated client** in `src/app/api/generated/` (`@hey-api/openapi-ts`, configured in `openapi-ts.config.ts`).
  - Use its SDK functions (`listJobRoles()`), `*Resource` helpers (signal-based `httpResource`) or `*Request` builders.
  - Never hand-write API types or URLs, and never edit the generated folder: it's gitignored and regenerated from `../src/main/resources/static/openapi.json` before every `start`, `build` and `test`.
  - If the API needs to change, change the backend and `openapi.json` first, then regenerate with `npm run generate:api`.
- **Auth:** `ApiKeyStore` holds the key, and `apiKeyInterceptor` adds `X-API-Key` to `/api/` URLs only. Keep the key out of URLs, logs and `localStorage`.
- **Errors:** `httpErrorInterceptor` already shows the backend's ProblemDetail `detail` in a snackbar for `/api` failures. Don't repeat that per page.
- **Pages** live in `src/app/features/<feature>/<name>-page.ts` as default-exported components, lazy-loaded in `app.routes.ts` with a `title`. Shared UI goes in `src/app/shared/`, and app-wide services and interceptors in `src/app/core/`.
- **Material:** use Angular Material components and `--mat-sys-*` CSS variables for colour and typography; no hard-coded colours. Icons are Material Symbols (`<mat-icon>name</mat-icon>`).
- **Verify before finishing:** `npm run format && npm run lint && npm test -- --watch=false && npm run build`. Tests use Vitest with `HttpTestingController`; a component that loads data needs its requests flushed before `whenStable()`.

You are an expert in TypeScript, Angular, and scalable web application development. You write functional, maintainable, performant, and accessible code following Angular and TypeScript best practices.

## TypeScript Best Practices

- Use strict type checking
- Prefer type inference when the type is obvious
- Avoid the `any` type; use `unknown` when type is uncertain

## Angular Best Practices

- Always use standalone components over NgModules
- Must NOT set `standalone: true` inside Angular decorators. It's the default in Angular v20+.
- Do NOT set `changeDetection: ChangeDetectionStrategy.OnPush` explicitly. `OnPush` is the default in Angular v22+.
- Use signals for state management
- Implement lazy loading for feature routes
- Do NOT use the `@HostBinding` and `@HostListener` decorators. Put host bindings inside the `host` object of the `@Component` or `@Directive` decorator instead
- Use `NgOptimizedImage` for all static images.
  - `NgOptimizedImage` does not work for inline base64 images.

## Accessibility Requirements

- It MUST pass all AXE checks.
- It MUST follow all WCAG AA minimums, including focus management, color contrast, and ARIA attributes.

## Components

- Keep components small and focused on a single responsibility
- Use `input()` and `output()` functions instead of decorators
- Use `model()` for two-way bound properties with `[(prop)]` syntax instead of pairing `input()` with `output()`
- Use `computed()` for derived state
- Use `linkedSignal()` for state derived from multiple reactive sources that must stay synchronized
- Prefer inline templates for small components
- Prefer Signal Forms (`@angular/forms/signals`) for new forms. They are stable in Angular v22+ and provide signal-based state, type-safe field access, and schema-based validation
- When not using Signal Forms, prefer Reactive forms instead of Template-driven ones
- Do NOT use `ngClass`, use `class` bindings instead
- Do NOT use `ngStyle`, use `style` bindings instead
- Do NOT import `CommonModule`, import only the directives and pipes the template uses, such as `AsyncPipe` or `DatePipe`
- When using external templates/styles, use paths relative to the component TS file.

## State Management

- Use signals for local component state
- Use `computed()` for derived state
- Keep state transformations pure and predictable
- Do NOT use `mutate` on signals, use `update` or `set` instead

## Templates

- Keep templates simple and avoid complex logic
- Use native control flow (`@if`, `@for`, `@switch`) instead of `*ngIf`, `*ngFor`, `*ngSwitch`
- Use the async pipe to handle observables
- Do not assume globals like (`new Date()`) are available.

## Services

- Design services around a single responsibility
- Prefer the `@Service` decorator over `@Injectable({providedIn: 'root'})` for new singleton services (Angular v22+)
- Use the `inject()` function instead of constructor injection
