import { TestBed } from '@angular/core/testing';

import { FakeStomp, testProviders, fakeKeycloak } from '../../testing/test-support';
import { NewestWins, RealtimeService, SlotUpdate, webSocketUrl } from './realtime.service';

const update = (shiftId: number, bookedSlots: number, observedAt: string): SlotUpdate => ({
  shiftId,
  bookedSlots,
  maxSlots: 5,
  availableSlots: 5 - bookedSlots,
  status: 'OPEN',
  observedAt,
});

describe('RealtimeService', () => {
  let stomp: FakeStomp;

  beforeEach(() => {
    stomp = new FakeStomp();
    TestBed.configureTestingModule({ providers: testProviders(fakeKeycloak(), stomp) });
  });

  it('connects only while someone listens, with a fresh bearer token, read-only topic', async () => {
    const live = TestBed.inject(RealtimeService);
    expect(stomp.activated).toBe(0);

    const received: SlotUpdate[] = [];
    const subscription = live.slotUpdates().subscribe((u) => received.push(u));
    await new Promise((resolve) => setTimeout(resolve));

    expect(stomp.activated).toBe(1);
    expect(stomp.config?.brokerURL).toBe('ws://api.test/ws');
    expect(stomp.connectHeaders).toEqual({ Authorization: 'Bearer token-123' });
    expect(stomp.destination).toBe('/topic/shifts');
    expect(live.connected()).toBe(true);

    stomp.emit(update(1, 2, '2030-01-01T00:00:00Z'));
    expect(received).toEqual([update(1, 2, '2030-01-01T00:00:00Z')]);

    stomp.drop();
    expect(live.connected()).toBe(false);

    subscription.unsubscribe();
    expect(stomp.deactivated).toBe(1);
  });

  it('shares one connection between screens', () => {
    const live = TestBed.inject(RealtimeService);
    const a = live.slotUpdates().subscribe();
    const b = live.slotUpdates().subscribe();
    a.unsubscribe();
    expect(stomp.deactivated).toBe(0);
    b.unsubscribe();
    expect(stomp.activated).toBe(1);
    expect(stomp.deactivated).toBe(1);
  });
});

describe('webSocketUrl', () => {
  it('derives the WebSocket address from the API address', () => {
    expect(webSocketUrl('http://localhost:8080', { protocol: 'http:', host: 'x' })).toBe('ws://localhost:8080/ws');
    expect(webSocketUrl('https://api.example.com', { protocol: 'https:', host: 'x' })).toBe('wss://api.example.com/ws');
    expect(webSocketUrl('', { protocol: 'https:', host: 'shiftgrab.example' })).toBe('wss://shiftgrab.example/ws');
    expect(webSocketUrl('', { protocol: 'http:', host: 'localhost:4200' })).toBe('ws://localhost:4200/ws');
  });
});

describe('NewestWins', () => {
  it('ignores an update older than the one already applied for the same shift', () => {
    const newest = new NewestWins();
    expect(newest.accept(update(1, 3, '2030-01-01T00:00:02Z'))).toBe(true);
    expect(newest.accept(update(1, 2, '2030-01-01T00:00:01Z'))).toBe(false);
    expect(newest.accept(update(1, 2, '2030-01-01T00:00:02Z'))).toBe(false);
    expect(newest.accept(update(2, 1, '2030-01-01T00:00:01Z'))).toBe(true);
    expect(newest.accept(update(1, 4, '2030-01-01T00:00:03Z'))).toBe(true);
  });
});
