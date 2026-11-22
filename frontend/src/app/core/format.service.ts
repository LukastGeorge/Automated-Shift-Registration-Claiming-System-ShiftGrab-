import { inject, Injectable } from '@angular/core';

import { APP_CONFIG } from './config';
import { I18nService } from './i18n/i18n.service';

/**
 * Dates, times and money in the organisation time zone and the current language. The backend sends and
 * receives UTC instants; converting at the edge keeps every screen consistent, whatever the browser's zone.
 */
@Injectable({ providedIn: 'root' })
export class FormatService {
  private readonly i18n = inject(I18nService);
  readonly timeZone = inject(APP_CONFIG).timeZone;

  dateTime(iso: string): string {
    return this.format(iso, { dateStyle: 'medium', timeStyle: 'short' });
  }

  date(iso: string): string {
    return this.format(iso, { weekday: 'long', day: 'numeric', month: 'long' });
  }

  time(iso: string): string {
    return this.format(iso, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  }

  /** Calendar day key in the organisation zone (YYYY-MM-DD), for grouping shifts by day. */
  dayKey(iso: string): string {
    return toZonedInput(iso, this.timeZone).slice(0, 10);
  }

  money(amount: number | string): string {
    return new Intl.NumberFormat(this.i18n.locale(), { style: 'currency', currency: 'VND' }).format(
      Number(amount),
    );
  }

  hours(value: number | string): string {
    return new Intl.NumberFormat(this.i18n.locale(), { maximumFractionDigits: 2 }).format(Number(value));
  }

  /** "2 d 03:15:09" style countdown; the caller decides what to show at zero. */
  countdown(ms: number): string {
    const total = Math.max(0, Math.floor(ms / 1000));
    const days = Math.floor(total / 86_400);
    const clock = [Math.floor((total % 86_400) / 3600), Math.floor((total % 3600) / 60), total % 60]
      .map((n) => String(n).padStart(2, '0'))
      .join(':');
    return days > 0 ? `${days}d ${clock}` : clock;
  }

  private format(iso: string, options: Intl.DateTimeFormatOptions): string {
    return new Intl.DateTimeFormat(this.i18n.locale(), { ...options, timeZone: this.timeZone }).format(
      new Date(iso),
    );
  }
}

/** Offset of `timeZone` from UTC at `instant`, in minutes (e.g. +420 for Asia/Ho_Chi_Minh). */
export function zoneOffsetMinutes(instant: Date, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(instant);
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second'));
  return Math.round((asUtc - Math.floor(instant.getTime() / 1000) * 1000) / 60_000);
}

/** `2026-11-23T08:00` typed in the organisation zone → UTC ISO instant for the API. */
export function fromZonedInput(local: string, timeZone: string): string {
  const [date, time = '00:00'] = local.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [hh, mm] = time.split(':').map(Number);
  const wallClockAsUtc = Date.UTC(y, m - 1, d, hh, mm);
  // Apply the offset twice so the result is right even next to a daylight-saving change.
  let offset = zoneOffsetMinutes(new Date(wallClockAsUtc), timeZone);
  offset = zoneOffsetMinutes(new Date(wallClockAsUtc - offset * 60_000), timeZone);
  return new Date(wallClockAsUtc - offset * 60_000).toISOString();
}

/** `YYYY-MM-DD` plus `days` calendar days (no time zone involved). */
export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** ISO-8601 duration (`P7D`, `PT168H`, `P1DT12H`) → days, as sent by the backend for `openBefore`. */
export function isoDurationDays(duration: string): number {
  const match = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(duration);
  if (!match) {
    return NaN;
  }
  const [, d = '0', h = '0', m = '0', s = '0'] = match;
  return Number(d) + Number(h) / 24 + Number(m) / 1440 + Number(s) / 86_400;
}

/** UTC ISO instant → `YYYY-MM-DDTHH:mm` in the organisation zone, for `<input type="datetime-local">`. */
export function toZonedInput(iso: string, timeZone: string): string {
  const instant = new Date(iso);
  const shifted = new Date(instant.getTime() + zoneOffsetMinutes(instant, timeZone) * 60_000);
  return shifted.toISOString().slice(0, 16);
}
