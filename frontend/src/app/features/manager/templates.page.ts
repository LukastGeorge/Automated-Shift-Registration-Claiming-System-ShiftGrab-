import { Component, inject, OnInit, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { ShiftTemplate } from '../../core/api/models';
import { TemplateApi } from '../../core/api/template-api.service';
import { addDays, FormatService, isoDurationDays, toZonedInput } from '../../core/format.service';
import { TranslatePipe } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages';
import { NotifyService } from '../../core/notify.service';
import { LoadErrorComponent } from '../../shared/load-error.component';
import { LoadState } from '../../shared/load-state';
import { notBlank } from './shift-rules';

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7];

function dateRange(group: AbstractControl): ValidationErrors | null {
  const { validFrom, validTo } = group.getRawValue() as { validFrom: string; validTo: string };
  return validFrom && validTo && validTo < validFrom ? { toAfterFrom: true } : null;
}

/** SG-62: recurring shift templates (SG-23) and idempotent generation of their shifts. */
@Component({
  selector: 'app-templates-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    TranslatePipe,
    LoadErrorComponent,
  ],
  templateUrl: './templates.page.html',
  styleUrl: './templates.page.scss',
})
export class TemplatesPage implements OnInit {
  private readonly api = inject(TemplateApi);
  private readonly notify = inject(NotifyService);
  protected readonly fmt = inject(FormatService);

  protected readonly weekdays = WEEKDAYS;
  protected readonly state = new LoadState<ShiftTemplate[]>();
  protected readonly showForm = signal(false);
  protected readonly saving = signal(false);
  protected readonly generating = signal<number | null>(null);

  private readonly today = toZonedInput(new Date().toISOString(), this.fmt.timeZone).slice(0, 10);

  protected readonly form = new FormGroup(
    {
      name: new FormControl('', { nonNullable: true, validators: [Validators.required, notBlank, Validators.maxLength(100)] }),
      weekdays: new FormControl<number[]>([1, 2, 3, 4, 5], { nonNullable: true, validators: Validators.required }),
      startTime: new FormControl('08:00', { nonNullable: true, validators: Validators.required }),
      endTime: new FormControl('12:00', { nonNullable: true, validators: Validators.required }),
      maxSlots: new FormControl(3, { nonNullable: true, validators: [Validators.required, Validators.min(1), Validators.max(500)] }),
      openBeforeDays: new FormControl(7, { nonNullable: true, validators: [Validators.required, Validators.min(0), Validators.max(60)] }),
      validFrom: new FormControl(addDays(this.today, 1), { nonNullable: true, validators: Validators.required }),
      validTo: new FormControl(addDays(this.today, 28), { nonNullable: true, validators: Validators.required }),
    },
    { validators: dateRange },
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.run(this.api.list());
  }

  protected create(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .create({
        name: v.name.trim(),
        weekdays: [...v.weekdays].sort(),
        startTime: v.startTime,
        endTime: v.endTime,
        maxSlots: v.maxSlots,
        openBefore: `P${v.openBeforeDays}D`,
        validFrom: v.validFrom,
        validTo: v.validTo,
      })
      .subscribe({
        next: (template) => {
          this.saving.set(false);
          this.showForm.set(false);
          this.form.controls.name.reset('');
          this.notify.success('tpl.created', { name: template.name });
          this.load();
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.notify.error(err);
        },
      });
  }

  protected generate(template: ShiftTemplate): void {
    this.generating.set(template.id);
    this.api.generate(template.id).subscribe({
      next: (result) => {
        this.generating.set(null);
        this.notify.success('tpl.generated', {
          created: result.createdShiftIds.length,
          existing: result.skippedExisting,
          past: result.skippedPast,
        });
      },
      error: (err: unknown) => {
        this.generating.set(null);
        this.notify.error(err);
      },
    });
  }

  protected weekdayKey(day: number): MessageKey {
    return `weekday.${day}` as MessageKey;
  }

  protected openBeforeDays(template: ShiftTemplate): string {
    return this.fmt.hours(isoDurationDays(template.openBefore));
  }

  /** LocalDate from the API, shown as a calendar date (noon UTC keeps the same day in every zone). */
  protected day(date: string): string {
    return this.fmt.date(`${date}T12:00:00Z`);
  }
}
