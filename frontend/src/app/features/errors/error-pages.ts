import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { TranslatePipe } from '../../core/i18n/i18n.service';

@Component({
  selector: 'app-forbidden-page',
  imports: [MatButtonModule, TranslatePipe],
  template: `
    <h1>{{ 'forbidden.title' | t }}</h1>
    <p>{{ 'forbidden.body' | t }}</p>
    <button mat-flat-button type="button" (click)="auth.logout()">{{ 'nav.logout' | t }}</button>
  `,
})
export class ForbiddenPage {
  protected readonly auth = inject(AuthService);
}

@Component({
  selector: 'app-not-found-page',
  imports: [MatButtonModule, RouterLink, TranslatePipe],
  template: `
    <h1>{{ 'notFound.title' | t }}</h1>
    <p>{{ 'notFound.body' | t }}</p>
    <a mat-flat-button routerLink="/">{{ 'notFound.home' | t }}</a>
  `,
})
export class NotFoundPage {}
