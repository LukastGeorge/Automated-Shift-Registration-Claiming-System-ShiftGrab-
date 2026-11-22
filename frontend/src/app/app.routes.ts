import { inject } from '@angular/core';
import { Routes } from '@angular/router';

import { AuthService } from './core/auth/auth.service';
import { homeFor } from './core/auth/role.guard';
import { ForbiddenPage, NotFoundPage } from './features/errors/error-pages';
import { ShellComponent } from './layout/shell.component';

export const routes: Routes = [
  {
    path: '',
    component: ShellComponent,
    children: [
      { path: '', pathMatch: 'full', redirectTo: () => homeFor(inject(AuthService)) },
      { path: 'profile', loadComponent: () => import('./features/profile/profile.page').then((m) => m.ProfilePage) },
      { path: 'forbidden', component: ForbiddenPage },
      { path: '**', component: NotFoundPage },
    ],
  },
];
