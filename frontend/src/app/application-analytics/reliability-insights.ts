import { MetricObservation, Platform } from '../metric.model';

export interface InsightThresholds {
  minSamples: number;
  firstP95Warning: number;
  firstP95Critical: number;
  homeP95Warning: number;
  homeP95Critical: number;
  firstCoverageWarning: number;
  firstCoverageCritical: number;
  homeCoverageWarning: number;
  homeCoverageCritical: number;
  slowShareWarning: number;
  slowShareCritical: number;
  versionIncreasePercent: number;
  versionIncreaseSeconds: number;
}
export const DEFAULT_THRESHOLDS: Readonly<InsightThresholds> = {
  minSamples:100, firstP95Warning:5, firstP95Critical:10, homeP95Warning:10, homeP95Critical:30,
  firstCoverageWarning:95, firstCoverageCritical:90, homeCoverageWarning:95, homeCoverageCritical:85,
  slowShareWarning:10, slowShareCritical:25, versionIncreasePercent:50, versionIncreaseSeconds:5
};

export type Severity = 'critical' | 'warning' | 'info';
export interface Finding {
  id:string; severity:Severity; title:string; evidence:string; action:string;
  section:'performance'|'versions'|'diagnostics'|'overview';
}
export interface InsightResult { findings:Finding[]; evaluated:number; skipped:number; blocked:boolean; }
export interface InsightInput {
  platform:Platform; metrics:readonly MetricObservation[]; sourceState:string; historical:boolean; nowMs:number; thresholds:InsightThresholds;
}
const fmt = (n:number) => new Intl.NumberFormat('en-US',{maximumFractionDigits:1}).format(n);
const valid = (n:number|undefined): n is number => n != null && Number.isFinite(n) && n >= 0;
const count = (n:number|undefined): n is number => valid(n) && Number.isSafeInteger(n);
const ratio = (n:number|undefined,d:number|undefined):number|undefined =>
  count(n) && count(d) && d>0 && n<=d ? 100*n/d : undefined;

export function validThresholds(t:InsightThresholds):boolean {
  return Object.values(t).every(n=>typeof n==='number' && Number.isFinite(n) && n>0)
    && Number.isInteger(t.minSamples) && t.minSamples>=20 && t.minSamples<=100000
    && t.firstP95Warning<t.firstP95Critical && t.homeP95Warning<t.homeP95Critical
    && t.firstCoverageCritical<t.firstCoverageWarning && t.firstCoverageWarning<=100
    && t.homeCoverageCritical<t.homeCoverageWarning && t.homeCoverageWarning<=100
    && t.slowShareWarning<t.slowShareCritical && t.slowShareCritical<=100
    && t.versionIncreasePercent<=1000;
}

