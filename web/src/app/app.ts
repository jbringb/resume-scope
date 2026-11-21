import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { httpResource } from '@angular/common/http';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';

interface NavItem {
  path: string;
  label: string;
  icon: string;
}

/** App shell: toolbar, navigation (side panel on desktop, drawer on phones) and the routed page. */
@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly nav: NavItem[] = [
    { path: '/job-roles', label: 'Job roles', icon: 'work' },
    { path: '/usage', label: 'Usage', icon: 'monitoring' },
    { path: '/settings', label: 'Settings', icon: 'settings' },
  ];

  protected readonly handset = toSignal(
    inject(BreakpointObserver)
      .observe(Breakpoints.Handset)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  // /health is open (no API key), so it shows whether the backend is reachable at all.
  private readonly health = httpResource<{ status: string }>(() => '/health');
  protected readonly apiStatus = computed(() => {
    if (this.health.isLoading()) {
      return 'checking';
    }
    return this.health.hasValue() && this.health.value().status === 'UP' ? 'online' : 'offline';
  });
}
