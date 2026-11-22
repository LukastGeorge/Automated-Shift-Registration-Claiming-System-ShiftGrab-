import { bootstrapApplication } from '@angular/platform-browser';

import { App } from './app/app';
import { appConfig } from './app/app.config';
import { loadConfig } from './app/core/config';

loadConfig()
  .then((config) => bootstrapApplication(App, appConfig(config)))
  .catch((err: unknown) => {
    console.error(err);
    document.body.textContent = `ShiftGrab could not start: ${err instanceof Error ? err.message : String(err)}`;
  });