/** Deterministic checks. No incident inference from missing events, and no cross-window comparisons. */
export function evaluateInsights(input:InsightInput):InsightResult {
  const {platform,sourceState,thresholds:t}=input;
  const result:InsightResult={findings:[],evaluated:0,skipped:0,blocked:false};
  const add=(id:string,severity:Severity,title:string,evidence:string,action:string,section:Finding['section']) =>
    result.findings.push({id,severity,title,evidence,action,section});
  const overall=input.metrics.find(m=>m.platform===platform && m.sourceSystem==='BIGQUERY' && m.serviceName==='GA4_INTRADAY');
  const partial=sourceState==='Partial collection'||sourceState==='Report: partial coverage';
  const age=overall ? input.nowMs-Date.parse(overall.collectedAtUtc) : NaN;
  const stale=!input.historical && (!Number.isFinite(age)||age>600000||age < -60000);
  if (!overall || stale || (!partial && !['Recently collected','On-demand report'].includes(sourceState)) || !validThresholds(t)) {
    result.blocked=true;
    add('coverage','info','Assessment unavailable',overall ? (stale ? 'The selected observations are stale or have an invalid collection timestamp.' : 'BigQuery status: '+sourceState+'.') : 'No BigQuery observations for the selected platform and window.',
      'Review source status before interpreting reliability.', 'diagnostics');
    return result;
  }
  if(partial) add('partial','info','Partial source coverage','The source reported partial coverage. Findings apply only to the available observations.',
    'Check collection diagnostics; missing data is not evidence of good health.','diagnostics');
  const scoped=input.metrics.filter(m=>m.platform===platform && m.sourceSystem==='BIGQUERY' &&
    m.windowStartUtc===overall.windowStartUtc && m.windowEndUtc===overall.windowEndUtc && m.demo===overall.demo);
  const metrics=new Map(scoped.filter(m=>m.serviceName==='GA4_INTRADAY').map(m=>[m.metricName,m.metricValue]));
  const get=(key:string)=>metrics.get(key);
  const mobile=platform==='IOS'||platform==='ANDROID';
  const eligible=get('session_start_eligible_sessions');
  if(mobile) {
    for (const stage of [
      {key:'session_to_first_screen',label:'First screen',p95Warn:t.firstP95Warning,p95Crit:t.firstP95Critical,covWarn:t.firstCoverageWarning,covCrit:t.firstCoverageCritical},
      {key:'session_to_home',label:'Home screen',p95Warn:t.homeP95Warning,p95Crit:t.homeP95Critical,covWarn:t.homeCoverageWarning,covCrit:t.homeCoverageCritical}
    ]) {
      const matched=get(stage.key+'_samples'), rate=ratio(matched,eligible);
      if(rate!==undefined && count(eligible) && eligible>=t.minSamples && count(matched)) {
        result.evaluated++;
        if(rate<stage.covWarn) add(stage.key+'_coverage',rate<stage.covCrit?'critical':'warning',stage.label+' coverage below target',
          fmt(eligible-matched)+' of '+fmt(eligible)+' eligible sessions did not record this screen within 5 minutes ('+fmt(100-rate)+'%).',
          'Check screen instrumentation and journey traces; unobserved sessions are not confirmed failures.','performance');
      } else result.skipped++;
      const p95=get(stage.key+'_p95_ms');
      const slow=get(stage.key+'_over_10s'), slowShare=ratio(slow,matched);
      if(count(matched)&&matched>=t.minSamples&&valid(p95)&&slowShare!==undefined&&count(slow)) {
        result.evaluated++;
        const seconds=p95/1000;
        if(seconds>stage.p95Warn||slowShare>t.slowShareWarning) {
          add(stage.key+'_latency',seconds>stage.p95Crit||slowShare>t.slowShareCritical?'critical':'warning',
            stage.label+' latency above target',
            'P95 '+fmt(seconds)+'s; '+fmt(slow)+' of '+fmt(matched)+' matched sessions ('+fmt(slowShare)+'%) took over 10s.',
            'Review startup traces and dependent API latency in this window.','performance');
        }
      } else result.skipped++;
    }
    // Compare newest two numeric version labels in the same window, not release-time baselines.
    const versions=new Map<string,Map<string,number>>();
    for(const m of scoped) {
      if(!m.serviceName.startsWith('APP_VERSION:')) continue;
      const version=m.serviceName.slice(12);
      if(!/^\d+\.\d+\.\d+(\.\d+)?$/.test(version)) continue;
      const row=versions.get(version)??new Map<string,number>();
      row.set(m.metricName,m.metricValue); versions.set(version,row);
    }
    const ordered=[...versions.keys()].sort((a,b)=> {
      const av=a.split('.').map(Number),bv=b.split('.').map(Number);
      for(let i=0;i<Math.max(av.length,bv.length);i++){const delta=(bv[i]??0)-(av[i]??0);if(delta!==0)return delta;}
      return b.localeCompare(a);
    });
    const [newest,previous]=ordered;
    const n=versions.get(newest),p=versions.get(previous);
    const ns=n?.get('session_to_home_samples'),ps=p?.get('session_to_home_samples');
    const nv=n?.get('session_to_home_p95_ms'),pv=p?.get('session_to_home_p95_ms');
    if(count(ns)&&count(ps)&&ns>=t.minSamples&&ps>=t.minSamples&&valid(nv)&&valid(pv)&&pv>0) {
      result.evaluated++;
      const increase=(nv-pv)/pv*100;
      if(increase>=t.versionIncreasePercent&&(nv-pv)/1000>=t.versionIncreaseSeconds)
        add('version_gap','warning','Potential version performance regression',
          newest+' home P95 '+fmt(nv/1000)+'s ('+fmt(ns)+' matched) vs '+previous+' '+fmt(pv/1000)+'s ('+fmt(ps)+' matched); +'+fmt(increase)+'%.',
          'Compare device, network and OS cohorts; verify release timing before attributing a regression.','versions');
    } else result.skipped++;
    if(platform==='ANDROID') add('payment_gap','info','Payment outcome coverage unavailable','Android initiation events alone cannot establish payment outcomes.',
      'Validate outcome telemetry or correlate with the transaction backend.','overview');
    if(platform==='IOS') {
      const failures=get('payment_failure_events');
      if(count(failures)) {result.evaluated++; if(failures>0) add('payment_events','info','Payment failure events observed',fmt(failures)+' failure events in this window; not unique failed transactions.',
        'Investigate payment logs; do not derive a transaction failure rate from event counts.','overview');}
      else result.skipped++;
    }
  } else if(platform==='WEB') {
    const total=get('workflow_total_events'), init=get('workflow_initiation_failure_events'), action=get('workflow_failure_events');
    if(count(total)&&count(init)&&count(action)&&init+action<=total) {
      result.evaluated++;
      if(init+action>0) add('workflow_events','info','Workflow failure events observed',fmt(init)+' initiation failures and '+fmt(action)+' action failures; '+fmt(total)+' workflow events observed.',
        'Review workflow and backend logs. Event counts do not identify unique failed transactions.','overview');
    } else result.skipped++;
  }
  for(const key of ['quality_missing_start_key_events','quality_missing_payment_key_events','quality_unclassified_workflow_events']) {
    if(key.includes('payment')&&platform!=='IOS'||key.includes('workflow')&&platform!=='WEB') continue;
    const value=get(key);
    if(!count(value)){result.skipped++;continue;}
    result.evaluated++;
    if(value>0) add(key,'info','Telemetry correlation gap',fmt(value)+' '+(key.includes('start')?'session-start events lack identifiers':key.includes('payment')?'payment events lack correlation identifiers':'workflow events have unclassified results')+'.',
      'Review instrumentation and correlation coverage.','diagnostics');
  }
  const order:Record<Severity,number>={critical:0,warning:1,info:2};
  result.findings.sort((a,b)=>order[a.severity]-order[b.severity]||a.id.localeCompare(b.id));
  return result;
}
