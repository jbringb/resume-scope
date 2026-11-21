import { Routes } from '@angular/router';

// Every page is lazy-loaded, so each feature ships as its own chunk.
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'job-roles' },
  {
    path: 'job-roles',
    title: 'Job roles · ResumeScope',
    loadComponent: () => import('./features/job-roles/job-roles-page'),
  },
  {
    path: 'usage',
    title: 'Usage · ResumeScope',
    loadComponent: () => import('./features/usage/usage-page'),
  },
  {
    path: 'settings',
    title: 'Settings · ResumeScope',
    loadComponent: () => import('./features/settings/settings-page'),
  },
  {
    path: '**',
    title: 'Not found · ResumeScope',
    loadComponent: () => import('./features/not-found/not-found-page'),
  },
];
