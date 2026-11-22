import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { Shift } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/i18n.service';

/** Confirms cancelling a shift (SG-22) and collects an optional reason for the affected staff. Closes with the reason. */
@Component({
  selector: 'app-cancel-shift-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, TranslatePipe],
  template: `
    <h2 mat-dialog-title>{{ 'admin.cancelTitle' | t: { name: shift.name } }}</h2>
    <mat-dialog-content>
      <p>{{ 'admin.cancelBody' | t: { booked: shift.bookedSlots } }}</p>
      <mat-form-field class="full">
        <mat-label>{{ 'admin.reason' | t }}</mat-label>
        <textarea matInput [formControl]="reason" rows="3" maxlength="500" data-testid="reason"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>{{ 'app.keep' | t }}</button>
      <button mat-flat-button type="button" class="danger" [mat-dialog-close]="{ reason: reason.value.trim() }" data-testid="confirm">
        {{ 'admin.cancelShift' | t }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .full { width: 100%; }
    .danger { --mat-button-filled-container-color: var(--mat-sys-error); --mat-button-filled-label-text-color: var(--mat-sys-on-error); }
  `,
})
export class CancelShiftDialog {
  protected readonly shift = inject<Shift>(MAT_DIALOG_DATA);
  protected readonly reason = new FormControl('', { nonNullable: true, validators: Validators.maxLength(500) });
}
