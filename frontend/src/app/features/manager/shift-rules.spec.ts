import { FormControl, FormGroup } from '@angular/forms';

import { addDays, isoDurationDays } from '../../core/format.service';
import { notBlank, shiftTimesValidator } from './shift-rules';

describe('shiftTimesValidator', () => {
  const now = Date.parse('2030-01-01T00:00:00Z');

  function errors(start: string, end: string, opensAt: string, mustBeFuture = true) {
    const group = new FormGroup({
      start: new FormControl(start),
      end: new FormControl(end),
      opensAt: new FormControl(opensAt),
    });
    return shiftTimesValidator('Asia/Ho_Chi_Minh', () => now, () => mustBeFuture)(group);
  }

  it('accepts a valid shift', () => {
    expect(errors('2030-01-02T08:00', '2030-01-02T12:00', '2030-01-01T09:00')).toBeNull();
  });

  it('applies the backend time rules', () => {
    expect(errors('2030-01-02T12:00', '2030-01-02T08:00', '2030-01-01T09:00')).toEqual({ endAfterStart: true });
    expect(errors('2030-01-02T06:00', '2030-01-02T19:00', '2030-01-01T09:00')).toEqual({ maxDuration: true });
    expect(errors('2030-01-02T08:00', '2030-01-02T12:00', '2030-01-02T08:00')).toEqual({ opensBeforeStart: true });
    // 2030-01-01T06:59 in Ho Chi Minh City is 23:59 UTC the day before, i.e. in the past.
    expect(errors('2030-01-01T06:59', '2030-01-01T10:00', '2029-12-31T09:00')).toEqual({ startInFuture: true });
    expect(errors('2030-01-01T06:59', '2030-01-01T10:00', '2029-12-31T09:00', false)).toBeNull();
  });

  it('allows an overnight shift of up to 12 hours', () => {
    expect(errors('2030-01-02T22:00', '2030-01-03T06:00', '2030-01-01T09:00')).toBeNull();
  });

  it('waits until every time is filled in', () => {
    expect(errors('', '2030-01-02T12:00', '2030-01-01T09:00')).toBeNull();
  });
});

describe('helpers', () => {
  it('rejects names made of spaces', () => {
    expect(notBlank(new FormControl('   '))).toEqual({ required: true });
    expect(notBlank(new FormControl('Bar'))).toBeNull();
  });

  it('adds calendar days across month ends', () => {
    expect(addDays('2030-01-31', 1)).toBe('2030-02-01');
    expect(addDays('2030-03-01', -1)).toBe('2030-02-28');
  });

  it('reads ISO durations as days', () => {
    expect(isoDurationDays('P7D')).toBe(7);
    expect(isoDurationDays('PT168H')).toBe(7);
    expect(isoDurationDays('P1DT12H')).toBe(1.5);
    expect(isoDurationDays('nonsense')).toBeNaN();
  });
});
