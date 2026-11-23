import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';

import { AUDIT_ACTIONS, AuditAction, AuditEntry, Page } from '../../core/api/models';
import { ReportApi } from '../../core/api/report-api.service';
import { addDays, FormatService, fromZonedInput } from '../../core/format.service';
import { TranslatePipe } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages';
import { LoadErrorComponent } from '../../shared/load-error.component';
import { LoadState } from '../../shared/load-state';

/** One readable line per detail: changes as `field: from → to`, lists joined, values as they are. */
export function summarizeDetails(details: Record<string, unknown> | null): string[] {
  if (!details) {
    return [];
  }
  return Object.entries(details).map(([key, value]) => {
    if (value && typeof value === 'object' && !Array.isArray(value) && 'from' in value && 'to' in value) {
      const change = value as { from: unknown; to: unknown };
      return `${key}: ${show(change.from)} → ${show(change.to)}`;
    }
    return `${key}: ${show(value)}`;
  });
}

function show(value: unknown): string {
  if (value === null || value === undefined) {
    return '–';
  }
  return Array.isArray(value) ? value.join(', ') : String(value);
}

/** SG-64: who changed what and when (SG-52), newest first, filtered by action, person and days. */
@Component({
  selector: 'app-audit-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    TranslatePipe,
    LoadErrorComponent,
  ],
  templateUrl: './audit.page.html',
  styleUrl: './audit.page.scss',
})
export class AuditPage implements OnInit {
  private readonly api = inject(ReportApi);
  protected readonly fmt = inject(FormatService);

  protected readonly actions = AUDIT_ACTIONS;
  protected readonly state = new LoadState<Page<AuditEntry>>();
  protected readonly action = signal<AuditAction | ''>('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly actor = signal<{ id: number; name: string } | null>(null);
  protected readonly page = signal(0);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const tz = this.fmt.timeZone;
    this.state.run(
      this.api.audit({
        action: this.action() || undefined,
        actorId: this.actor()?.id,
        from: this.from() ? fromZonedInput(`${this.from()}T00:00`, tz) : undefined,
        to: this.to() ? fromZonedInput(`${addDays(this.to(), 1)}T00:00`, tz) : undefined,
        page: this.page(),
      }),
    );
  }

  protected apply(): void {
    this.page.set(0);
    this.load();
  }

  protected filterByActor(entry: AuditEntry): void {
    if (entry.actorId !== null) {
      this.actor.set({ id: entry.actorId, name: entry.actorName ?? `#${entry.actorId}` });
      this.apply();
    }
  }

  protected clearActor(): void {
    this.actor.set(null);
    this.apply();
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.load();
  }

  protected actionKey(action: AuditAction): MessageKey {
    return `audit.act.${action}`;
  }

  protected targetKey(entry: AuditEntry): MessageKey {
    return `audit.target.${entry.targetType}`;
  }

  protected details(entry: AuditEntry): string[] {
    return summarizeDetails(entry.details);
  }
}
