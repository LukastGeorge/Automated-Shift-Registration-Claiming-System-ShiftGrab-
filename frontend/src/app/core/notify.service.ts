import { inject, Injectable } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

import { Problem, toProblem } from './api/problem';
import { I18nService, Params } from './i18n/i18n.service';
import { MessageKey } from './i18n/messages';

/** Short messages at the bottom of the screen; errors are translated from the backend's error code. */
@Injectable({ providedIn: 'root' })
export class NotifyService {
  private readonly snackBar = inject(MatSnackBar);
  private readonly i18n = inject(I18nService);

  success(key: MessageKey, params?: Params): void {
    this.snackBar.open(this.i18n.t(key, params), this.i18n.t('app.close'), { duration: 4000 });
  }

  /** Shows the error and returns it, so callers can react to specific codes. */
  error(error: unknown): Problem {
    const problem = toProblem(error);
    this.snackBar.open(this.messageFor(problem), this.i18n.t('app.close'), {
      duration: 6000,
      panelClass: 'snack-error',
    });
    return problem;
  }

  messageFor(problem: Problem): string {
    let message =
      this.i18n.tryT(`error.${problem.code}`) ?? this.i18n.t('error.default', { code: problem.code });
    if (problem.code === 'VALIDATION_FAILED' && problem.errors?.length) {
      message += ' ' + problem.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
    }
    if (problem.retryAfterSeconds) {
      message += ' ' + this.i18n.t('error.retryAfter', { seconds: problem.retryAfterSeconds });
    }
    return message;
  }
}
