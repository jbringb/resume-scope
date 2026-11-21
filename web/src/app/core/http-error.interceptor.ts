import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import type { ProblemDetail } from '../api/generated';

/**
 * Shows API errors in a snackbar. The backend returns RFC 9457 ProblemDetail bodies, so `detail`
 * carries the human-readable reason (e.g. "Monthly LLM cost budget of €5 exceeded").
 */
export const httpErrorInterceptor: HttpInterceptorFn = (req, next) => {
  const snackBar = inject(MatSnackBar);
  const router = inject(Router);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && req.url.startsWith('/api/')) {
        if (error.status === 401) {
          snackBar
            .open('API key missing or invalid.', 'Settings', { duration: 8000 })
            .onAction()
            .subscribe(() => void router.navigate(['/settings']));
        } else {
          snackBar.open(errorMessage(error), 'Dismiss', { duration: 8000 });
        }
      }
      return throwError(() => error);
    }),
  );
};

export function errorMessage(error: HttpErrorResponse): string {
  const problem = error.error as Partial<ProblemDetail> | null;
  if (problem && typeof problem === 'object' && typeof problem.detail === 'string') {
    return problem.detail;
  }
  return error.status === 0
    ? 'Cannot reach the ResumeScope API.'
    : `Request failed (${error.status}).`;
}
