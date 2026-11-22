import { InjectionToken } from '@angular/core';

/**
 * Settings that differ per environment. They are read from `/config.json` at startup instead of being
 * compiled in, so one build (one Docker image) runs in every environment: production only replaces the file.
 */
export interface AppConfig {
  /** Base URL of the backend; empty string means "same origin" (behind the production reverse proxy). */
  apiUrl: string;
  keycloak: { url: string; realm: string; clientId: string };
  /** Organisation time zone: every date is shown and entered in this zone, whatever the browser's zone. */
  timeZone: string;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');

export async function loadConfig(fetchFn: typeof fetch = fetch): Promise<AppConfig> {
  const response = await fetchFn('config.json', { cache: 'no-store' });
  if (!response.ok) {
    throw new Error(`Cannot load config.json (HTTP ${response.status})`);
  }
  return (await response.json()) as AppConfig;
}
