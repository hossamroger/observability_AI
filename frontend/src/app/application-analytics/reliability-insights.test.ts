import { MetricObservation } from '../metric.model';
import { DEFAULT_THRESHOLDS, evaluateInsights, InsightInput, validThresholds } from './reliability-insights';

const now=Date.parse('2026-11-25T00:12:00Z');
const start='2026-11-24T23:40:00Z',end='2026-11-25T00:10:00Z';
function metric(key:string,value:number,scope='GA4_INTRADAY',windowEnd=end):MetricObservation {
  return {id:scope+key,sourceSystem:'BIGQUERY',platform:'IOS',serviceName:scope,metricName:key,metricValue:value,
    metricUnit:key.endsWith('_ms')?'ms':'sessions',windowStartUtc:start,windowEndUtc:windowEnd,collectedAtUtc:'2026-11-25T00:11:00Z',demo:false};
}
const defaults={...DEFAULT_THRESHOLDS};
const base:InsightInput={platform:'IOS',metrics:[],sourceState:'Recently collected',historical:false,nowMs:now,thresholds:defaults};
function assert(condition:unknown,message:string):void { if(!condition) throw new Error(message); }
const inputs=[
  metric('session_start_eligible_sessions',451),metric('session_to_first_screen_samples',423),
  metric('session_to_first_screen_p95_ms',3710),metric('session_to_first_screen_over_10s',5),
  metric('session_to_home_samples',379),metric('session_to_home_p95_ms',49640),metric('session_to_home_over_10s',96),
  metric('session_to_home_samples',106,'APP_VERSION:2.20.2'),metric('session_to_home_p95_ms',21590,'APP_VERSION:2.20.2'),
  metric('session_to_home_samples',115,'APP_VERSION:2.20.3'),metric('session_to_home_p95_ms',54870,'APP_VERSION:2.20.3'),
  metric('payment_failure_events',2),metric('quality_missing_start_key_events',0),metric('quality_missing_payment_key_events',0)
];
const result=evaluateInsights({...base,metrics:inputs});
assert(result.findings.some(f=>f.id==='session_to_home_coverage'&&f.evidence.includes('72 of 451')&&f.severity==='critical'),'coverage count');
assert(result.findings.some(f=>f.id==='session_to_home_latency'&&f.evidence.includes('96 of 379')&&f.severity==='critical'),'matched slow share');
assert(result.findings.some(f=>f.id==='version_gap'&&f.evidence.includes('2.20.3')&&f.evidence.includes('2.20.2')),'version comparison');
assert(result.findings.some(f=>f.id==='payment_events'&&f.severity==='info'),'payment event classification');
assert(!result.findings.some(f=>f.evidence.includes('success rate')),'no unsupported payment rate');
assert(evaluateInsights({...base,metrics:inputs,sourceState:'Collection failed'}).blocked,'failed source blocked');
assert(evaluateInsights({...base,metrics:inputs,nowMs:now+3600000,sourceState:'Partial collection'}).blocked,'stale partial source blocked');
assert(!evaluateInsights({...base,metrics:inputs,nowMs:now+3600000,historical:true,sourceState:'On-demand report'}).blocked,'historical assessment');
assert(evaluateInsights({...base,metrics:[]}).blocked,'missing source');
assert(!evaluateInsights({...base,metrics:inputs.map(m=>m.metricName==='session_to_home_samples'&&m.serviceName==='APP_VERSION:2.20.3'?{...m,metricValue:3}:m)}).findings.some(f=>f.id==='version_gap'),'low sample suppression');
assert(!evaluateInsights({...base,metrics:inputs.map(m=>m.serviceName==='APP_VERSION:2.20.3'?{...m,windowEndUtc:'2026-11-25T00:05:00Z'}:m)}).findings.some(f=>f.id==='version_gap'),'cross-window suppression');
assert(!evaluateInsights({...base,metrics:inputs.filter(m=>m.metricName!=='session_to_home_samples'||m.serviceName!=='GA4_INTRADAY')}).findings.some(f=>f.id==='session_to_home_coverage'||f.id==='session_to_home_latency'),'missing match count not treated as zero');
assert(!validThresholds({...defaults,homeCoverageCritical:99}),'invalid threshold order');
assert(!validThresholds({...defaults,minSamples:1}),'minimum cohort guard');
assert(validThresholds(defaults),'default thresholds');
console.log('Reliability insights checks passed.');
