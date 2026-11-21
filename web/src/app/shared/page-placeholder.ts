import { Component, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

/** Stand-in body for pages the shell has a route for but no feature yet. */
@Component({
  selector: 'app-page-placeholder',
  imports: [MatCardModule, MatIconModule],
  template: `
    <mat-card appearance="outlined">
      <mat-card-content class="body">
        <mat-icon aria-hidden="true">{{ icon() }}</mat-icon>
        <p>{{ text() }}</p>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .body {
      display: flex;
      align-items: center;
      gap: 12px;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class PagePlaceholder {
  readonly icon = input('construction');
  readonly text = input.required<string>();
}
