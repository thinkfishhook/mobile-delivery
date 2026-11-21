import {publishFindings} from './hosted.mjs';
const check=(condition,message)=>{if(!condition)throw Error(message);};
const oid=value=>typeof value==='string'&&/^[a-f0-9]{40}$/.test(value);
export async function publishNativeFindings({evidence,view,number,readLine,...options}){
 check(evidence?.complete===true&&evidence.model==='connector-managed'&&evidence.sourceSha===view?.after&&oid(view.before)&&oid(view.after),'Native review is incomplete or differs from the exact requested view');
 check(Number.isSafeInteger(number)&&number>0&&/^[\w.-]+\/[\w.-]+$/.test(options.repository),'Invalid native PR provenance');
 check(Array.isArray(evidence.findings)&&evidence.findings.length<=50,'Native finding inventory is incomplete');
 const locations=new Map(),findings=[];
 for(const native of evidence.findings){
  check(['P0','P1','P2','P3'].includes(native.priority)&&Number.isSafeInteger(native.id)&&native.id>0&&Number.isSafeInteger(native.reviewId)&&native.reviewId>0,'Invalid native finding priority/identity');
  check(['LEFT','RIGHT'].includes(native.side)&&native.sourceSha===(native.side==='LEFT'?view.before:view.after),'Native finding source differs from its exact review side');
  check(typeof native.path==='string'&&native.path.length<=500&&!native.path.startsWith('/')&&!native.path.includes('\\')&&!/[\x00-\x1f]/.test(native.path)&&!native.path.split('/').some(p=>['','..','.'].includes(p))&&Number.isSafeInteger(native.line)&&native.line>0,'Unsafe native committed location');
  check(typeof native.body==='string'&&native.body.includes('<!-- codex-code-review-finding:v1 -->'),'Unknown native finding evidence');
  const fullLine=await readLine(native);
  check(typeof fullLine==='string'&&fullLine.trim().length>0,'Native finding has no committed source anchor');
  const text=native.body.replace(/^\*\*<sub><sub>!\[P[0-3] Badge\][^\n]+\n\n/,'').replace(/\n*<!-- codex-code-review-finding:v1 -->\s*$/,'').replace(/\n\nUseful\? React with[^\n]*\s*$/,'').trim();
  const finding={priority:native.priority,title:native.title,path:native.path,line:native.line,anchor:fullLine.trim().slice(0,300),evidence:`Native review: https://github.com/${options.repository}/pull/${number}#discussion_r${native.id}\n\n${text}`,impact:'Reported in the native evidence.',correction:'Reported in the native evidence.',risk:{blocksPush:false,categories:[],affectedSurface:native.path,reversibility:'unknown',mitigation:'Not separately classified by the native Connector.',reason:'Native hosted findings never gate delivery.'}};
  locations.set(finding,{sha:native.sourceSha,path:native.path,line:native.line,fullLine});findings.push(finding);
 }
 return await publishFindings({...options,provider:'connector',model:'connector-managed',review:{schema:1,complete:true,summary:'Completed exact native Connector review.',findings},source:async finding=>locations.get(finding)});
}
