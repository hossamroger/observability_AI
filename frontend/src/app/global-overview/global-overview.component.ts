import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, OnChanges, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { DashboardService } from '../dashboard.service';
import { ApplicationRange, DashboardOverview, MetricObservation, Platform } from '../metric.model';
import { latestPlatformMetrics } from '../latest-platform-metrics';
import { DEFAULT_THRESHOLDS, evaluateInsights, Finding, InsightResult, InsightThresholds, validThresholds } from '../application-analytics/reliability-insights';

type AppPlatform = 'WEB'|'ANDROID'|'IOS';
export interface GlobalServiceRow {
  id:string; name:string; health:string; requests?:number; failed?:number; failure?:number; p95?:number;
}
interface PlatformSummary {
  platform:AppPlatform; name:string; status:string; tone:string; assessment:InsightResult;
  users?:number; sessions?:number; coverage?:number; homeP95?:number; workflowFailures?:number;
  paymentInitiated?:number; purchases?:number; paymentFailures?:number;
  ga4Window?:MetricObservation; bigQueryWindow?:MetricObservation;
}
interface TrendPoint {x:number;y:number;value:number;time:string;}
interface TrendSeries {platform:AppPlatform;name:string;color:string;points:TrendPoint[];segments:{a:TrendPoint;b:TrendPoint}[];}
const APPS:AppPlatform[]=['WEB','ANDROID','IOS'];
const NAMES:Record<AppPlatform,string>={WEB:'Web',ANDROID:'Android',IOS:'iOS'};
const COLORS:Record<AppPlatform,string>={WEB:'#4389e0',ANDROID:'#18a584',IOS:'#a478e0'};
const finite=(v:number|undefined):v is number=>v!=null&&Number.isFinite(v)&&v>=0;
const fresh=(time:string|undefined,maxAge:number,now=Date.now()):boolean=>{
  const age=now-Date.parse(time??'');
  return Number.isFinite(age)&&age>=-60000&&age<=maxAge;
};

@Component({
  selector:'app-global-overview',standalone:true,imports:[CommonModule],
  templateUrl:'./global-overview.component.html',styleUrls:['./global-overview.component.css']
})
export class GlobalOverviewComponent implements OnChanges,OnDestroy {
  @Input() overview:DashboardOverview|null=null;
  @Input() overviewError='';
  @Input() services:GlobalServiceRow[]=[];
  @Input() serviceStart?:string;
  @Input() serviceEnd?:string;
  @Input() serviceLoading=false;
  @Input() serviceError='';
  @Output() platformSelected=new EventEmitter<Platform>();
  @Output() serviceSelected=new EventEmitter<string>();
  readonly apps=APPS;
  readonly names=NAMES;
  readonly colors=COLORS;
  summaries:PlatformSummary[]=[];
  findings:(Finding&{platform:AppPlatform;platformName:string})[]=[];
  sources:{name:string;key:string;label:string;tone:string;time?:string}[]=[];
  topServices:GlobalServiceRow[]=[];
  backendAvailable=false;
  backendStatus='Awaiting observations';
  backendTone='neutral';
  criticalServices=0;
  warningServices=0;
  unknownServices=0;
  errorServices=0;
  severityFilter='all';
  showAllFindings=false;
  trendPeriod=60;
  trendLoading=false;
  trendError='';
  trendReport?:ApplicationRange;
  trendSeries:TrendSeries[]=[];
  trendMax=1;
  trendTicks:number[]=[];
  enabledPlatforms:Record<AppPlatform,boolean>={WEB:true,ANDROID:true,IOS:true};
  private trendSubscription?:Subscription;

  constructor(private dashboard:DashboardService){}
  ngOnChanges():void { this.rebuild(); }
  ngOnDestroy():void { this.trendSubscription?.unsubscribe(); }

