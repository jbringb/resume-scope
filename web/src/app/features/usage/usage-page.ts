import { Component } from '@angular/core';
import { PagePlaceholder } from '../../shared/page-placeholder';

@Component({
  selector: 'app-usage-page',
  imports: [PagePlaceholder],
  template: `
    <h1>Usage</h1>
    <app-page-placeholder
      icon="monitoring"
      text="This month's token usage and estimated LLM cost against your budget will show here."
    />
  `,
})
export default class UsagePage {}
