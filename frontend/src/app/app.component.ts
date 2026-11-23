import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { DashboardService } from './dashboard.service';
import { DashboardOverview, MetricObservation, Platform } from './metric.model';

type ServiceMetric = 'requests' | 'p50' | 'p95' | 'p99' | 'failure';
interface ServiceRow {
  id: string;
  name: string;
  metrics: Partial<Record<ServiceMetric, MetricObservation>>;
}

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
  search = '';
  page = 0;
  readonly pageSize = 25;
  sortBy: ServiceMetric | 'name' = 'name';
  descending = false;
  readonly columns: {key: ServiceMetric; label: string}[] = [
    {key:'requests',label:'Requests / min'}, {key:'p50',label:'P50 (ms)'},
    {key:'p95',label:'P95 (ms)'}, {key:'p99',label:'P99 (ms)'}, {key:'failure',label:'Failure (%)'}
  ];

  get serviceRows(): ServiceRow[] {
    const rows = new Map<string, ServiceRow>();
    for (const metric of this.overview?.metrics ?? []) {
      if (metric.sourceSystem !== 'DYNATRACE' || metric.platform !== 'BACKEND') continue;
      const key = this.metricKey(metric.metricName);
      if (!key) continue;
      const match = metric.serviceName.match(/\\[(SERVICE-[^\\]]+)\\]$/);
      const id = match?.[1] ?? metric.serviceName;
      const name = match ? metric.serviceName.slice(0, match.index).trim() : metric.serviceName;
      const row = rows.get(id) ?? {id, name, metrics: {}};
      const previous = row.metrics[key];
      if (!previous || Date.parse(metric.windowEndUtc) > Date.parse(previous.windowEndUtc)) row.metrics[key] = metric;
      rows.set(id, row);
    }
    return Array.from(rows.values());
  }

  private metricKey(name: string): ServiceMetric | undefined {
    const key = name.toLowerCase();
    if (key.includes('service.requestcount')) return 'requests';
    if (key.includes('service.errors.total.rate')) return 'failure';
    if (key.includes('service.response.time')) {
      if (key.includes('percentile(50)')) return 'p50';
      if (key.includes('percentile(95)')) return 'p95';
      if (key.includes('percentile(99)')) return 'p99';
    }
    return undefined;
  }

  get filteredRows(): ServiceRow[] {
    const query = this.search.trim().toLowerCase();
    const sort = this.sortBy;
    return this.serviceRows.filter(row => (row.name + ' ' + row.id).toLowerCase().includes(query))
      .sort((a,b) => {
        if (sort === 'name') return (this.descending ? -1 : 1) * a.name.localeCompare(b.name) || a.id.localeCompare(b.id);
        const av = a.metrics[sort]?.metricValue, bv = b.metrics[sort]?.metricValue;
        if (av == null || bv == null) return av == null ? (bv == null ? a.id.localeCompare(b.id) : 1) : -1;
        return (this.descending ? -1 : 1) * (av-bv) || a.id.localeCompare(b.id);
      });
  }
  get pageCount(): number { return Math.max(1, Math.ceil(this.filteredRows.length / this.pageSize)); }
  get currentPage(): number { return Math.min(this.page, this.pageCount - 1); }
  get visibleRows(): ServiceRow[] { return this.filteredRows.slice(this.currentPage*this.pageSize,(this.currentPage+1)*this.pageSize); }
  get servicesWithErrors(): number { return this.serviceRows.filter(r => (r.metrics.failure?.metricValue ?? 0) > 0).length; }
  get backendOtherMetrics(): MetricObservation[] { return this.selectedMetrics.filter(m => m.sourceSystem !== 'DYNATRACE'); }
  setSearch(value: string): void { this.search = value; this.page = 0; }
  setSort(key: ServiceMetric | 'name'): void {
    this.descending = this.sortBy === key ? !this.descending : key !== 'name';
    this.sortBy = key; this.page = 0;
  }
  trackService(index: number, row: ServiceRow): string { return row.id; }
  isStale(metric: MetricObservation): boolean { return Date.now() - Date.parse(metric.windowEndUtc) > 300000; }

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
