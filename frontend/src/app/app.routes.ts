import { inject } from '@angular/core';
import { Routes } from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { homeFor, roleGuard } from './core/auth/role.guard';
import { ForbiddenPage, NotFoundPage } from './features/errors/error-pages';
import { ShellComponent } from './layout/shell.component';

export const routes: Routes = [
  {
    path: '',
    component: ShellComponent,
    children: [
      { path: '', pathMatch: 'full', redirectTo: () => homeFor(inject(AuthService)) },
      {
        path: 'shifts',
        canMatch: [roleGuard('STAFF')],
        loadComponent: () => import('./features/staff/open-shifts.page').then((m) => m.OpenShiftsPage),
      },
      {
        path: 'my-shifts',
        canMatch: [roleGuard('STAFF')],
        loadComponent: () => import('./features/staff/my-shifts.page').then((m) => m.MyShiftsPage),
      },
      {
        path: 'manage',
        canMatch: [roleGuard('MANAGER')],
        children: [
          {
            path: 'shifts',
            loadComponent: () => import('./features/manager/shifts-admin.page').then((m) => m.ShiftsAdminPage),
          },
          {
            path: 'templates',
            loadComponent: () => import('./features/manager/templates.page').then((m) => m.TemplatesPage),
          },
          { path: 'staff', loadComponent: () => import('./features/manager/staff.page').then((m) => m.StaffPage) },
          { path: '', pathMatch: 'full', redirectTo: 'shifts' },
        ],
      },
      { path: 'profile', loadComponent: () => import('./features/profile/profile.page').then((m) => m.ProfilePage) },
      { path: 'forbidden', component: ForbiddenPage },
      { path: '**', component: NotFoundPage },
    ],
  },
];
