import { Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { Problem } from '../core/api/problem';
import { TranslatePipe } from '../core/i18n/i18n.service';
import { NotifyService } from '../core/notify.service';

/** Inline error block with a retry button, for a page whose data could not be loaded. */
@Component({
  selector: 'app-load-error',
  imports: [MatButtonModule, MatIconModule, TranslatePipe],
  template: `
    <div class="error" role="alert">
      <mat-icon aria-hidden="true">error</mat-icon>
      <span>{{ message() }}</span>
      <button mat-stroked-button type="button" (click)="retry.emit()">{{ 'app.retry' | t }}</button>
    </div>
  `,
  styles: `
    .error {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
      padding: 16px;
      border-radius: 12px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
  `,
})
export class LoadErrorComponent {
  private readonly notify = inject(NotifyService);
  readonly problem = input.required<Problem>();
  readonly retry = output<void>();
  protected readonly message = computed(() => this.notify.messageFor(this.problem()));
}
