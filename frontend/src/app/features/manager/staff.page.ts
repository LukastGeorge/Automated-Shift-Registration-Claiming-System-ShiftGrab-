import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';

import { Page, Role, UserSummary } from '../../core/api/models';
import { UserApi } from '../../core/api/user-api.service';
import { FormatService } from '../../core/format.service';
import { TranslatePipe } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages';
import { NotifyService } from '../../core/notify.service';
import { LoadErrorComponent } from '../../shared/load-error.component';
import { LoadState } from '../../shared/load-state';

const MAX_RATE = 10_000_000;

/** SG-62: people who have logged in, and their hourly rate (SG-25). */
@Component({
  selector: 'app-staff-page',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTableModule,
    TranslatePipe,
    LoadErrorComponent,
  ],
  templateUrl: './staff.page.html',
  styles: `
    .sub { color: var(--mat-sys-on-surface-variant); max-width: 70ch; }
    .table-wrap { overflow-x: auto; border: 1px solid var(--mat-sys-outline-variant); border-radius: 12px; }
    table { width: 100%; min-width: 640px; }
    .num { font-variant-numeric: tabular-nums; }
    .rate { display: flex; align-items: center; gap: 8px; }
    .rate input { width: 120px; }
    .pager { display: flex; justify-content: center; align-items: center; gap: 12px; margin-top: 16px; }
  `,
})
export class StaffPage implements OnInit {
  private readonly api = inject(UserApi);
  private readonly notify = inject(NotifyService);
  protected readonly fmt = inject(FormatService);

  protected readonly columns = ['name', 'email', 'role', 'rate'];
  protected readonly state = new LoadState<Page<UserSummary>>();
  protected readonly role = signal<Role | undefined>('STAFF');
  protected readonly page = signal(0);
  protected readonly rows = signal<UserSummary[]>([]);
  /** Rates typed but not yet saved, by user id. */
  protected readonly drafts = signal<ReadonlyMap<number, string>>(new Map());
  protected readonly saving = signal<number | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.run(this.api.list(this.role(), this.page()), (page) => this.rows.set(page.content));
  }

  protected setRole(role: Role | undefined): void {
    this.role.set(role);
    this.page.set(0);
    this.load();
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.load();
  }

  protected draft(id: number, value: string): void {
    this.drafts.update((drafts) => new Map(drafts).set(id, value));
  }

  protected validDraft(id: number): number | null {
    const raw = this.drafts().get(id);
    if (raw === undefined || raw.trim() === '') {
      return null;
    }
    const value = Number(raw);
    return Number.isFinite(value) && value >= 0 && value <= MAX_RATE ? Math.round(value * 100) / 100 : null;
  }

  protected save(user: UserSummary): void {
    const rate = this.validDraft(user.id);
    if (rate === null) {
      return;
    }
    this.saving.set(user.id);
    this.api.setHourlyRate(user.id, rate).subscribe({
      next: (updated) => {
        this.saving.set(null);
        this.rows.update((rows) => rows.map((r) => (r.id === updated.id ? updated : r)));
        this.drafts.update((drafts) => {
          const next = new Map(drafts);
          next.delete(user.id);
          return next;
        });
        this.notify.success('staff.saved', { name: updated.fullName });
      },
      error: (err: unknown) => {
        this.saving.set(null);
        this.notify.error(err);
      },
    });
  }

  protected roleKey(role: Role): MessageKey {
    return `role.${role}`;
  }
}
