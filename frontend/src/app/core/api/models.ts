/** TypeScript mirrors of the backend's response records. Instants are ISO-8601 strings in UTC. */

export type Role = 'MANAGER' | 'STAFF';

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type ShiftStatus = 'OPEN' | 'FULL' | 'CANCELLED';

export interface Shift {
  id: number;
  name: string;
  startAt: string;
  endAt: string;
  registrationOpenAt: string;
  maxSlots: number;
  bookedSlots: number;
  availableSlots: number;
  status: ShiftStatus;
  templateId: number | null;
  version: number;
}

export type RegistrationStatus = 'CONFIRMED' | 'CANCELLED';

export interface Registration {
  id: number;
  shiftId: number;
  status: RegistrationStatus;
  startAt: string;
  endAt: string;
  hourlyRateSnapshot: number;
  registeredAt: string;
  cancelledAt: string | null;
}

/** One row of "my shifts": the registration plus what the screen needs to offer (or refuse) a cancel. */
export interface MyShift {
  id: number;
  shiftId: number;
  shiftName: string | null;
  status: RegistrationStatus;
  startAt: string;
  endAt: string;
  hours: number;
  hourlyRateSnapshot: number;
  registeredAt: string;
  cancelledAt: string | null;
  cancellable: boolean;
  cancelDeadline: string;
}

/** Current ISO week in the organisation zone, against the weekly hour limit (BR-04). */
export interface WeekSummary {
  weekStart: string;
  weekEnd: string;
  confirmedHours: number;
  limitHours: number;
  remainingHours: number;
}

export interface MyShifts {
  currentWeek: WeekSummary;
  registrations: Page<MyShift>;
}

/** Fields a manager may change on a shift; omitted fields stay as they are (PATCH). */
export interface ShiftChanges {
  name?: string;
  startAt?: string;
  endAt?: string;
  registrationOpenAt?: string;
  maxSlots?: number;
}

export interface NewShift {
  name: string;
  startAt: string;
  endAt: string;
  registrationOpenAt: string;
  maxSlots: number;
}

/** Recurring shift template; times are local to the organisation zone, `openBefore` is an ISO-8601 duration. */
export interface ShiftTemplate {
  id: number;
  name: string;
  weekdays: number[];
  startTime: string;
  endTime: string;
  overnight: boolean;
  maxSlots: number;
  openBefore: string;
  validFrom: string;
  validTo: string;
}

export type NewShiftTemplate = Omit<ShiftTemplate, 'id' | 'overnight'>;

export interface GenerationResult {
  templateId: number;
  createdShiftIds: number[];
  skippedExisting: number;
  skippedPast: number;
}

export interface UserSummary {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  hourlyRate: number;
}

export interface Me {
  id: number;
  fullName: string;
  email: string;
  role: Role;
  hourlyRate: number;
  reminderOptIn: boolean;
}
