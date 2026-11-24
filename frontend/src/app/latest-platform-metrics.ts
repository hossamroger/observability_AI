import { MetricObservation, Platform } from './metric.model';

export function latestPlatformMetrics(data:readonly MetricObservation[], platform:Platform):MetricObservation[] {
    // Use the latest source-wide window, not a different historical window for each platform.
    const latestWindows = new Map<string, number>();
    for (const m of data) {
      if (m.sourceSystem !== 'BIGQUERY' && m.sourceSystem !== 'GA4') continue;
      const end = Date.parse(m.windowEndUtc);
      if (Number.isFinite(end)) latestWindows.set(m.sourceSystem, Math.max(end, latestWindows.get(m.sourceSystem) ?? -Infinity));
    }
    const seen = new Set<string>();
    return data.filter(metric => {
      if (metric.platform !== platform) return false;
      if (latestWindows.has(metric.sourceSystem) && Date.parse(metric.windowEndUtc) !== latestWindows.get(metric.sourceSystem)) return false;
      const key = `${metric.sourceSystem}|${metric.serviceName}|${metric.metricName}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }
