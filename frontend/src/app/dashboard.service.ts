import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DashboardOverview } from './metric.model';

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly apiPath = '/api/v1';
  constructor(private readonly http: HttpClient) {}

  loadOverview(): Observable<DashboardOverview> {
    return this.http.get<DashboardOverview>(`${this.apiPath}/overview`);
  }

  loadServiceWindow(minutes: number): Observable<import('./metric.model').MetricObservation[]> {
    return this.http.get<import('./metric.model').MetricObservation[]>(`${this.apiPath}/service-window`, {params: {minutes}});
  }

  observeChanges(onChange: () => void): EventSource {
    const stream = new EventSource(`${this.apiPath}/stream`);
    stream.addEventListener('metrics-updated', onChange);
    return stream;
  }
}
