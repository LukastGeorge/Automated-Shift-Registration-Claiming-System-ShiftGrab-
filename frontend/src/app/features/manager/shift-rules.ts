import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

import { fromZonedInput } from '../../core/format.service';

export const MAX_SHIFT_HOURS = 12;

/**
 * The backend's time rules for a shift (SG-20), checked in the browser first so the manager sees the problem
 * next to the field. The backend checks them again: this is convenience, not enforcement.
 */
export function shiftTimesValidator(
  timeZone: string,
  now: () => number,
  mustStartInFuture: (start: string) => boolean,
): ValidatorFn {
  return (group: AbstractControl): ValidationErrors | null => {
    const { start, end, opensAt } = group.getRawValue() as { start: string; end: string; opensAt: string };
    if (!start || !end || !opensAt) {
      return null;
    }
    const startMs = Date.parse(fromZonedInput(start, timeZone));
    const endMs = Date.parse(fromZonedInput(end, timeZone));
    const opensMs = Date.parse(fromZonedInput(opensAt, timeZone));
    const errors: ValidationErrors = {};
    if (endMs <= startMs) {
      errors['endAfterStart'] = true;
    } else if (endMs - startMs > MAX_SHIFT_HOURS * 3_600_000) {
      errors['maxDuration'] = true;
    }
    if (opensMs >= startMs) {
      errors['opensBeforeStart'] = true;
    }
    if (mustStartInFuture(start) && startMs <= now()) {
      errors['startInFuture'] = true;
    }
    return Object.keys(errors).length ? errors : null;
  };
}

export function notBlank(control: AbstractControl): ValidationErrors | null {
  return typeof control.value === 'string' && control.value.trim() === '' ? { required: true } : null;
}
