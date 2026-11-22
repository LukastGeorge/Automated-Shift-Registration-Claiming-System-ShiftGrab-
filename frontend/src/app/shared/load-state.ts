import { signal } from '@angular/core';
import { Observable } from 'rxjs';

import { Problem, toProblem } from '../core/api/problem';

/**
 * Loading / data / error for one request, as signals the template can read directly.
 * `run()` can be called again (retry, filters changed); a newer call wins over an older one still in flight.
 */
export class LoadState<T> {
  readonly loading = signal(false);
  readonly data = signal<T | undefined>(undefined);
  readonly error = signal<Problem | undefined>(undefined);
  private generation = 0;

  run(source: Observable<T>): void {
    const current = ++this.generation;
    this.loading.set(true);
    this.error.set(undefined);
    source.subscribe({
      next: (value) => {
        if (current === this.generation) {
          this.data.set(value);
        }
      },
      error: (err: unknown) => {
        if (current === this.generation) {
          this.error.set(toProblem(err));
          this.loading.set(false);
        }
      },
      complete: () => {
        if (current === this.generation) {
          this.loading.set(false);
        }
      },
    });
  }
}
