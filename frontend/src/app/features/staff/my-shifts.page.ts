import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';

import { MyShift, MyShifts, RegistrationStatus } from '../../core/api/models';
import { RegistrationApi } from '../../core/api/registration-api.service';
import { FormatService } from '../../core/format.service';
import { I18nService, TranslatePipe } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages';
import { NotifyService } from '../../core/notify.service';
import { ConfirmData, ConfirmDialogComponent } from '../../shared/confirm-dialog.component';
import { LoadErrorComponent } from '../../shared/load-error.component';
import { LoadState } from '../../shared/load-state';

type Scope = 'upcoming' | 'past';

/** SG-61: my upcoming/past registrations, this week's hours against the limit (SG-32) and cancel (SG-31). */
@Component({
  selector: 'app-my-shifts-page',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
    TranslatePipe,
    LoadErrorComponent,
  ],
  templateUrl: './my-shifts.page.html',
  styleUrl: './my-shifts.page.scss',
})
export class MyShiftsPage implements OnInit {
  private readonly api = inject(RegistrationApi);
  private readonly dialog = inject(MatDialog);
  private readonly notify = inject(NotifyService);
  private readonly i18n = inject(I18nService);
  protected readonly fmt = inject(FormatService);

  protected readonly state = new LoadState<MyShifts>();
  protected readonly scope = signal<Scope>('upcoming');
  protected readonly page = signal(0);
  protected readonly cancelling = signal<number | null>(null);

  protected readonly week = computed(() => this.state.data()?.currentWeek);
  protected readonly items = computed(() => this.state.data()?.registrations.content ?? []);
  protected readonly totalPages = computed(() => this.state.data()?.registrations.totalPages ?? 0);
  protected readonly weekPercent = computed(() => {
    const week = this.week();
    return week && week.limitHours > 0 ? Math.min(100, (100 * week.confirmedHours) / week.limitHours) : 0;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.run(this.api.mine(this.scope(), this.page()));
  }

  protected setScope(scope: Scope): void {
    this.scope.set(scope);
    this.page.set(0);
    this.load();
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.load();
  }

  protected cancel(item: MyShift): void {
    const name = item.shiftName ?? `#${item.shiftId}`;
    const data: ConfirmData = {
      title: this.i18n.t('my.confirmTitle'),
      body: this.i18n.t('my.confirmBody', { name, time: this.fmt.dateTime(item.startAt) }),
      confirm: this.i18n.t('my.cancel'),
      cancel: this.i18n.t('app.keep'),
    };
    this.dialog
      .open(ConfirmDialogComponent, { data })
      .afterClosed()
      .pipe(
        filter((confirmed) => confirmed === true),
        switchMap(() => {
          this.cancelling.set(item.id);
          return this.api.cancel(item.id);
        }),
      )
      .subscribe({
        next: () => {
          this.cancelling.set(null);
          this.notify.success('my.cancelled');
          this.load();
        },
        error: (err: unknown) => {
          this.cancelling.set(null);
          this.notify.error(err);
          this.load();
        },
      });
  }

  protected statusKey(status: RegistrationStatus): MessageKey {
    return `reg.${status}`;
  }
}
