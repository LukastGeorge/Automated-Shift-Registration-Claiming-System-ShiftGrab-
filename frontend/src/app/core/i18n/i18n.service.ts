import { DOCUMENT } from '@angular/common';
import { effect, inject, Injectable, Pipe, PipeTransform, signal } from '@angular/core';

import { en, MessageKey, vi } from './messages';

export type Lang = 'vi' | 'en';

export type Params = Record<string, string | number>;

const DICTIONARIES: Record<Lang, Record<MessageKey, string>> = { vi, en };
const STORAGE_KEY = 'shiftgrab.lang';

/** Runtime language switch without reloading: every text reads the `lang` signal. Vietnamese by default. */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly document = inject(DOCUMENT);
  readonly lang = signal<Lang>(readStoredLang());

  constructor() {
    effect(() => {
      const lang = this.lang();
      this.document.documentElement.lang = lang;
      try {
        localStorage.setItem(STORAGE_KEY, lang);
      } catch {
        // Private mode or blocked storage: the choice simply is not remembered.
      }
    });
  }

  t(key: MessageKey, params?: Params): string {
    return interpolate(DICTIONARIES[this.lang()][key] ?? key, params);
  }

  /** For keys built at runtime, such as `error.<CODE>`; returns undefined when the key is unknown. */
  tryT(key: string, params?: Params): string | undefined {
    const text = (DICTIONARIES[this.lang()] as Record<string, string>)[key];
    return text === undefined ? undefined : interpolate(text, params);
  }

  toggle(): void {
    this.lang.update((lang) => (lang === 'vi' ? 'en' : 'vi'));
  }

  /** BCP 47 locale for Intl formatting. */
  locale(): string {
    return this.lang() === 'vi' ? 'vi-VN' : 'en-GB';
  }
}

/** `{{ 'nav.logout' | t }}`. Impure so it re-renders when the language signal changes. */
@Pipe({ name: 't', pure: false })
export class TranslatePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  transform(key: MessageKey, params?: Params): string {
    return this.i18n.t(key, params);
  }
}

function interpolate(text: string, params?: Params): string {
  if (!params) {
    return text;
  }
  return text.replace(/\{(\w+)\}/g, (match, name: string) => (name in params ? String(params[name]) : match));
}

function readStoredLang(): Lang {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === 'en' || stored === 'vi' ? stored : 'vi';
  } catch {
    return 'vi';
  }
}
