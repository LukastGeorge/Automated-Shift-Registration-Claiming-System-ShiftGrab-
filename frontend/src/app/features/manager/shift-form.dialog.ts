import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { Shift, ShiftChanges } from '../../core/api/models';
import { ShiftApi } from '../../core/api/shift-api.service';
import { addDays, FormatService, fromZonedInput, toZonedInput } from '../../core/format.service';
import { TranslatePipe } from '../../core/i18n/i18n.service';
import { NotifyService } from '../../core/notify.service';
import { notBlank, shiftTimesValidator } from './shift-rules';

export interface ShiftFormData {
  shift?: Shift;
}

/** `Shift` when saved, `'stale'` when someone else changed the shift meanwhile (the list must reload). */
export type ShiftFormResult = Shift | 'stale' | undefined;

/** Create (SG-20) or edit (SG-21) a shift. Times are typed in the organisation zone. */
@Component({
  selector: 'app-shift-form-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, TranslatePipe],
  templateUrl: './shift-form.dialog.html',
  styles: `
    form { display: grid; gap: 4px; min-width: min(480px, 80vw); }
    .row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .note { color: var(--mat-sys-on-surface-variant); font: var(--mat-sys-body-small); margin: 0 0 8px; }
    .error { color: var(--mat-sys-error); font: var(--mat-sys-body-small); margin: 0; }
    @media (max-width: 520px) { .row { grid-template-columns: 1fr; } }
  `,
})
export class ShiftFormDialog {
  private readonly api = inject(ShiftApi);
  private readonly notify = inject(NotifyService);
  private readonly ref = inject<MatDialogRef<ShiftFormDialog, ShiftFormResult>>(MatDialogRef);
  protected readonly fmt = inject(FormatService);

  protected readonly shift = inject<ShiftFormData>(MAT_DIALOG_DATA).shift;
  protected readonly isNew = !this.shift;
  /** BR-09: start and end are frozen once staff hold the shift. */
  protected readonly timesLocked = (this.shift?.bookedSlots ?? 0) > 0;
  /** BR-08: never fewer slots than people already booked. */
  protected readonly minSlots = Math.max(1, this.shift?.bookedSlots ?? 1);
  protected readonly saving = signal(false);
  protected readonly noChanges = signal(false);

  private readonly initial = this.initialValue();

  protected readonly form = new FormGroup(
    {
      name: new FormControl(this.initial.name, { nonNullable: true, validators: [Validators.required, notBlank, Validators.maxLength(100)] }),
      start: new FormControl({ value: this.initial.start, disabled: this.timesLocked }, { nonNullable: true, validators: Validators.required }),
      end: new FormControl({ value: this.initial.end, disabled: this.timesLocked }, { nonNullable: true, validators: Validators.required }),
      opensAt: new FormControl(this.initial.opensAt, { nonNullable: true, validators: Validators.required }),
      maxSlots: new FormControl(this.initial.maxSlots, {
        nonNullable: true,
        validators: [Validators.required, Validators.min(this.minSlots), Validators.max(500)],
      }),
    },
    {
      validators: shiftTimesValidator(
        this.fmt.timeZone,
        () => Date.now(),
        // An existing shift that keeps its start time may already be close to it; only a new or moved start must be future.
        (start) => this.isNew || start !== this.initial.start,
      ),
    },
  );

  protected submit(): void {
    this.noChanges.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const tz = this.fmt.timeZone;
    if (!this.shift) {
      this.save(
        this.api.create({
          name: value.name.trim(),
          startAt: fromZonedInput(value.start, tz),
          endAt: fromZonedInput(value.end, tz),
          registrationOpenAt: fromZonedInput(value.opensAt, tz),
          maxSlots: value.maxSlots,
        }),
        'admin.created',
      );
      return;
    }
    // PATCH sends only what changed, so an untouched field can never overwrite someone else's edit.
    const changes: ShiftChanges = {};
    if (value.name.trim() !== this.initial.name) changes.name = value.name.trim();
    if (value.start !== this.initial.start) changes.startAt = fromZonedInput(value.start, tz);
    if (value.end !== this.initial.end) changes.endAt = fromZonedInput(value.end, tz);
    if (value.opensAt !== this.initial.opensAt) changes.registrationOpenAt = fromZonedInput(value.opensAt, tz);
    if (value.maxSlots !== this.initial.maxSlots) changes.maxSlots = value.maxSlots;
    if (Object.keys(changes).length === 0) {
      this.noChanges.set(true);
      return;
    }
    this.save(this.api.update(this.shift.id, this.shift.version, changes), 'admin.saved');
  }

  private save(request: ReturnType<ShiftApi['create']>, successKey: 'admin.created' | 'admin.saved'): void {
    this.saving.set(true);
    request.subscribe({
      next: (saved) => {
        this.notify.success(successKey, { name: saved.name });
        this.ref.close(saved);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        const problem = this.notify.error(err);
        if (problem.code === 'CONCURRENT_MODIFICATION' || problem.code === 'SHIFT_NOT_FOUND') {
          this.ref.close('stale');
        }
      },
    });
  }

  private initialValue() {
    const tz = this.fmt.timeZone;
    if (this.shift) {
      return {
        name: this.shift.name,
        start: toZonedInput(this.shift.startAt, tz),
        end: toZonedInput(this.shift.endAt, tz),
        opensAt: toZonedInput(this.shift.registrationOpenAt, tz),
        maxSlots: this.shift.maxSlots,
      };
    }
    const nowLocal = toZonedInput(new Date().toISOString(), tz);
    const tomorrow = addDays(nowLocal.slice(0, 10), 1);
    return { name: '', start: `${tomorrow}T08:00`, end: `${tomorrow}T12:00`, opensAt: nowLocal, maxSlots: 3 };
  }
}
