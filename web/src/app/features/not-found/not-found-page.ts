import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found-page',
  imports: [MatButtonModule, RouterLink],
  template: `
    <h1>Page not found</h1>
    <p>There's nothing at this address.</p>
    <a mat-flat-button routerLink="/">Go to job roles</a>
  `,
})
export default class NotFoundPage {}
