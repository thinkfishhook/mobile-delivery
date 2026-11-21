// The native Connector format is observed, versioned metadata, not a public API guarantee.
// Unknown or contradictory formats are incomplete until explicitly supported.
const oid=value=>typeof value==='string'&&/^[a-f0-9]{40}$/.test(value);
const check=(condition,message)=>{if(!condition)throw Error(message);};
const trusted=value=>value?.user?.id===199175422&&value.user.login==='chatgpt-codex-connector[bot]'&&value.user.type==='Bot'&&(!value.performed_via_github_app||value.performed_via_github_app.id===1144995);
async function inventory(api,path){
 const result=[];
 for(let page=1;page<=20;page++){
  const rows=await api(`${path}?per_page=100&page=${page}`);
  check(Array.isArray(rows),'Native review inventory is incomplete');result.push(...rows);
  if(rows.length<100)return result;
 }
 throw Error('Native review inventory exceeds bound; no complete review claimed');
}
function nativeState(comments,after){
 const summaries=comments.filter(c=>trusted(c)&&c.body?.startsWith('<!-- codex-pull-request-review-summary -->'));
 check(summaries.length>0,'Native review incomplete: no trusted summary');
 check(summaries.length===1,'Native review summary is ambiguous');
 const matches=[...summaries[0].body.matchAll(/<!-- codex-review-state:v1 ([\s\S]*?) -->/g)];
 check(matches.length===1,'Native review metadata is missing or ambiguous');
 let state;try{state=JSON.parse(matches[0][1]).code_review;}catch{throw Error('Native review metadata is malformed/incomplete');}
 check(state?.commit_sha===after,'Native review does not cover the exact requested source');
 check(state.status==='completed'&&typeof state.has_any_findings==='boolean'&&typeof state.has_unresolved_findings==='boolean','Native review is incomplete, not completed');
 check(state.has_any_findings||!state.has_unresolved_findings,'Native review metadata is contradictory/incomplete');
 return state;
}
export async function connectorEvidence({api,number,before,after,base,head}){
 check(Number.isSafeInteger(number)&&number>0&&oid(before)&&oid(after)&&before!==after&&base?.startsWith('codex/review/')&&head?.startsWith('codex/review/')&&base!==head,'Expected exact immutable review-only source identities');
 const path=`pulls/${number}`;
 const verify=pr=>check(pr.number===number&&pr.state==='open'&&pr.head?.sha===after&&pr.base?.sha===before&&pr.head?.ref===head&&pr.base?.ref===base,'Review-only PR source moved or was closed; preserve the human decision');
 verify(await api(path));
 const state=nativeState(await inventory(api,`issues/${number}/comments`),after);
 const reviews=await inventory(api,`${path}/reviews`),comments=await inventory(api,`${path}/comments`);
 const receipts=new Set(reviews.filter(r=>trusted(r)&&r.commit_id===after&&r.state==='COMMENTED'&&r.body?.includes('<!-- codex-review-receipt:v1 -->')).map(r=>r.id));
 const findings=[];
 for(const comment of comments.filter(c=>trusted(c)&&c.commit_id===after)){
  check(comment.body?.includes('<!-- codex-code-review-finding:v1 -->'),'Unrecognized exact-head Connector comment; native review incomplete');
  check(receipts.has(comment.pull_request_review_id),'Native finding has no exact-head review receipt');
  const match=comment.body.match(/^\*\*<sub><sub>!\[(P[0-3]) Badge\]\(https:\/\/img\.shields\.io\/badge\/\1-[^\s)]+\)<\/sub><\/sub>\s+([^\n]+?)\*\*\n\n([\s\S]+)/);
  check(match&&comment.body.length<=10000,'Unknown native priority/finding format; review incomplete');
  check(comment.path&&comment.path.length<=500&&!comment.path.startsWith('/')&&!comment.path.includes('\\')&&!/[\x00-\x1f]/.test(comment.path)&&!comment.path.split('/').some(p=>['','..','.'].includes(p)),'Unsafe native finding path');
  check(Number.isSafeInteger(comment.line)&&comment.line>0&&['LEFT','RIGHT'].includes(comment.side),'Native finding is unanchored or has an unsupported source side');
  check(Number.isSafeInteger(comment.id)&&comment.id>0&&match[2].trim().length<=180,'Invalid native finding identity/title');
  findings.push({id:comment.id,reviewId:comment.pull_request_review_id,priority:match[1],title:match[2].trim(),body:comment.body,path:comment.path,line:comment.line,sourceSha:comment.side==='LEFT'?before:after,side:comment.side});
 }
 check(new Set(findings.map(f=>f.id)).size===findings.length,'Duplicate native finding identities');
 check(state.has_any_findings===(findings.length>0),'Native summary and recorded findings conflict; review incomplete');
 check(findings.length<=50,'Native findings exceed publication bound; review incomplete');
 verify(await api(path));
 check(JSON.stringify(nativeState(await inventory(api,`issues/${number}/comments`),after))===JSON.stringify(state),'Native review state changed while collecting evidence; retry');
 return {complete:true,model:'connector-managed',sourceSha:after,findings};
}
