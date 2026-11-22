import { MyShift, MyShifts, Page, Shift } from '../app/core/api/models';

export function page<T>(content: T[], pageNo = 0, totalPages = 1): Page<T> {
  return { content, page: pageNo, size: 50, totalElements: content.length, totalPages };
}

export function shift(overrides: Partial<Shift> = {}): Shift {
  return {
    id: 1,
    name: 'Morning bar',
    startAt: '2030-01-07T01:00:00Z', // 08:00 in Ho Chi Minh City
    endAt: '2030-01-07T05:00:00Z',
    registrationOpenAt: '2020-01-01T00:00:00Z',
    maxSlots: 5,
    bookedSlots: 2,
    availableSlots: 3,
    status: 'OPEN',
    templateId: null,
    version: 0,
    ...overrides,
  };
}

export function myShift(overrides: Partial<MyShift> = {}): MyShift {
  return {
    id: 10,
    shiftId: 1,
    shiftName: 'Morning bar',
    status: 'CONFIRMED',
    startAt: '2030-01-07T01:00:00Z',
    endAt: '2030-01-07T05:00:00Z',
    hours: 4,
    hourlyRateSnapshot: 25000,
    registeredAt: '2029-12-30T01:00:00Z',
    cancelledAt: null,
    cancellable: true,
    cancelDeadline: '2030-01-06T01:00:00Z',
    ...overrides,
  };
}

export function myShifts(items: MyShift[], confirmedHours = 12): MyShifts {
  return {
    currentWeek: {
      weekStart: '2030-01-07',
      weekEnd: '2030-01-13',
      confirmedHours,
      limitHours: 40,
      remainingHours: 40 - confirmedHours,
    },
    registrations: page(items),
  };
}
