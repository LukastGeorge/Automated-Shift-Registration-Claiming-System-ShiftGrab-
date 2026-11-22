import { TestBed } from '@angular/core/testing';

import { I18nService } from './i18n.service';
import { en, vi } from './messages';

describe('I18nService', () => {
  beforeEach(() => localStorage.clear());

  it('speaks Vietnamese by default and switches to English', () => {
    const i18n = TestBed.inject(I18nService);
    expect(i18n.t('nav.logout')).toBe('Đăng xuất');

    i18n.toggle();
    TestBed.tick();

    expect(i18n.t('nav.logout')).toBe('Log out');
    expect(document.documentElement.lang).toBe('en');
    expect(localStorage.getItem('shiftgrab.lang')).toBe('en');
    expect(i18n.locale()).toBe('en-GB');
  });

  it('fills in parameters and leaves unknown placeholders untouched', () => {
    const i18n = TestBed.inject(I18nService);
    expect(i18n.t('app.page', { page: 2, total: 5 })).toBe('Trang 2/5');
    expect(i18n.t('error.default')).toBe('Đã có lỗi xảy ra (mã {code}).');
  });

  it('returns undefined for keys built at runtime that do not exist', () => {
    const i18n = TestBed.inject(I18nService);
    expect(i18n.tryT('error.SHIFT_FULL')).toBe('Ca đã hết suất.');
    expect(i18n.tryT('error.NOT_A_CODE')).toBeUndefined();
  });

  it('has a translation for every key in both languages', () => {
    expect(Object.keys(en).sort()).toEqual(Object.keys(vi).sort());
    for (const text of [...Object.values(vi), ...Object.values(en)]) {
      expect(text.trim()).not.toBe('');
    }
  });
});
