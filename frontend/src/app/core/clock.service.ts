import { Injectable, OnDestroy, signal } from '@angular/core';

/**
 * Current time as a signal that ticks every second, for countdowns ("opens in 00:04:12"). A button bound to it
 * enables itself the moment registration opens, without polling the server. The server still decides (BR-05).
 */
@Injectable({ providedIn: 'root' })
export class ClockService implements OnDestroy {
  private readonly current = signal(Date.now());
  readonly now = this.current.asReadonly();
  private readonly timer = setInterval(() => this.current.set(Date.now()), 1000);

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }
}
