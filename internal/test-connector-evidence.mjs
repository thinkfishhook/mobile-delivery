import { test } from 'node:test';
import assert from 'node:assert/strict';
import { connectorEvidence } from '../review/connector-evidence.mjs';
const before='a'.repeat(40),after='b'.repeat(40);
const user={id:199175422,login:'chatgpt-codex-connector[bot]',type:'Bot'};
const state=(changes={})=>({commit_sha:after,status:'completed',has_any_findings:false,has_unresolved_findings:false,...changes});
const summary=value=>({id:1,user,body:`<!-- codex-pull-request-review-summary -->\n<!-- codex-review-state:v1 ${JSON.stringify({code_review:value})} -->`});
function fixture(){
 const f={comments:[summary(state())],reviews:[],findings:[],calls:[],pr:{number:3,state:'open',head:{sha:after,ref:'codex/review/key/head'},base:{sha:before,ref:'codex/review/key/base'}}};
 f.api=async path=>{f.calls.push(path);if(path==='pulls/3')return f.pr;if(path.startsWith('issues/3/comments?'))return f.comments;if(path.startsWith('pulls/3/reviews?'))return f.reviews;if(path.startsWith('pulls/3/comments?'))return f.findings;throw Error(path);};
 f.input={api:f.api,number:3,before,after,base:f.pr.base.ref,head:f.pr.head.ref};return f;
}
const finding=(priority='P2',side='RIGHT')=>({id:22,user,commit_id:after,original_commit_id:after,pull_request_review_id:11,path:'src/store.kt',line:5,side,html_url:'https://github.com/owner/repo/pull/3#discussion_r22',body:`**<sub><sub>![${priority} Badge](https://img.shields.io/badge/${priority}-yellow?style=flat)</sub></sub>  Preserve saved records**\n\nOld readers lose saved records when this path overwrites them. Keep a compatible reader.\n\nUseful? React with 👍 / 👎.\n\n<!-- codex-code-review-finding:v1 -->`});
test('only exact completed trusted native state establishes a clean review',async()=>{
 const f=fixture(),r=await connectorEvidence(f.input);assert.equal(r.complete,true);assert.deepEqual(r.findings,[]);assert.equal(r.model,'connector-managed');
 for(const changes of [{status:'running'},{commit_sha:before},{has_any_findings:null},{has_unresolved_findings:true}]){f.comments=[summary(state(changes))];await assert.rejects(connectorEvidence(f.input),/incomplete|exact|completed/);}
 f.comments=[];await assert.rejects(connectorEvidence(f.input),/incomplete/);
});
test('human copied markers and lookalike bot identities cannot assert completion',async()=>{
 for(const changed of [{id:7},{type:'User'},{login:'other[bot]'}]){const f=fixture();f.comments[0].user={...user,...changed};await assert.rejects(connectorEvidence(f.input),/incomplete/);}
 const f=fixture();f.comments[0].performed_via_github_app={id:7};await assert.rejects(connectorEvidence(f.input),/incomplete/);
});
test('native P0 through P3 retain exact text priority review and source side',async()=>{
 const f=fixture();f.comments=[summary(state({has_any_findings:true,has_unresolved_findings:true}))];f.reviews=[{id:11,user,commit_id:after,state:'COMMENTED',body:'<!-- codex-review-receipt:v1 -->'}];
 f.findings=['P0','P1','P2','P3'].map((p,i)=>({...finding(p,i===0?'LEFT':'RIGHT'),id:22+i}));
 const r=await connectorEvidence(f.input);assert.deepEqual(r.findings.map(x=>x.priority),['P0','P1','P2','P3']);assert.equal(r.findings[0].sourceSha,before);assert.equal(r.findings[1].sourceSha,after);assert.match(r.findings[0].body,/Old readers/);assert.equal(r.findings[0].reviewId,11);
});
test('missing findings unanchored comments conflicting state or unknown native formats stay incomplete',async()=>{
 const f=fixture();f.comments=[summary(state({has_any_findings:true}))];await assert.rejects(connectorEvidence(f.input),/findings|incomplete/);
 f.reviews=[{id:11,user,commit_id:after,state:'COMMENTED',body:'<!-- codex-review-receipt:v1 -->'}];
 for(const changed of [{line:null},{side:'UNKNOWN'},{path:'../secret'},{pull_request_review_id:9},{body:finding().body.replaceAll('P2','P4')}]){f.findings=[{...finding(),...changed}];await assert.rejects(connectorEvidence(f.input));}
 f.findings=[finding()];f.comments=[summary(state())];await assert.rejects(connectorEvidence(f.input),/conflict/);
 f.comments=[summary(state()),summary(state())];f.findings=[];await assert.rejects(connectorEvidence(f.input),/ambiguous/);
});
test('source movement malformed state and incomplete inventories never become clean',async()=>{
 const f=fixture();f.pr.head.sha=before;await assert.rejects(connectorEvidence(f.input),/source/);f.pr.head.sha=after;
 f.comments[0].body+='\n<!-- codex-review-state:v1 {} -->';await assert.rejects(connectorEvidence(f.input),/ambiguous/);
 f.comments=Array.from({length:100},()=>({body:'human',user:{id:1}}));await assert.rejects(connectorEvidence(f.input),/bound|inventory/);
});
test('a native state change while reading evidence must be retried',async()=>{
 const f=fixture();let reads=0;const api=async path=>{if(path.startsWith('issues/3/comments?')&&++reads===2)return [summary(state({status:'running'}))];return f.api(path);};await assert.rejects(connectorEvidence({...f.input,api}),/changed|incomplete/);
});
