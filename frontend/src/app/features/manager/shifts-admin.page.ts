import { Component, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { filter, switchMap } from 'rxjs';

import { Page, Shift, ShiftStatus } from '../../core/api/models';
import { ShiftApi } from '../../core/api/shift-api.service';
import { addDays, FormatService, fromZonedInput, toZonedInput } from '../../core/format.service';
import { I18nService, TranslatePipe } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages';
import { NotifyService } from '../../core/notify.service';
import { NewestWins, RealtimeService } from '../../core/realtime.service';
import { LoadErrorComponent } from '../../shared/load-error.component';
import { LoadState } from '../../shared/load-state';
import { CancelShiftDialog } from './cancel-shift.dialog';
import { ShiftFormData, ShiftFormDialog, ShiftFormResult } from './shift-form.dialog';

const STATUSES: ShiftStatus[] = ['OPEN', 'FULL', 'CANCELLED'];

/** SG-62: shifts in a date range with create (SG-20), edit (SG-21) and cancel (SG-22). */
@Component({
  selector: 'app-shifts-admin-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    TranslatePipe,
    LoadErrorComponent,
  ],
  templateUrl: './shifts-admin.page.html',
  styleUrl: './shifts-admin.page.scss',
})
export class ShiftsAdminPage implements OnInit {
  private readonly api = inject(ShiftApi);
  private readonly dialog = inject(MatDialog);
  private readonly notify = inject(NotifyService);
  private readonly i18n = inject(I18nService);
  protected readonly fmt = inject(FormatService);

  protected readonly statuses = STATUSES;
  protected readonly columns = ['time', 'name', 'slots', 'opens', 'status', 'actions'];
  protected readonly state = new LoadState<Page<Shift>>();

  private readonly today = toZonedInput(new Date().toISOString(), this.fmt.timeZone).slice(0, 10);
  protected readonly from = signal(this.today);
  protected readonly to = signal(addDays(this.today, 13));
  protected readonly status = signal<ShiftStatus[]>([]);
  protected readonly page = signal(0);

  private readonly newestWins = new NewestWins();

  constructor() {
    // SG-40: booked counters and statuses move while the manager watches.
    inject(RealtimeService)
      .slotUpdates()
      .pipe(takeUntilDestroyed())
      .subscribe((update) => {
        if (!this.newestWins.accept(update)) {
          return;
        }
        this.state.data.update((page) =>
          page && {
            ...page,
            content: page.content.map((s) =>
              s.id === update.shiftId
                ? { ...s, bookedSlots: update.bookedSlots, maxSlots: update.maxSlots, availableSlots: update.availableSlots, status: update.status }
                : s,
            ),
          },
        );
      });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    const tz = this.fmt.timeZone;
    this.state.run(
      this.api.search({
        from: fromZonedInput(`${this.from()}T00:00`, tz),
        // The "to" day is included: query up to the next midnight.
        to: fromZonedInput(`${addDays(this.to(), 1)}T00:00`, tz),
        status: this.status(),
        page: this.page(),
        size: 20,
      }),
    );
  }

  protected applyFilters(): void {
    this.page.set(0);
    this.load();
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.load();
  }

  protected editable(shift: Shift): boolean {
    return shift.status !== 'CANCELLED' && Date.parse(shift.startAt) > Date.now();
  }

  protected openForm(shift?: Shift): void {
    this.dialog
      .open<ShiftFormDialog, ShiftFormData, ShiftFormResult>(ShiftFormDialog, { data: { shift }, autoFocus: 'first-tabbable' })
      .afterClosed()
      .subscribe((result) => {
        if (result === 'stale') {
          this.notify.success('admin.reloaded');
        }
        if (result) {
          this.load();
        }
      });
  }

  protected cancel(shift: Shift): void {
    this.dialog
      .open<CancelShiftDialog, Shift, { reason: string } | undefined>(CancelShiftDialog, { data: shift })
      .afterClosed()
      .pipe(
        filter((result): result is { reason: string } => !!result),
        switchMap(({ reason }) => this.api.cancel(shift.id, reason || undefined)),
      )
      .subscribe({
        next: () => {
          this.notify.success('admin.cancelled', { name: shift.name });
          this.load();
        },
        error: (err: unknown) => {
          this.notify.error(err);
          this.load();
        },
      });
  }

  protected statusKey(status: ShiftStatus): MessageKey {
    return `status.${status}`;
  }

  protected statusLabel(status: ShiftStatus): string {
    return this.i18n.t(this.statusKey(status));
  }
}
