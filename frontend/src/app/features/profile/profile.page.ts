import { Component, inject, OnInit } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { IdentityApi } from '../../core/api/identity-api.service';
import { Me, Role } from '../../core/api/models';
import { FormatService } from '../../core/format.service';
import { TranslatePipe } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages';
import { LoadErrorComponent } from '../../shared/load-error.component';
import { LoadState } from '../../shared/load-state';

/** Shows the profile stored by the backend; the first visit also proves login → token → API works end to end. */
@Component({
  selector: 'app-profile-page',
  imports: [MatCardModule, MatProgressBarModule, TranslatePipe, LoadErrorComponent],
  template: `
    <h1>{{ 'profile.title' | t }}</h1>
    @if (state.loading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'app.loading' | t" />
    }
    @if (state.error(); as problem) {
      <app-load-error [problem]="problem" (retry)="load()" />
    }
    @if (state.data(); as me) {
      <mat-card appearance="outlined" class="card">
        <mat-card-header>
          <mat-card-title data-testid="profile-name">{{ me.fullName }}</mat-card-title>
          <mat-card-subtitle>{{ roleKey(me.role) | t }}</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <dl>
            <dt>{{ 'profile.email' | t }}</dt>
            <dd>{{ me.email }}</dd>
            <dt>{{ 'profile.hourlyRate' | t }}</dt>
            <dd>{{ 'profile.perHour' | t: { amount: fmt.money(me.hourlyRate) } }}</dd>
          </dl>
          <p class="hint">{{ 'profile.managedByKeycloak' | t }}</p>
        </mat-card-content>
      </mat-card>
    }
  `,
  styles: `
    .card { max-width: 520px; }
    dl { display: grid; grid-template-columns: max-content 1fr; gap: 8px 24px; margin: 16px 0; }
    dt { color: var(--mat-sys-on-surface-variant); }
    dd { margin: 0; }
    .hint { color: var(--mat-sys-on-surface-variant); font: var(--mat-sys-body-small); }
  `,
})
export class ProfilePage implements OnInit {
  private readonly api = inject(IdentityApi);
  protected readonly fmt = inject(FormatService);
  protected readonly state = new LoadState<Me>();

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.run(this.api.me());
  }

  protected roleKey(role: Role): MessageKey {
    return `role.${role}`;
  }
}
