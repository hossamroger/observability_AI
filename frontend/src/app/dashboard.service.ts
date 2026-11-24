import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DashboardOverview, ApplicationRange, MetricObservation } from './metric.model';

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly apiPath = '/api/v1';
  constructor(private readonly http: HttpClient) {}

  loadOverview(): Observable<DashboardOverview> {
    return this.http.get<DashboardOverview>(`${this.apiPath}/overview`, {params: {includeServiceMetrics: false}});
  }

  loadServiceWindow(minutes: number): Observable<import('./metric.model').MetricObservation[]> {
    return this.http.get<import('./metric.model').MetricObservation[]>(`${this.apiPath}/service-window`, {params: {minutes}});
  }

  loadServiceHistory(serviceId: string, minutes: number): Observable<import('./metric.model').MetricObservation[]> {
    return this.http.get<import('./metric.model').MetricObservation[]>(`${this.apiPath}/service-history`, {params: {serviceId, minutes}});
  }

  loadApplicationRange(params: Record<string,string>): Observable<ApplicationRange> {
    return this.http.post<ApplicationRange>(`${this.apiPath}/application-range`, null, {params});
  }

  loadApplicationHistory(platform: string, metric: string, minutes: number): Observable<MetricObservation[]> {
    return this.http.get<MetricObservation[]>(`${this.apiPath}/application-history`, {params: {platform,metric,minutes}});
  }

  observeChanges(onChange: () => void): EventSource {
    const stream = new EventSource(`${this.apiPath}/stream`);
    stream.addEventListener('metrics-updated', onChange);
    return stream;
  }
}
