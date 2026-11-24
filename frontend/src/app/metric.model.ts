export type Platform = 'WEB' | 'ANDROID' | 'IOS' | 'BACKEND' | 'UNKNOWN';

export interface MetricObservation {
  id: string;
  sourceSystem: string;
  platform: Platform;
  serviceName: string;
  metricName: string;
  metricValue: number;
  metricUnit: string;
  collectedAtUtc: string;
  windowStartUtc: string;
  windowEndUtc: string;
  demo: boolean;
}

export interface SourceOutcome {
  sourceSystem: string;
  status: 'SUCCESS' | 'FAILED' | 'EMPTY' | 'PARTIAL';
  errorCategory: string | null;
  recordCount: number;
  executedAtUtc: string;
}

export interface DashboardOverview {
  dataMode: 'DEMO' | 'LIVE';
  generatedAtUtc: string;
  metrics: MetricObservation[];
  sourceOutcomes?: SourceOutcome[];
}
