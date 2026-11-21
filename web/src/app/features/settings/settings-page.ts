import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { getMonthlyUsage, type MonthlyUsageResponse } from '../../api/generated';
import { ApiKeyStore } from '../../core/api-key-store';

type ConnectionCheck =
  { state: 'idle' | 'checking' | 'failed' } | { state: 'ok'; usage: MonthlyUsageResponse };

@Component({
  selector: 'app-settings-page',
  imports: [
    FormField,
    CurrencyPipe,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
  ],
  templateUrl: './settings-page.html',
  styleUrl: './settings-page.scss',
})
export default class SettingsPage {
  private readonly apiKeyStore = inject(ApiKeyStore);

  protected readonly model = signal({ apiKey: this.apiKeyStore.key() });
  protected readonly settingsForm = form(this.model);
  protected readonly check = signal<ConnectionCheck>({ state: 'idle' });
  protected readonly usage = computed(() => {
    const check = this.check();
    return check.state === 'ok' ? check.usage : null;
  });

  protected save(event: Event): void {
    event.preventDefault();
    this.apiKeyStore.set(this.model().apiKey);
    void this.testConnection();
  }

  protected clear(): void {
    this.apiKeyStore.clear();
    this.model.set({ apiKey: '' });
    this.check.set({ state: 'idle' });
  }

  // The usage endpoint needs a valid key when auth is on, so it doubles as a connection check.
  protected async testConnection(): Promise<void> {
    this.check.set({ state: 'checking' });
    const { data } = await getMonthlyUsage();
    this.check.set(data ? { state: 'ok', usage: data } : { state: 'failed' });
  }
}
