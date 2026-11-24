import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { MyShifts, Page, Shift, ShiftStatus } from '../../core/api/models';
import { RegistrationApi } from '../../core/api/registration-api.service';
import { ShiftApi } from '../../core/api/shift-api.service';
import { ClockService } from '../../core/clock.service';
import { NewestWins, RealtimeService, SlotUpdate } from '../../core/realtime.service';
import { FormatService } from '../../core/format.service';
import { TranslatePipe } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages';
import { NotifyService } from '../../core/notify.service';
import { LoadErrorComponent } from '../../shared/load-error.component';
import { LoadState } from '../../shared/load-state';

const PAGE_SIZE = 50;

/** What the claim button of one shift should show, derived from the shift, my shifts and the clock. */
export type ClaimState =
  | { kind: 'mine' }
  | { kind: 'full' }
  | { kind: 'not-open'; opensInMs: number }
  | { kind: 'claimable' };

export function claimState(shift: Shift, mine: boolean, now: number): ClaimState {
  if (mine) {
    return { kind: 'mine' };
  }
  if (shift.status !== 'OPEN' || shift.availableSlots <= 0) {
    return { kind: 'full' };
  }
  const opensInMs = Date.parse(shift.registrationOpenAt) - now;
  return opensInMs > 0 ? { kind: 'not-open', opensInMs } : { kind: 'claimable' };
}

interface Day {
  key: string;
  label: string;
  shifts: Shift[];
}

/** SG-61: upcoming shifts grouped by day, with a claim button per shift (SG-30). */
@Component({
  selector: 'app-open-shifts-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    RouterLink,
    TranslatePipe,
    LoadErrorComponent,
  ],
  templateUrl: './open-shifts.page.html',
  styleUrl: './open-shifts.page.scss',
})
export class OpenShiftsPage implements OnInit {
  private readonly shiftApi = inject(ShiftApi);
  private readonly registrationApi = inject(RegistrationApi);
  private readonly notify = inject(NotifyService);
  protected readonly fmt = inject(FormatService);
  protected readonly clock = inject(ClockService);
  protected readonly live = inject(RealtimeService);
  private readonly newestWins = new NewestWins();
  /** A live update named a shift this list does not show yet (e.g. just created): offer a reload. */
  protected readonly newShifts = signal(false);

  constructor() {
    // SG-40: other people's claims and cancellations move the slot counters without a reload.
    this.live
      .slotUpdates()
      .pipe(takeUntilDestroyed())
      .subscribe((update) => this.applyLive(update));
  }

  protected readonly state = new LoadState<[Page<Shift>, MyShifts]>();
  private readonly shifts = signal<Shift[]>([]);
  private readonly mine = signal<ReadonlySet<number>>(new Set());
  private readonly page = signal<Page<Shift> | undefined>(undefined);
  protected readonly claiming = signal<number | null>(null);

  protected readonly days = computed<Day[]>(() => {
    const byDay = new Map<string, Day>();
    for (const shift of this.shifts()) {
      const key = this.fmt.dayKey(shift.startAt);
      if (!byDay.has(key)) {
        byDay.set(key, { key, label: this.fmt.date(shift.startAt), shifts: [] });
      }
      byDay.get(key)?.shifts.push(shift);
    }
    return [...byDay.values()];
  });

  protected readonly hasMore = computed(() => {
    const page = this.page();
    return !!page && page.page + 1 < page.totalPages;
  });

  ngOnInit(): void {
    this.load();
  }

  private applyLive(update: SlotUpdate): void {
    if (!this.newestWins.accept(update)) {
      return; // an older update overtaken by a newer one
    }
    if (!this.shifts().some((s) => s.id === update.shiftId)) {
      if (update.status === 'OPEN') {
        this.newShifts.set(true);
      }
      return;
    }
    this.patch(update.shiftId, (s) => ({
      ...s,
      bookedSlots: update.bookedSlots,
      maxSlots: update.maxSlots,
      availableSlots: update.availableSlots,
      status: update.status,
    }));
  }

  load(): void {
    this.newShifts.set(false);
    this.state.run(
      forkJoin([
        this.shiftApi.search({ page: 0, size: PAGE_SIZE }),
        this.registrationApi.mine('upcoming', 0, 100, 'CONFIRMED'),
      ]),
      ([page, mine]) => {
        this.page.set(page);
        this.shifts.set(page.content);
        this.mine.set(new Set(mine.registrations.content.map((r) => r.shiftId)));
      },
    );
  }

  loadMore(): void {
    const next = (this.page()?.page ?? 0) + 1;
    this.shiftApi.search({ page: next, size: PAGE_SIZE }).subscribe({
      next: (page) => {
        this.page.set(page);
        this.shifts.update((list) => [...list, ...page.content]);
      },
      error: (err: unknown) => this.notify.error(err),
    });
  }

  protected claimStateOf(shift: Shift): ClaimState {
    return claimState(shift, this.mine().has(shift.id), this.clock.now());
  }

  protected claim(shift: Shift): void {
    this.claiming.set(shift.id);
    this.registrationApi.claim(shift.id).subscribe({
      next: () => {
        this.claiming.set(null);
        this.mine.update((ids) => new Set([...ids, shift.id]));
        // While live updates flow, the server's absolute counters are the truth: the update for this very claim may
        // already have arrived before this HTTP answer, and adding +1 on top of it counted the claim twice (found by
        // the SG-82 E2E test). Only without a live connection do we count our own claim locally.
        if (!this.live.connected()) {
          this.patch(shift.id, (s) => {
            const available = s.availableSlots - 1;
            return { ...s, bookedSlots: s.bookedSlots + 1, availableSlots: available, status: available === 0 ? 'FULL' : s.status };
          });
        }
        this.notify.success('shifts.claimed', { name: shift.name });
      },
      error: (err: unknown) => {
        this.claiming.set(null);
        const problem = this.notify.error(err);
        // Keep the screen truthful without a reload: the server just told us the current state.
        if (problem.code === 'SHIFT_FULL') {
          this.patch(shift.id, (s) => ({ ...s, availableSlots: 0, status: 'FULL' }));
        } else if (problem.code === 'ALREADY_REGISTERED') {
          this.mine.update((ids) => new Set([...ids, shift.id]));
        } else if (problem.code === 'SHIFT_CANCELLED') {
          this.patch(shift.id, (s) => ({ ...s, status: 'CANCELLED' }));
        }
      },
    });
  }

  protected statusKey(status: ShiftStatus): MessageKey {
    return `status.${status}`;
  }

  protected hoursOf(shift: Shift): string {
    return this.fmt.hours((Date.parse(shift.endAt) - Date.parse(shift.startAt)) / 3_600_000);
  }

  private patch(id: number, change: (shift: Shift) => Shift): void {
    this.shifts.update((list) => list.map((s) => (s.id === id ? change(s) : s)));
  }
}
