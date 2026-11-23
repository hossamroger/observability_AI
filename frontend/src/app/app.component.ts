import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { DashboardService } from './dashboard.service';
import { DashboardOverview, MetricObservation, Platform } from './metric.model';

type ServiceMetric = 'requests' | 'failed' | 'successful' | 'p50' | 'p95' | 'p99' | 'failure';
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
  readonly windows = [5, 15, 30, 60];
  windowMinutes = 5;
  windowMetrics: MetricObservation[] = [];
  windowError = '';
  windowLoading = false;
  private windowRequest = 0;
  private windowSubscription?: import('rxjs').Subscription;
  get windowStart(): string | undefined { return this.windowMetrics[0]?.windowStartUtc; }
  get windowEnd(): string | undefined { return this.windowMetrics[0]?.windowEndUtc; }
  selectWindow(minutes: number): void {
    this.windowMinutes = minutes; this.page = 0; this.windowMetrics = [];
    this.refreshWindow();
  }
  private refreshWindow(): void {
    if (this.selectedPlatform !== 'BACKEND') return;
    const request = ++this.windowRequest;
    this.windowSubscription?.unsubscribe();
    this.windowLoading = true;
    this.windowSubscription = this.dashboard.loadServiceWindow(this.windowMinutes).subscribe({
      next: metrics => { if (request === this.windowRequest) { this.windowMetrics = metrics; this.windowError = ''; this.windowLoading = false; } },
      error: () => { if (request === this.windowRequest) { this.windowMetrics = []; this.windowError = 'Dynatrace reporting window unavailable'; this.windowLoading = false; } }
    });
  }
  page = 0;
  readonly pageSize = 25;
  sortBy: ServiceMetric | 'name' = 'failed';
  descending = true;
  readonly columns: {key: ServiceMetric; label: string}[] = [
    {key:'requests',label:'Total requests'}, {key:'failed',label:'Failed'}, {key:'successful',label:'Successful'}, {key:'p50',label:'P50 (ms)'},
    {key:'p95',label:'P95 (ms)'}, {key:'p99',label:'P99 (ms)'}, {key:'failure',label:'Failure (%)'}
  ];

  get serviceRows(): ServiceRow[] {
    const rows = new Map<string, ServiceRow>();
    for (const metric of this.windowMetrics) {
      if (metric.sourceSystem !== 'DYNATRACE' || metric.platform !== 'BACKEND') continue;
      const key = this.metricKey(metric.metricName);
      if (!key) continue;
      const match = metric.serviceName.match(/\[(SERVICE-[^\]]+)\]$/);
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
    if (key.includes('service.errors.total.count')) return 'failed';
    if (key.includes('service.errors.total.successcount')) return 'successful';
    if (key.includes('service.requestcount')) return 'requests';
    if (key.includes('service.errors.total.rate')) return 'failure';
    if (key.includes('service.response.time')) {
      if (key.includes('percentile(50)')) return 'p50';
      if (key.includes('percentile(95)')) return 'p95';
      if (key.includes('percentile(99)')) return 'p99';
    }
    return undefined;
  }

  warningRate = 1;
  criticalRate = 5;
  minimumRequests = 10;
  slowP95 = 2000;
  healthFilter = 'ALL';
  selectedServiceId = '';
  get selectedService(): ServiceRow | undefined { return this.serviceRows.find(r => r.id === this.selectedServiceId); }
  health(row: ServiceRow): string {
    const total = row.metrics.requests?.metricValue;
    const failed = row.metrics.failed?.metricValue;
    const successful = row.metrics.successful?.metricValue;
    const p95 = row.metrics.p95?.metricValue;
    if (total == null || total <= 0 || failed == null || successful == null || failed + successful !== total || p95 == null) return 'UNKNOWN';
    if (total < this.minimumRequests) return 'LOW TRAFFIC';
    const rate = 100 * failed / total;
    if (rate >= this.criticalRate) return 'CRITICAL';
    if (rate >= this.warningRate || p95 >= this.slowP95) return 'WARNING';
    return 'HEALTHY';
  }
  setThreshold(key: 'warningRate' | 'criticalRate' | 'minimumRequests' | 'slowP95', value: string): void {
    const n = Number(value);
    if (value.trim() && Number.isFinite(n) && n > 0 && (key !== 'warningRate' || n <= this.criticalRate) && (key !== 'criticalRate' || (n >= this.warningRate && n <= 100))) this[key] = n;
  }
  get criticalServices(): number { return this.serviceRows.filter(r => this.health(r) === 'CRITICAL').length; }
  get filteredRows(): ServiceRow[] {
    const query = this.search.trim().toLowerCase();
    const sort = this.sortBy;
    return this.serviceRows.filter(row => (row.name + ' ' + row.id).toLowerCase().includes(query) && (this.healthFilter === 'ALL' || this.health(row) === this.healthFilter))
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
    this.windowSubscription?.unsubscribe();
    if (this.fallbackRefresh) clearInterval(this.fallbackRefresh);
  }

  selectPlatform(platform: Platform): void { this.selectedPlatform = platform; this.refreshWindow(); }

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
    this.refreshWindow();
    this.dashboard.loadOverview().subscribe({
      next: data => { this.overview = data; this.errorMessage = ''; this.loading = false; },
      error: () => { this.errorMessage = 'Backend unavailable. Verify the local server and proxy.'; this.loading = false; }
    });
  }
}