  private thresholds():InsightThresholds {
    try {
      const candidate={...DEFAULT_THRESHOLDS,...JSON.parse(localStorage.getItem('observability.insightThresholds.v1')||'{}')};
      if(validThresholds(candidate))return candidate;
    }catch{ /* Browser storage may be unavailable. */ }
    return {...DEFAULT_THRESHOLDS};
  }
  private sourceState(key:string,metric?:MetricObservation):string {
    if(this.overviewError)return 'Overview unavailable';
    const outcome=this.overview?.sourceOutcomes?.find(o=>o.sourceSystem===key);
    if(outcome?.status==='FAILED')return 'Collection failed';
    if(outcome?.status==='EMPTY')return 'No observations returned';
    if(outcome?.status==='PARTIAL')return 'Partial collection';
    if(!metric)return 'No data';
    return fresh(metric.collectedAtUtc,key==='BIGQUERY'?600000:120000)?'Recently collected':'Stale';
  }
  private tone(state:string):string {
    return state==='Collection failed'||state==='Overview unavailable'?'critical':state==='Recently collected'?'good':state==='No data'?'neutral':'warning';
  }
  private rebuild():void {
    const data=this.overview?.metrics??[], now=Date.now(), thresholds=this.thresholds();
    this.summaries=APPS.map(platform=>{
      const metrics=latestPlatformMetrics(data,platform);
      const ga4Window=metrics.find(m=>m.sourceSystem==='GA4'&&m.metricName==='active_users_last_30m');
      const bigQueryWindow=metrics.find(m=>m.sourceSystem==='BIGQUERY'&&m.serviceName==='GA4_INTRADAY');
      const state=this.sourceState('BIGQUERY',bigQueryWindow);
      const assessment=evaluateInsights({platform,metrics,sourceState:state,historical:false,nowMs:now,thresholds});
      const hasCritical=assessment.findings.some(f=>f.severity==='critical');
      const hasWarning=assessment.findings.some(f=>f.severity==='warning');
      const status=assessment.blocked?'Unavailable':hasCritical?'Critical findings':hasWarning?'Warning findings':
        state==='Partial collection'?'Partial coverage':assessment.skipped?'Limited assessment':assessment.findings.length?'Informational findings':'No rule breaches';
      const tone=assessment.blocked?'neutral':hasCritical?'critical':hasWarning?'warning':'neutral';
      // Never present stale or failed source values as live KPIs.
      const allowBQ=!!bigQueryWindow && fresh(bigQueryWindow.collectedAtUtc,600000,now)&&['Recently collected','Partial collection'].includes(state);
      const bq=(key:string)=>{const v=metrics.find(m=>m.sourceSystem==='BIGQUERY'&&m.serviceName==='GA4_INTRADAY'&&m.metricName===key)?.metricValue;return allowBQ&&finite(v)?v:undefined;};
      const eligible=bq('session_start_eligible_sessions'), matched=bq('session_to_home_samples'), p95=bq('session_to_home_p95_ms');
      const initFail=bq('workflow_initiation_failure_events'),actionFail=bq('workflow_failure_events');
      const users=ga4Window?.metricValue;
      return {platform,name:NAMES[platform],status,tone,assessment,
        users:ga4Window&&fresh(ga4Window.collectedAtUtc,120000,now)&&['Recently collected','Partial collection'].includes(this.sourceState('GA4',ga4Window))&&finite(users)?users:undefined,
        sessions:bq('observed_sessions'),coverage:finite(eligible)&&eligible>0&&finite(matched)&&matched<=eligible?100*matched/eligible:undefined,
        homeP95:finite(p95)?p95/1000:undefined,workflowFailures:finite(initFail)&&finite(actionFail)?initFail+actionFail:undefined,
        paymentInitiated:bq('payment_initiated_events'),purchases:bq('purchase_events'),paymentFailures:bq('payment_failure_events'),ga4Window,bigQueryWindow};
    });
    const rank={critical:0,warning:1,info:2};
    this.findings=this.summaries.flatMap(p=>p.assessment.findings.map(f=>({...f,platform:p.platform,platformName:p.name})))
      .sort((a,b)=>rank[a.severity]-rank[b.severity]||a.platform.localeCompare(b.platform)||a.id.localeCompare(b.id));
    this.sources=[{key:'GA4',name:'GA4 Realtime'},{key:'BIGQUERY',name:'BigQuery'},{key:'DYNATRACE',name:'Dynatrace'},{key:'ELK',name:'ELK'}].map(source=>{
      const metrics=data.filter(m=>m.sourceSystem===source.key).sort((a,b)=>Date.parse(b.collectedAtUtc)-Date.parse(a.collectedAtUtc));
      const outcome=this.overview?.sourceOutcomes?.find(o=>o.sourceSystem===source.key);
      let label=this.sourceState(source.key,metrics[0]);
      // Scheduled Dynatrace metrics are deliberately omitted from the overview payload.
      if(source.key==='DYNATRACE'&&!this.overviewError&&outcome?.status==='SUCCESS')
        label=fresh(outcome.executedAtUtc,120000)?'Recently collected':'Stale';
      return {...source,label,tone:this.tone(label),time:outcome?.executedAtUtc??metrics[0]?.collectedAtUtc};
    });
    this.backendAvailable=!this.serviceError&&!!this.serviceStart&&fresh(this.serviceEnd,300000,now)&&this.services.length>0;
    this.criticalServices=this.services.filter(s=>s.health==='CRITICAL').length;
    this.warningServices=this.services.filter(s=>s.health==='WARNING').length;
    this.unknownServices=this.services.filter(s=>s.health==='UNKNOWN'||s.health==='LOW TRAFFIC').length;
    this.errorServices=this.services.filter(s=>finite(s.failure)&&s.failure>0).length;
    this.backendStatus=this.serviceError?'Window unavailable':!this.backendAvailable?'No fresh observations':this.criticalServices?'Critical services':this.warningServices?'Warning services':this.unknownServices?'Limited assessment':'No rule breaches';
    this.backendTone=!this.backendAvailable?'neutral':this.criticalServices?'critical':this.warningServices?'warning':'neutral';
    this.topServices=this.backendAvailable?this.services.filter(s=>finite(s.failed)&&s.failed>0)
      .sort((a,b)=>(b.failed??0)-(a.failed??0)||a.id.localeCompare(b.id)).slice(0,5):[];
  }
  findingCount(severity:string):number {return this.findings.filter(f=>f.severity===severity).length;}
  get filteredFindings(){return this.findings.filter(f=>this.severityFilter==='all'||f.severity===this.severityFilter);}
  get visibleFindings(){return this.showAllFindings?this.filteredFindings:this.filteredFindings.slice(0,5);}
  trackFinding(index:number,f:Finding&{platform:AppPlatform}):string{return f.platform+'|'+f.id;}
  trackPlatform(index:number,p:PlatformSummary):string{return p.platform;}
  setSeverity(value:string):void{this.severityFilter=value;this.showAllFindings=false;}
  selectPeriod(value:string):void {
    this.trendPeriod=Number(value);
    this.trendReport=undefined;this.trendSeries=[];this.trendError='';
  }
  togglePlatform(platform:AppPlatform):void {
    this.enabledPlatforms={...this.enabledPlatforms,[platform]:!this.enabledPlatforms[platform]};
    this.buildTrend();
  }
  loadTrend():void {
    if(this.trendLoading)return;
    this.trendError='';this.trendLoading=true;
    this.trendSubscription=this.dashboard.loadApplicationSourceHistory(this.trendPeriod).subscribe({
      next:report=>{this.trendReport=report;this.trendLoading=false;this.buildTrend();},
      error:err=>{this.trendLoading=false;this.trendError=err.status===429?'Reporting cooldown: wait a minute and retry.':err.status===503?'On-demand history is disabled.':'History unavailable. Check export availability and query byte limit.';}
    });
  }
  private buildTrend():void {
    const report=this.trendReport;
    if(!report){this.trendSeries=[];return;}
    const start=Date.parse(report.windowStartUtc),end=Date.parse(report.windowEndUtc),bucket=(report.bucketSeconds??0)*1000;
    if(!Number.isFinite(start)||!Number.isFinite(end)||end<=start||bucket<=0){this.trendSeries=[];return;}
    const rows=report.metrics.filter(m=>m.sourceSystem==='BIGQUERY'&&m.serviceName==='GA4_HISTORY'&&m.metricName==='observed_sessions'&&APPS.includes(m.platform as AppPlatform)&&this.enabledPlatforms[m.platform as AppPlatform]&&finite(m.metricValue)&&Date.parse(m.windowEndUtc)>start&&Date.parse(m.windowEndUtc)<=end);
    this.trendMax=Math.max(1,...rows.map(m=>m.metricValue));
    this.trendTicks=[0,1,2,3,4].map(i=>start+(end-start)*i/4);
    this.trendSeries=APPS.filter(p=>this.enabledPlatforms[p]).map(platform=>{
      const points=rows.filter(m=>m.platform===platform).sort((a,b)=>Date.parse(a.windowEndUtc)-Date.parse(b.windowEndUtc))
        .map(m=>({x:48+560*(Date.parse(m.windowEndUtc)-start)/(end-start),y:166-140*m.metricValue/this.trendMax,value:m.metricValue,time:m.windowEndUtc}));
      const segments=points.slice(1).flatMap((b,i)=>Date.parse(b.time)-Date.parse(points[i].time)===bucket?[{a:points[i],b}]:[]);
      return {platform,name:NAMES[platform],color:COLORS[platform],points,segments};
    });
  }
  get trendHasData():boolean{return this.trendSeries.some(s=>s.points.length>0);}
  get trendGrid(){return [0,1,2,3,4].map(i=>({y:166-140*i/4,value:this.trendMax*i/4}));}
}
