import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { ApplicationAnalyticsComponent } from './application-analytics/application-analytics.component';
import { GlobalOverviewComponent, GlobalServiceRow } from './global-overview/global-overview.component';
import { latestPlatformMetrics } from './latest-platform-metrics';
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
  imports: [CommonModule, ApplicationAnalyticsComponent, GlobalOverviewComponent],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  readonly platforms: Platform[] = ['WEB', 'ANDROID', 'IOS', 'BACKEND'];
  selectedPlatform: Platform = 'WEB';
  globalView = true;
  darkMode = this.restoreTheme();
  private lastGlobalWindowAttempt = 0;
  private restoreTheme():boolean {
    try { return localStorage.getItem('observability.theme') === 'dark'; } catch { return false; }
  }
  toggleTheme():void {
    this.darkMode = !this.darkMode;
    try { localStorage.setItem('observability.theme',this.darkMode?'dark':'light'); } catch { /* Session only */ }
  }
  openGlobal():void {
    if (this.globalView) return;
    this.globalView=true;
    this.resetServiceWindow();
    this.lastGlobalWindowAttempt=0;
    this.refresh();
  }
  private resetServiceWindow():void {
    this.windowRequest++; this.windowSubscription?.unsubscribe(); this.windowLoading=false;
    this.windowMetrics=[]; this.windowError='';
  }
  openGlobalService(id:string):void {
    this.selectPlatform('BACKEND');
    this.clearServiceFilters();
    this.openService(id);
    setTimeout(() => document.getElementById('service-history-panel')?.scrollIntoView({block:'start'}),0);
  }
  private globalRowsCache:GlobalServiceRow[]=[];
  private globalRowsInput?:MetricObservation[];
  private globalRowsThresholdKey='';
  get globalServices():GlobalServiceRow[] {
    const key=[this.warningRate,this.criticalRate,this.minimumRequests,this.slowP95].join('|');
    if(this.globalRowsInput!==this.windowMetrics || this.globalRowsThresholdKey!==key){
      this.globalRowsInput=this.windowMetrics;
      this.globalRowsThresholdKey=key;
      this.globalRowsCache=this.serviceRows.map(row=>({id:row.id,name:row.name,health:this.health(row),
        requests:row.metrics.requests?.metricValue,failed:row.metrics.failed?.metricValue,
        failure:row.metrics.failure?.metricValue,p95:row.metrics.p95?.metricValue}));
    }
    return this.globalRowsCache;
  }
  readonly displayTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Local time';
  platformTitle(platform: Platform): string {
    return platform === 'BACKEND' ? 'Backend & infrastructure' : platform === 'WEB' ? 'Web' : platform === 'IOS' ? 'iOS' : 'Android';
  }
  overview: DashboardOverview | null = null;
  loading = true;
  search = '';
  readonly windows = [5, 15, 30, 60];
  windowMinutes = 5;
  windowMetrics: MetricObservation[] = [];
  windowError = '';
  windowLoading = false;
  get serviceSummaryAvailable(): boolean { return !this.windowError && (!this.windowLoading || this.windowMetrics.length > 0); }
  private windowRequest = 0;
  private overviewSubscription?: import('rxjs').Subscription;
  private windowSubscription?: import('rxjs').Subscription;
  get windowStart(): string | undefined { return this.windowMetrics[0]?.windowStartUtc; }
  get windowEnd(): string | undefined { return this.windowMetrics[0]?.windowEndUtc; }
  selectWindow(minutes: number): void {
    this.windowRequest++; this.windowSubscription?.unsubscribe(); this.windowLoading = false;
    this.windowMinutes = minutes; this.page = 0; this.windowMetrics = []; this.windowError = '';
    this.refreshWindow();
  }
  private refreshWindow(): void {
    if ((!this.globalView && this.selectedPlatform !== 'BACKEND') || this.windowLoading) return;
    if(this.globalView) {
      if(Date.now()-this.lastGlobalWindowAttempt<30000) return;
      this.lastGlobalWindowAttempt=Date.now();
    }
    const request = ++this.windowRequest;
    this.windowSubscription?.unsubscribe();
    this.windowLoading = true;
    this.windowSubscription = this.dashboard.loadServiceWindow(this.globalView ? 30 : this.windowMinutes).subscribe({
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
  historyMinutes = 60;
  historyMetric: ServiceMetric = 'p95';
  history: MetricObservation[] = [];
  historyLoading = false;
  historyError = '';
  private historySubscription?: import('rxjs').Subscription;
  openService(id: string): void { this.selectedServiceId = id; this.loadHistory(); }
  closeService(): void { this.historySubscription?.unsubscribe(); this.selectedServiceId = ''; this.history = []; }
  loadHistory(): void {
    this.historySubscription?.unsubscribe();
    this.history = []; this.historyError = ''; this.historyLoading = false;
    if (!/^SERVICE-[A-Fa-f0-9]{16}$/.test(this.selectedServiceId)) {
      this.historyError = 'History requires a resolved Dynatrace service ID.'; return;
    }
    this.historyLoading = true;
    this.historySubscription = this.dashboard.loadServiceHistory(this.selectedServiceId, this.historyMinutes).subscribe({
      next: data => { this.history = data; this.historyLoading = false; },
      error: () => { this.historyError = 'Historical data unavailable'; this.historyLoading = false; }
    });
  }
  get historyPoints(): {x:number;y:number;value:number;time:string}[] {
    const data = this.history.filter(m => this.metricKey(m.metricName) === this.historyMetric && Number.isFinite(m.metricValue));
    const end = Date.now(), start = end - this.historyMinutes * 60000;
    const max = Math.max(1, ...data.map(m => m.metricValue));
    return data.map(m => ({x: 30 + 740 * (Date.parse(m.windowEndUtc)-start)/(end-start), y: 190 - 170*m.metricValue/max, value:m.metricValue,time:m.windowEndUtc}));
  }
  get historyMaximum(): number { return Math.max(1, ...this.historyPoints.map(p => p.value)); }
  private restoreThresholds(): void {
    try {
      const saved = JSON.parse(localStorage.getItem('observability.health.v1') || 'null');
      if (saved && [saved.warningRate,saved.criticalRate,saved.minimumRequests,saved.slowP95].every(n => typeof n === 'number' && Number.isFinite(n) && n > 0) && saved.warningRate <= saved.criticalRate && saved.criticalRate <= 100) {
        this.warningRate = saved.warningRate; this.criticalRate = saved.criticalRate;
        this.minimumRequests = saved.minimumRequests; this.slowP95 = saved.slowP95;
      }
    } catch { /* Storage may be disabled. Defaults remain available. */ }
  }
  private persistThresholds(): void {
    try { localStorage.setItem('observability.health.v1', JSON.stringify({warningRate:this.warningRate,criticalRate:this.criticalRate,minimumRequests:this.minimumRequests,slowP95:this.slowP95})); }
    catch { /* Current session values remain available. */ }
  }
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
    if (value.trim() && Number.isFinite(n) && n > 0 && (key !== 'warningRate' || n <= this.criticalRate) && (key !== 'criticalRate' || (n >= this.warningRate && n <= 100))) { this[key] = n; this.persistThresholds(); }
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
  clearServiceFilters(): void { this.search = ''; this.healthFilter = 'ALL'; this.page = 0; }
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
    this.restoreThresholds();
    this.refresh();
    this.stream = this.dashboard.observeChanges(() => this.refresh());
    // Re-fetch occasionally even if a proxy disconnects or drops server-sent events.
    this.fallbackRefresh = setInterval(() => this.refresh(), 30000);
  }

  ngOnDestroy(): void {
    this.historySubscription?.unsubscribe();
    this.overviewSubscription?.unsubscribe();
    this.stream?.close();
    this.windowSubscription?.unsubscribe();
    if (this.fallbackRefresh) clearInterval(this.fallbackRefresh);
  }

  selectPlatform(platform: Platform): void {
    const fromGlobal=this.globalView;
    this.globalView=false;
    this.selectedPlatform = platform;
    if (fromGlobal || platform !== 'BACKEND') this.resetServiceWindow();
    if (fromGlobal && platform === 'BACKEND') { this.windowMinutes=30; this.page=0; this.closeService(); }
    if (platform === 'BACKEND') this.refreshWindow();
  }

  get selectedMetrics(): MetricObservation[] {
    return latestPlatformMetrics(this.overview?.metrics ?? [], this.selectedPlatform);
  }

  get lastUpdated(): string {
    const metrics = this.globalView ? [...(this.overview?.metrics ?? []),...this.windowMetrics] : this.selectedPlatform === 'BACKEND' ? [...this.selectedMetrics, ...this.windowMetrics] : this.selectedMetrics;
    const values = metrics.map(metric => Date.parse(metric.collectedAtUtc)).filter(Number.isFinite);
    return values.length ? new Date(Math.max(...values)).toLocaleString() : 'No data received';
  }

  refresh(): void {
    this.refreshWindow();
    if (this.overviewSubscription && !this.overviewSubscription.closed) return;
    this.overviewSubscription = this.dashboard.loadOverview().subscribe({
      next: data => { this.overview = data; this.errorMessage = ''; this.loading = false; },
      error: () => { this.errorMessage = 'Backend unavailable. Verify the local server and proxy.'; this.loading = false; }
    });
  }
}
