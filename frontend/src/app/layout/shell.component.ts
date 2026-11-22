import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from '../core/auth/auth.service';
import { I18nService, TranslatePipe } from '../core/i18n/i18n.service';
import { MessageKey } from '../core/i18n/messages';
import { Role } from '../core/api/models';

interface NavItem {
  path: string;
  label: MessageKey;
  icon: string;
  role?: Role;
}

/** Navigation entries; each feature adds its own and the shell shows only those the user's role allows. */
export const NAV_ITEMS: NavItem[] = [
  { path: '/shifts', label: 'nav.openShifts', icon: 'event_available', role: 'STAFF' },
  { path: '/my-shifts', label: 'nav.myShifts', icon: 'calendar_month', role: 'STAFF' },
  { path: '/manage/shifts', label: 'nav.manageShifts', icon: 'edit_calendar', role: 'MANAGER' },
  { path: '/manage/templates', label: 'nav.templates', icon: 'event_repeat', role: 'MANAGER' },
  { path: '/manage/staff', label: 'nav.staff', icon: 'group', role: 'MANAGER' },
  { path: '/profile', label: 'nav.profile', icon: 'person' },
];

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule, MatButtonModule, MatIconModule, TranslatePipe],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly i18n = inject(I18nService);

  protected readonly nav = computed(() =>
    NAV_ITEMS.filter((item) => !item.role || this.auth.roles().includes(item.role)),
  );

  protected roleKey(role: Role): MessageKey {
    return `role.${role}`;
  }
}
