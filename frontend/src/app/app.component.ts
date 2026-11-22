import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { DashboardService } from './dashboard.service';
import { DashboardOverview, MetricObservation, Platform } from './metric.model';

@Component({
  selector: 'ds-root',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  readonly platforms: Platform[] = ['WEB', 'ANDROID', 'IOS', 'BACKEND'];
  selectedPlatform: Platform = 'WEB';
  overview: DashboardOverview | null = null;
  loading = true;
  errorMessage = '';
  private stream?: EventSource;
  private fallbackRefresh?: ReturnType<typeof setInterval>;

  constructor(private readonly dashboard: DashboardService) {}

  ngOnInit(): void {
    this.refresh();
    this.stream = this.dashboard.observeChanges(() => this.refresh());
    // Re-fetch occasionally even if a proxy disconnects or drops server-sent events.
    this.fallbackRefresh = setInterval(() => this.refresh(), 30000);
  }

  ngOnDestroy(): void {
    this.stream?.close();
    if (this.fallbackRefresh) clearInterval(this.fallbackRefresh);
  }

  selectPlatform(platform: Platform): void { this.selectedPlatform = platform; }

  get selectedMetrics(): MetricObservation[] {
    const data = this.overview?.metrics ?? [];
    const seen = new Set<string>();
    return data.filter(metric => {
      if (metric.platform !== this.selectedPlatform) return false;
      const key = `${metric.sourceSystem}|${metric.serviceName}|${metric.metricName}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  get lastUpdated(): string {
    const values = this.selectedMetrics.map(metric => Date.parse(metric.collectedAtUtc));
    return values.length ? new Date(Math.max(...values)).toLocaleString() : 'No data received';
  }

  refresh(): void {
    this.dashboard.loadOverview().subscribe({
      next: data => { this.overview = data; this.errorMessage = ''; this.loading = false; },
      error: () => { this.errorMessage = 'Backend unavailable. Verify the local server and proxy.'; this.loading = false; }
    });
  }
}
