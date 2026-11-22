import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import { Client, StompConfig } from '@stomp/stompjs';
import { Observable, Subject } from 'rxjs';

import { ShiftStatus } from './api/models';
import { AuthService } from './auth/auth.service';
import { APP_CONFIG } from './config';

/** Current slot counters of one shift, pushed by the backend after every committed change (SG-40). */
export interface SlotUpdate {
  shiftId: number;
  bookedSlots: number;
  maxSlots: number;
  availableSlots: number;
  status: ShiftStatus;
  /** Database clock at read time: updates can arrive out of order, the newest per shift wins. */
  observedAt: string;
}

/** The part of the STOMP client used here; tests provide a fake. */
export type StompClient = Pick<Client, 'activate' | 'deactivate' | 'subscribe'>;

export const STOMP_CLIENT_FACTORY = new InjectionToken<(config: StompConfig) => StompClient>('STOMP_CLIENT_FACTORY', {
  providedIn: 'root',
  factory: () => (config: StompConfig) => new Client(config),
});

export const TOPIC = '/topic/shifts';

/**
 * One shared STOMP connection, opened while at least one screen listens and closed when the last one leaves.
 * A fresh access token is put on every (re)connect, because tokens live only 5 minutes.
 */
@Injectable({ providedIn: 'root' })
export class RealtimeService {
  private readonly auth = inject(AuthService);
  private readonly apiUrl = inject(APP_CONFIG).apiUrl;
  private readonly createClient = inject(STOMP_CLIENT_FACTORY);
  private readonly updates = new Subject<SlotUpdate>();
  private client?: StompClient;
  private listeners = 0;

  readonly connected = signal(false);

  slotUpdates(): Observable<SlotUpdate> {
    return new Observable<SlotUpdate>((subscriber) => {
      if (this.listeners++ === 0) {
        this.start();
      }
      const subscription = this.updates.subscribe(subscriber);
      return () => {
        subscription.unsubscribe();
        if (--this.listeners === 0) {
          this.stop();
        }
      };
    });
  }

  private start(): void {
    const client: StompClient = this.createClient({
      brokerURL: webSocketUrl(this.apiUrl, window.location),
      reconnectDelay: 5000,
      beforeConnect: async (c: Client) => {
        c.connectHeaders = { Authorization: `Bearer ${await this.auth.token()}` };
      },
      onConnect: () => {
        this.connected.set(true);
        client.subscribe(TOPIC, (message) => this.updates.next(JSON.parse(message.body) as SlotUpdate));
      },
      onWebSocketClose: () => this.connected.set(false),
      onStompError: () => this.connected.set(false),
    });
    this.client = client;
    client.activate();
  }

  private stop(): void {
    void this.client?.deactivate();
    this.client = undefined;
    this.connected.set(false);
  }
}

/** `http://api:8080` → `ws://api:8080/ws`; empty (same origin) → `wss://<current host>/ws` on HTTPS. */
export function webSocketUrl(apiUrl: string, location: Pick<Location, 'protocol' | 'host'>): string {
  if (!apiUrl) {
    return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
  }
  return `${apiUrl.replace(/^http/, 'ws')}/ws`;
}

/**
 * Keeps only updates newer than the last one applied for the same shift. Returns false for a stale update,
 * which the caller must ignore.
 */
export class NewestWins {
  private readonly seen = new Map<number, string>();

  accept(update: SlotUpdate): boolean {
    const previous = this.seen.get(update.shiftId);
    if (previous !== undefined && Date.parse(previous) >= Date.parse(update.observedAt)) {
      return false;
    }
    this.seen.set(update.shiftId, update.observedAt);
    return true;
  }
}
