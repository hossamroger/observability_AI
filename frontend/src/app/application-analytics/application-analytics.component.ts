import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Component, Input, OnChanges, OnDestroy, SimpleChanges } from '@angular/core';
import { Subscription } from 'rxjs';
import { DashboardService } from '../dashboard.service';
import { MetricObservation, Platform, SourceOutcome, ApplicationRange } from '../metric.model';

interface MetricDefinition {
  key: string;
  label: string;
  source: 'GA4' | 'BIGQUERY';
  tone?: 'positive' | 'negative';
}

@Component({
  selector: 'app-application-analytics',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './application-analytics.component.html',
  styleUrls: ['./application-analytics.component.css']
})
export class ApplicationAnalyticsComponent implements OnChanges, OnDestroy {
  @Input() selectedPlatform: Platform = 'WEB';
  @Input('selectedMetrics') liveMetrics: MetricObservation[] = [];
  rangeChoice = 'live';
  activeSection = 'overview';
  versionSearch = '';
  versionSort = 'session_start_eligible_sessions';
  versionDescending = true;
  activeHistoryPoint: {time:string;from:string;value:number;x:number;y:number} | null = null;

  get sections(): {key:string;label:string}[] {
    const sections = [{key:'overview',label:'Overview'}];
    if (this.isMobile) sections.push({key:'performance',label:'Performance'},{key:'versions',label:'App versions'});
    sections.push({key:'trends',label:'Trends'},{key:'diagnostics',label:'Data quality'});
    return sections;
  }

  selectSection(key: string): void { this.activeSection = key; }

  get filteredVersionRows(): {version:string;metrics:Record<string,number>}[] {
    const search = this.versionSearch.trim().toLowerCase();
    return this.versionRows.filter(row => row.version.toLowerCase().includes(search)).sort((a,b) => {
      if (this.versionSort === 'version') return (this.versionDescending ? -1 : 1) * a.version.localeCompare(b.version, undefined, {numeric:true});
      const av = a.metrics[this.versionSort], bv = b.metrics[this.versionSort];
      if (av == null || bv == null) return av == null ? (bv == null ? a.version.localeCompare(b.version) : 1) : -1;
      return (this.versionDescending ? -1 : 1) * (av-bv) || a.version.localeCompare(b.version);
    });
  }

  sortVersions(key: string): void {
    this.versionDescending = this.versionSort === key ? !this.versionDescending : key !== 'version';
    this.versionSort = key;
  }

  versionSortIndicator(key: string): string {
    return key === this.versionSort ? (this.versionDescending ? ' ↓' : ' ↑') : '';
  }

  get historyUnit(): string { return this.historyDisplayedMetric.endsWith('_ms') ? 's' : this.historyDisplayedMetric === 'observed_sessions' ? 'sessions' : 'events'; }
  get historyGrid(): {value:number;y:number}[] {
    return [0,1,2,3,4].map(i => ({value:this.historyMax*i/4,y:175-145*i/4}));
  }
  get historyLatestValue(): number | null {
    const rows = this.historyRows;
    return rows.length ? this.displayValue(rows[rows.length-1]) : null;
  }
  customFrom = '';
  customTo = '';
  rangeReport?: ApplicationRange;
  rangeLoading = false;
  rangeError = '';
  private rangeSubscription?: Subscription;

  historyPeriod = 360;
  historySource: 'bigquery' | 'stored' = 'bigquery';
  historyReport?: ApplicationRange;
  historyDisplayedMetric = '';
  historyMetric = 'observed_sessions';
  historyRows: MetricObservation[] = [];
  historyLoading = false;
  historyError = '';
  historyLoaded = false;
  historyRequestedAt = 0;
  private historySubscription?: Subscription;

