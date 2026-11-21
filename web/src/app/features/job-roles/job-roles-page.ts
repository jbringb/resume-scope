import { Component } from '@angular/core';
import { PagePlaceholder } from '../../shared/page-placeholder';

@Component({
  selector: 'app-job-roles-page',
  imports: [PagePlaceholder],
  template: `
    <h1>Job roles</h1>
    <app-page-placeholder
      icon="work"
      text="Create job roles, upload candidate CVs and run the AI analysis here."
    />
  `,
})
export default class JobRolesPage {}
