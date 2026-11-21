import { HttpErrorResponse } from '@angular/common/http';
import { errorMessage } from './http-error.interceptor';

describe('errorMessage', () => {
  it('uses the ProblemDetail detail from the backend', () => {
    const error = new HttpErrorResponse({
      status: 429,
      error: {
        title: 'Too Many Requests',
        status: 429,
        detail: 'Monthly LLM cost budget exceeded',
      },
    });
    expect(errorMessage(error)).toBe('Monthly LLM cost budget exceeded');
  });

  it('explains a network failure', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toBe(
      'Cannot reach the ResumeScope API.',
    );
  });

  it('falls back to the status code', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 500, error: 'oops' }))).toBe(
      'Request failed (500).',
    );
  });
});
