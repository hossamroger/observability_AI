import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { MetricObservation, Platform, SourceOutcome } from '../metric.model';

interface MetricDefinition {
  key: string;
  label: string;
  source: 'GA4' | 'BIGQUERY';
  tone?: 'positive' | 'negative';
}

@Component({
  selector: 'app-application-analytics',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './application-analytics.component.html',
  styleUrls: ['./application-analytics.component.css']
})
export class ApplicationAnalyticsComponent {
  @Input() selectedPlatform: Platform = 'WEB';
  @Input() selectedMetrics: MetricObservation[] = [];
  @Input() loading = false;
  @Input() sourceOutcomes: SourceOutcome[] = [];
  outcome(source: string): SourceOutcome | undefined { return this.sourceOutcomes.find(o => o.sourceSystem === source); }

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

  get operationMetrics(): MetricDefinition[] {
    if (this.selectedPlatform === 'WEB') return [
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

  get technicalMetrics(): MetricObservation[] {
    const shown = new Set([...this.overviewMetrics,...this.operationMetrics].map(m => m.source+'|'+m.key));
    return this.activityMetrics.filter(m => !shown.has(m.sourceSystem+'|'+m.metricName));
  }

  freshness(metric: MetricObservation | undefined, source: 'GA4' | 'BIGQUERY'): string {
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