  constructor(private readonly dashboard: DashboardService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['selectedPlatform'] && !changes['selectedPlatform'].firstChange) {
      this.historyMetric = 'observed_sessions';
      this.activeSection = 'overview';
      this.versionSearch = '';
      this.resetHistory();
    }
  }

  ngOnDestroy(): void {
    this.rangeSubscription?.unsubscribe();
    this.historySubscription?.unsubscribe();
  }

  get selectedMetrics(): MetricObservation[] {
    if (!this.rangeReport) return this.liveMetrics;
    return [...this.liveMetrics.filter(m => m.sourceSystem === 'GA4'),
      ...this.rangeReport.metrics.filter(m => m.platform === this.selectedPlatform)];
  }

  selectRange(choice: string): void {
    this.rangeChoice = choice;
    if (choice === 'live') {
      this.rangeSubscription?.unsubscribe();
      this.rangeReport = undefined;
      this.rangeLoading = false;
      this.rangeError = '';
    }
  }

  applyRange(): void {
    if (this.rangeLoading || this.rangeChoice === 'live') return;
    this.rangeError = '';
    let params: Record<string,string>;
    if (this.rangeChoice === 'custom') {
      const from = new Date(this.customFrom), to = new Date(this.customTo);
      if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime()) || from >= to) {
        this.rangeError = 'Choose a valid start and end time.';
        return;
      }
      params = {from:from.toISOString(),to:to.toISOString()};
    } else params = {minutes:this.rangeChoice};
    this.rangeLoading = true;
    this.rangeSubscription = this.dashboard.loadApplicationRange(params).subscribe({
      next: data => { this.rangeReport = data; this.rangeLoading = false; },
      error: err => {
        this.rangeLoading = false;
        this.rangeError = err.status === 429 ? 'Reporting cooldown: wait at least one minute before trying again.'
          : err.status === 400 ? 'Invalid range. Select 5 minutes to 7 days, ending before the source delay.'
          : err.status === 503 ? 'On-demand analytics is disabled or BigQuery is unavailable.'
          : 'BigQuery report failed. Check export availability, collection logs and the query byte limit.';
      }
    });
  }

  trackHistoryOption(index: number, option: {key:string;label:string}): string { return option.key; }

  get historyOptions(): {key:string;label:string}[] {
    const items = [{key:'observed_sessions',label:'Observed sessions'},{key:'observed_events',label:'Observed events'}];
    if (this.isMobile) items.push({key:'session_to_home_p95_ms',label:'Home screen P95 (s)'},{key:'session_to_first_screen_p95_ms',label:'First screen P95 (s)'},{key:'payment_initiated_events',label:'Payment initiated events'});
    if (this.selectedPlatform === 'IOS') items.push({key:'purchase_events',label:'Purchase events'},{key:'payment_failure_events',label:'Payment failure events'});
    if (this.selectedPlatform === 'WEB') items.push({key:'workflow_total_events',label:'Workflow events'});
    return items;
  }

  resetHistory(): void {
    this.activeHistoryPoint = null;
    this.historySubscription?.unsubscribe();
    this.historyRows = [];
    this.historyReport = undefined;
    this.historyDisplayedMetric = '';
    this.historyLoaded = false;
    this.historyLoading = false;
    this.historyError = '';
  }

  changeHistoryMetric(metric: string): void {
    this.historyMetric = metric;
    this.activeHistoryPoint = null;
    if (this.historySource === 'bigquery' && this.historyReport && !this.historyLoading) {
      this.showSourceHistory(this.historyReport);
    } else {
      this.resetHistory();
    }
  }

  changeHistoryPeriod(period: number): void {
    this.historyPeriod = period;
    this.resetHistory();
  }

  changeHistorySource(source: 'bigquery' | 'stored'): void {
    this.historySource = source;
    this.resetHistory();
  }

  get historyLabel(): string {
    return this.historyOptions.find(m => m.key === this.historyDisplayedMetric)?.label ?? '';
  }

  private showSourceHistory(report: ApplicationRange): void {
    this.historyDisplayedMetric = this.historyMetric;
    this.historyRows = report.metrics.filter(m => m.platform === this.selectedPlatform && m.metricName === this.historyMetric)
      .sort((a,b) => Date.parse(a.windowEndUtc) - Date.parse(b.windowEndUtc));
  }

  loadHistory(): void {
    this.resetHistory();
    this.historyLoading = true;
    this.historyLoaded = true;
    this.historyRequestedAt = Date.now();
    this.historyDisplayedMetric = this.historyMetric;
    if (this.historySource === 'bigquery') {
      this.historySubscription = this.dashboard.loadApplicationSourceHistory(this.historyPeriod).subscribe({
        next: report => {
          this.historyReport = report;
          this.showSourceHistory(report);
          this.historyLoading = false;
        },
        error: err => {
          this.historyLoading = false;
          this.historyError = err.status === 429 ? 'Reporting cooldown: wait at least one minute and retry.'
            : err.status === 503 ? 'On-demand BigQuery analytics is disabled or unavailable.'
            : 'BigQuery history failed. Check export availability, query byte limit and backend logs.';
        }
      });
    } else {
      const platform = this.selectedPlatform, metric = this.historyMetric;
      this.historySubscription = this.dashboard.loadApplicationHistory(platform,metric,this.historyPeriod).subscribe({
        next: rows => {
          this.historyRows = rows.filter(m => m.platform === platform && m.metricName === metric)
            .sort((a,b) => Date.parse(a.windowEndUtc) - Date.parse(b.windowEndUtc));
          this.historyLoading = false;
        },
        error: () => { this.historyError = 'Stored history unavailable.'; this.historyLoading = false; }
      });
    }
  }

  get historyEnd(): number { return this.historyReport ? Date.parse(this.historyReport.windowEndUtc) : this.historyRequestedAt; }
  get historyStart(): number { return this.historyReport ? Date.parse(this.historyReport.windowStartUtc) : this.historyEnd - this.historyPeriod * 60000; }
  get historyMax(): number { return Math.max(1,...this.historyRows.map(m => this.displayValue(m))); }
  get historyTicks(): number[] {
    return [0,1,2,3,4].map(i => this.historyStart + (this.historyEnd-this.historyStart)*i/4);
  }
  get historyPoints(): {x:number;y:number;time:string;from:string;value:number}[] {
    const end = this.historyEnd, start = this.historyStart, max = this.historyMax;
    if (!this.historyRows.length || end <= start) return [];
    return this.historyRows.filter(m => Date.parse(m.windowEndUtc) > start && Date.parse(m.windowEndUtc) <= end)
      .map(m => ({
        x:44 + 700 * (Date.parse(m.windowEndUtc)-start)/(end-start),
        y:175 - 145 * this.displayValue(m)/max,
        time:m.windowEndUtc,from:m.windowStartUtc,value:this.displayValue(m)
      }));
  }

  get historySegments(): {x1:number;y1:number;x2:number;y2:number}[] {
    const points = this.historyPoints;
    // Connect only adjacent source buckets. Stored rolling snapshots can have irregular gaps.
    if (!this.historyReport?.bucketSeconds) return [];
    const interval = this.historyReport.bucketSeconds*1000;
    return points.slice(1).flatMap((point,i) => {
      const previous = points[i];
      return Date.parse(point.time)-Date.parse(previous.time) === interval
        ? [{x1:previous.x,y1:previous.y,x2:point.x,y2:point.y}] : [];
    });
  }
  @Input() loading = false;
  @Input() sourceOutcomes: SourceOutcome[] = [];
  outcome(source: string): SourceOutcome | undefined { return source === 'BIGQUERY' && this.rangeReport ? undefined : this.sourceOutcomes.find(o => o.sourceSystem === source); }

  get isMobile(): boolean { return this.selectedPlatform === 'ANDROID' || this.selectedPlatform === 'IOS'; }
  get realtimeWindow(): MetricObservation | undefined { return this.findMetric('GA4','active_users_last_30m') ?? this.selectedMetrics.find(m => m.sourceSystem === 'GA4'); }

  findMetric(source: string, name: string): MetricObservation | undefined {
    return this.selectedMetrics.find(m => m.sourceSystem === source && m.metricName === name &&
      (source !== 'BIGQUERY' || m.serviceName === 'GA4_INTRADAY'));
  }

  value(definition: MetricDefinition): number | undefined {
    return this.findMetric(definition.source, definition.key)?.metricValue;
  }

  get overviewMetrics(): MetricDefinition[] {
    return [
      {key:'active_users_last_30m',label:'Active users',source:'GA4'},
      {key:'observed_sessions',label:'Observed sessions',source:'BIGQUERY'},
      {key:this.isMobile ? 'screen_view_events' : 'page_view_events',label:this.isMobile ? 'Screen views' : 'Page views',source:'BIGQUERY'},
      ...(this.isMobile ? [{key:'service_click_events',label:'Service clicks',source:'BIGQUERY' as const}] : [])
    ];
  }

  windowMinutes(metric: MetricObservation): number | null {
    const minutes = (Date.parse(metric.windowEndUtc) - Date.parse(metric.windowStartUtc)) / 60000;
    return Number.isFinite(minutes) && minutes > 0 ? minutes : null;
  }

  get operationMetrics(): MetricDefinition[] {
    if (this.selectedPlatform === 'WEB') return [
      {key:'workflow_total_events',label:'Total workflow events',source:'BIGQUERY'},
      {key:'workflow_initiation_success_events',label:'Initiation success events',source:'BIGQUERY',tone:'positive'},
      {key:'workflow_initiation_failure_events',label:'Initiation failure events',source:'BIGQUERY',tone:'negative'},
      {key:'workflow_success_events',label:'Action success events',source:'BIGQUERY',tone:'positive'},
      {key:'workflow_failure_events',label:'Action failure events',source:'BIGQUERY',tone:'negative'}
    ];
    const metrics: MetricDefinition[] = [{key:'payment_initiated_events',label:'Initiated events',source:'BIGQUERY'}];
    if (this.selectedPlatform === 'IOS') metrics.push(
      {key:'purchase_events',label:'Purchase events',source:'BIGQUERY',tone:'positive'},
      {key:'payment_failure_events',label:'Failure events',source:'BIGQUERY',tone:'negative'}
    );
    return metrics;
  }

  get qualityChecks(): {label:string;key:string;denominator?:string}[] {
    const checks: {label:string;key:string;denominator?:string}[] = [
      {label:'Events missing user or session ID',key:'quality_missing_session_key_events',denominator:'observed_events'},
      {label:'Session starts missing user or session ID',key:'quality_missing_start_key_events',denominator:'session_start_events'}
    ];
    if (this.selectedPlatform === 'IOS') checks.push({label:'Initiation / purchase events missing correlation ID',key:'quality_missing_payment_key_events'});
    if (this.selectedPlatform === 'WEB') checks.push({label:'Workflow events with unclassified result',key:'quality_unclassified_workflow_events',denominator:'workflow_total_events'});
    return checks;
  }

  qualityValue(key: string): number | undefined { return this.performanceMetric(key)?.metricValue; }

  get technicalMetrics(): MetricObservation[] {
    const shown = new Set([...this.overviewMetrics,...this.operationMetrics].map(m => m.source+'|'+m.key));
    return this.activityMetrics.filter(m => !shown.has(m.sourceSystem+'|'+m.metricName) && !(m.sourceSystem === 'BIGQUERY' && m.metricName.startsWith('quality_')));
  }

  freshness(metric: MetricObservation | undefined, source: 'GA4' | 'BIGQUERY'): string {
    if (source === 'BIGQUERY' && this.rangeReport) return this.rangeReport.partial ? 'Report: partial coverage' : 'On-demand report';
    const outcome = this.outcome(source);
    if (outcome && outcome.status !== 'SUCCESS') {
      return outcome.status === 'FAILED' ? 'Collection failed' : outcome.status === 'EMPTY' ? 'No observations returned' : 'Partial collection';
    }
    if (!metric) return 'No data';
    const age = Date.now() - Date.parse(metric.collectedAtUtc);
    if (!Number.isFinite(age)) return 'Unknown';
    return age > (source === 'GA4' ? 120000 : 600000) ? 'Stale' : 'Recently collected';
  }

  readonly durationColumns = [
    {key:'samples',label:'Samples'}, {key:'average_ms',label:'Average (s)'}, {key:'p50_ms',label:'P50 (s)'}, {key:'p95_ms',label:'P95 (s)'}, {key:'p99_ms',label:'P99 (s)'}
  ];
  get durationRows(): {key:string;label:string}[] {
    const rows = [{key:'session_to_first_screen',label:'Session start → First screen'}, {key:'session_to_home',label:'Session start → Home screen'}];
    return rows;
  }
  get bigQueryWindow(): MetricObservation | undefined { return this.selectedMetrics.find(m => m.sourceSystem === 'BIGQUERY' && m.serviceName === 'GA4_INTRADAY'); }
  performanceMetric(name: string): MetricObservation | undefined {
    return this.selectedMetrics.find(m => m.sourceSystem === 'BIGQUERY' && m.serviceName === 'GA4_INTRADAY' && m.metricName === name);
  }
  get activityMetrics(): MetricObservation[] {
    return this.selectedMetrics.filter(m => m.sourceSystem !== 'BIGQUERY' || (m.serviceName === 'GA4_INTRADAY' && !/^(session_to_|payment_to_|session_start_eligible_sessions$)/.test(m.metricName)));
  }

  readonly slowThresholds = [5,10,30,60];
  readonly sessionStages = [{key:'session_to_first_screen',label:'First screen observed'},{key:'session_to_home',label:'Home screen observed'}];
  get eligibleSessions(): number | undefined { return this.performanceMetric('session_start_eligible_sessions')?.metricValue; }
  percent(numerator: number | undefined, denominator: number | undefined): number | null {
    return numerator != null && denominator != null && denominator > 0 && numerator <= denominator ? 100 * numerator / denominator : null;
  }
  notObserved(samples: number | undefined): number | null {
    const total = this.eligibleSessions;
    return samples != null && total != null && samples <= total ? total - samples : null;
  }
  displayValue(metric: MetricObservation): number { return metric.metricUnit === 'ms' ? metric.metricValue / 1000 : metric.metricValue; }
  get versionRows(): {version:string;metrics:Record<string,number>}[] {
    const groups = new Map<string,Record<string,number>>();
    for (const m of this.selectedMetrics) {
      if (m.sourceSystem !== 'BIGQUERY' || !m.serviceName.startsWith('APP_VERSION:')) continue;
      const version = m.serviceName.slice('APP_VERSION:'.length);
      const values = groups.get(version) ?? {};
      values[m.metricName] = this.displayValue(m);
      groups.set(version, values);
    }
    return Array.from(groups.entries()).map(([version,metrics]) => ({version,metrics}))
      .sort((a,b) => (b.metrics['session_start_eligible_sessions'] ?? 0) - (a.metrics['session_start_eligible_sessions'] ?? 0) || a.version.localeCompare(b.version));
  }


}
