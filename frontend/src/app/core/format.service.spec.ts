import { TestBed } from '@angular/core/testing';

import { testProviders } from '../../testing/test-support';
import { FormatService, fromZonedInput, toZonedInput, zoneOffsetMinutes } from './format.service';
import { I18nService } from './i18n/i18n.service';

describe('time zone conversion', () => {
  it('treats typed times as organisation time, whatever the browser zone', () => {
    expect(zoneOffsetMinutes(new Date('2026-11-23T01:00:00Z'), 'Asia/Ho_Chi_Minh')).toBe(420);
    expect(fromZonedInput('2026-11-23T08:00', 'Asia/Ho_Chi_Minh')).toBe('2026-11-23T01:00:00.000Z');
    expect(toZonedInput('2026-11-23T01:00:00Z', 'Asia/Ho_Chi_Minh')).toBe('2026-11-23T08:00');
  });

  it('round-trips across midnight and daylight saving time', () => {
    expect(toZonedInput(fromZonedInput('2026-11-23T23:30', 'Asia/Ho_Chi_Minh'), 'Asia/Ho_Chi_Minh')).toBe(
      '2026-11-23T23:30',
    );
    // Europe/Berlin switches to summer time on 2026-03-29.
    expect(fromZonedInput('2026-03-30T08:00', 'Europe/Berlin')).toBe('2026-03-30T06:00:00.000Z');
    expect(fromZonedInput('2026-03-27T08:00', 'Europe/Berlin')).toBe('2026-03-27T07:00:00.000Z');
  });
});

describe('FormatService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: testProviders() });
  });

  it('formats in the organisation zone', () => {
    const fmt = TestBed.inject(FormatService);
    expect(fmt.time('2026-11-23T01:00:00Z')).toBe('08:00');
    expect(fmt.dayKey('2026-11-23T18:30:00Z')).toBe('2026-11-24');
  });

  it('formats money in VND and follows the language', () => {
    const fmt = TestBed.inject(FormatService);
    expect(fmt.money(25000)).toContain('25.000');
    TestBed.inject(I18nService).lang.set('en');
    expect(fmt.money(25000)).toContain('25,000');
  });

  it('shows countdowns with days only when needed', () => {
    const fmt = TestBed.inject(FormatService);
    expect(fmt.countdown(65_000)).toBe('00:01:05');
    expect(fmt.countdown(2 * 86_400_000 + 3_600_000)).toBe('2d 01:00:00');
    expect(fmt.countdown(-5)).toBe('00:00:00');
  });
});
