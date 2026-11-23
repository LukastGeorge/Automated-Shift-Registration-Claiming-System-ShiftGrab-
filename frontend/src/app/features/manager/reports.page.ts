import { DOCUMENT } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';

import { HoursReport } from '../../core/api/models';
import { fileNameFrom, ReportApi } from '../../core/api/report-api.service';
import { FormatService, toZonedInput } from '../../core/format.service';
import { I18nService, TranslatePipe } from '../../core/i18n/i18n.service';
import { NotifyService } from '../../core/notify.service';
import { LoadErrorComponent } from '../../shared/load-error.component';
import { LoadState } from '../../shared/load-state';

/** SG-64: hours and pay per staff member for a period (SG-50), downloadable as CSV or Excel (SG-51). */
@Component({
  selector: 'app-reports-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    TranslatePipe,
    LoadErrorComponent,
  ],
  templateUrl: './reports.page.html',
  styleUrl: './reports.page.scss',
})
export class ReportsPage implements OnInit {
  private readonly api = inject(ReportApi);
  private readonly notify = inject(NotifyService);
  private readonly i18n = inject(I18nService);
  private readonly document = inject(DOCUMENT);
  protected readonly fmt = inject(FormatService);

  private readonly today = toZonedInput(new Date().toISOString(), this.fmt.timeZone).slice(0, 10);
  /** Default period: this month so far. */
  protected readonly from = signal(`${this.today.slice(0, 8)}01`);
  protected readonly to = signal(this.today);
  protected readonly state = new LoadState<HoursReport>();
  protected readonly downloading = signal<'csv' | 'xlsx' | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.run(this.api.hours(this.from(), this.to()));
  }

  protected download(format: 'csv' | 'xlsx'): void {
    this.downloading.set(format);
    this.api.download(this.from(), this.to(), format, this.i18n.lang()).subscribe({
      next: (response) => {
        this.downloading.set(null);
        const name = fileNameFrom(response.headers.get('Content-Disposition'), `shiftgrab-hours.${format}`);
        this.save(response.body as Blob, name);
        this.notify.success('report.downloaded', { file: name });
      },
      error: (err: unknown) => {
        this.downloading.set(null);
        this.notify.error(err);
      },
    });
  }

  /** A temporary link to the downloaded bytes, clicked once: the browser saves the file under its real name. */
  private save(blob: Blob, name: string): void {
    const url = URL.createObjectURL(blob);
    const link = this.document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    URL.revokeObjectURL(url);
  }
}
