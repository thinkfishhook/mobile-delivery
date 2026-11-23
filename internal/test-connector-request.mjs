import { test } from 'node:test';
import assert from 'node:assert/strict';
import { requestConnectorReview } from '../review/connector-request.mjs';
const before='a'.repeat(40),after='b'.repeat(40);
function fixture(){
 const refs=new Map(),pulls=[],comments=[],calls=[];
 const api=async(path,body,method)=>{
  calls.push({path,body,method});
  if(path===`git/commits/${after}`)return {sha:after,parents:[{sha:before}]};
  if(path.startsWith('git/ref/heads/')){const ref=path.slice('git/ref/'.length);if(!refs.has(ref)){const e=Error('404');e.status=404;throw e;}return {object:{sha:refs.get(ref)}};}
  if(path==='git/refs'){refs.set(body.ref.replace('refs/',''),body.sha);return {};}
  if(path.startsWith('pulls?'))return pulls;
  if(path==='pulls'){const pr={number:1,state:'open',html_url:'https://github.com/owner/repo/pull/1',body:body.body,head:{sha:after,ref:body.head},base:{sha:before,ref:body.base}};pulls.push(pr);return pr;}
  if(path.startsWith('issues/1/comments')&&!body)return comments.slice(0,path.includes('per_page=100')?100:30);
  if(path==='issues/1/comments'&&body){comments.push({body:body.body,user:{login:'github-actions[bot]'}});return {};}
  throw Error(`Unexpected ${path}`);
 };
 return {api,refs,pulls,comments,calls};
}
test('bot review uses disposable branches bound to the exact parent and commit',async()=>{
 const f=fixture();const result=await requestConnectorReview({api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1'});
 assert.equal(result.number,1);assert.equal(f.pulls.length,1);assert.equal(f.comments[0].body,'@codex review');
 assert.ok([...f.refs.keys()].every(k=>k.startsWith('heads/codex/review/')));
 assert.deepEqual([...f.refs.values()],[before,after]);assert.notEqual(f.pulls[0].base.ref,'trunk');assert.notEqual(f.pulls[0].base.ref,'main');
 assert.match(f.pulls[0].body,/review-only/i);assert.match(f.pulls[0].body,new RegExp(after));
});
test('identical requests reuse refs PR and trigger instead of creating more paid reviews',async()=>{
 const f=fixture();const input={api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1'};
 await requestConnectorReview(input);await requestConnectorReview(input);assert.equal(f.pulls.length,1);assert.equal(f.comments.length,1);assert.equal(f.refs.size,2);
});
test('a mismatched authoritative parent fails before any mutation',async()=>{
 const f=fixture();await assert.rejects(requestConnectorReview({api:f.api,repository:'owner/repo',before:'c'.repeat(40),after,runUrl:'https://github.com/owner/repo/actions/runs/1'}));assert.equal(f.refs.size,0);assert.equal(f.pulls.length,0);
});
test('a moved owned branch is preserved without a force update',async()=>{
 const f=fixture(),input={api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1'};
 const first=await requestConnectorReview(input);f.refs.set(`heads/${first.base}`,'c'.repeat(40));
 const count=f.calls.length;await assert.rejects(requestConnectorReview(input),/moved/);assert.ok(f.calls.slice(count).every(c=>!c.body));
});
test('deliberately closed review PRs remain closed and are not duplicated',async()=>{
 const f=fixture(),input={api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1'};
 await requestConnectorReview(input);f.pulls[0].state='closed';await assert.rejects(requestConnectorReview(input),/human closed/);assert.equal(f.pulls.length,1);assert.equal(f.comments.length,1);
});

test('an existing bot trigger beyond GitHub default pagination is not duplicated',async()=>{
 const f=fixture(),input={api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1'};
 await requestConnectorReview(input);const trigger=f.comments.pop();
 for(let i=0;i<80;i++)f.comments.push({body:'Human context '+i,user:{login:'human'}});
 f.comments.push(trigger);await requestConnectorReview(input);assert.equal(f.comments.length,81);
});
test('lost ref and PR create responses are read back without duplicate mutations',async()=>{
 const f=fixture(),lost=new Set();
 const api=async(path,body,method)=>{const result=await f.api(path,body,method);if(body&&['git/refs','pulls'].includes(path)&&!lost.has(path)){lost.add(path);throw Error('response lost');}return result;};
 await requestConnectorReview({api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1'});assert.equal(f.refs.size,2);assert.equal(f.pulls.length,1);assert.equal(f.comments.length,1);
});

test('automatic review is requested only by opening the bot PR, without a mention',async()=>{
 const f=fixture();const result=await requestConnectorReview({api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',trigger:'automatic'});
 assert.equal(f.pulls.length,1);assert.equal(f.comments.length,0);assert.equal(result.trigger,'automatic');assert.equal(result.complete,false);assert.ok(f.calls.every(c=>!c.path.startsWith('issues/')));
});
test('automatic reruns reuse the PR without adding a manual trigger',async()=>{
 const f=fixture();const input={api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',trigger:'automatic'};
 await requestConnectorReview(input);await requestConnectorReview(input);assert.equal(f.pulls.length,1);assert.equal(f.refs.size,2);assert.equal(f.comments.length,0);
});
test('unknown review trigger modes fail before any GitHub mutation',async()=>{
 const f=fixture();await assert.rejects(requestConnectorReview({api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',trigger:'unknown'}),/trigger/);assert.equal(f.refs.size,0);assert.equal(f.pulls.length,0);
});

test('combined landed ranges require authoritative ancestor proof before opening one exact review',async()=>{
 const f=fixture(),parent='c'.repeat(40);
 const api=async(path,body,method)=>{
  if(path===`git/commits/${after}`)return {sha:after,parents:[{sha:parent}]};
  if(path===`compare/${before}...${after}`)return {status:'ahead',ahead_by:3,behind_by:0,base_commit:{sha:before},merge_base_commit:{sha:before}};
  return f.api(path,body,method);
 };
 const result=await requestConnectorReview({api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',trigger:'automatic',view:'landed-range'});
 assert.equal(result.view,'landed-range');assert.deepEqual([...f.refs.values()],[before,after]);assert.equal(f.pulls.length,1);assert.equal(f.comments.length,0);
 assert.match(f.pulls[0].body,/combined landed range/i);assert.doesNotMatch(f.pulls[0].body,/First parent:/);
});
test('diverged, contradictory and missing combined-range ancestry fail before writes',async()=>{
 for(const change of [{status:'diverged'},{ahead_by:0},{behind_by:1},{base_commit:{sha:'c'.repeat(40)}},{merge_base_commit:{sha:'c'.repeat(40)}}]){
  const f=fixture();const api=async(path,body,method)=>path.startsWith('compare/')?{status:'ahead',ahead_by:3,behind_by:0,base_commit:{sha:before},merge_base_commit:{sha:before},...change}:f.api(path,body,method);
  await assert.rejects(requestConnectorReview({api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',view:'landed-range'}),/ancestor|range/);
  assert.equal(f.refs.size,0);assert.equal(f.pulls.length,0);
 }
 const f=fixture();await assert.rejects(requestConnectorReview({api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',view:'landed-range'}));assert.equal(f.refs.size,0);
});
test('unknown source-view modes fail before any GitHub read or mutation',async()=>{
 const f=fixture();await assert.rejects(requestConnectorReview({api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',view:'latest'}),/view/);assert.equal(f.calls.length,0);
});

const linkedUser={id:328449,login:'evandelaney',type:'User'};
function linkedFixture(){
 const f=fixture(),linkedCalls=[];
 const requester={user:linkedUser,api:async(path,body)=>{
  linkedCalls.push({path,body});assert.equal(path,'issues/1/comments');
  const comment={id:55,body:body.body,user:linkedUser};f.comments.push(comment);return comment;
 }};
 return {...f,requester,linkedCalls,input:{api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',trigger:'linked-mention',requester}};
}
test('linked requester credential is used only for the review comment, preserving bot PR ownership',async()=>{
 const f=linkedFixture();const result=await requestConnectorReview(f.input);
 assert.equal(f.pulls.length,1);assert.equal(f.comments[0].user.id,linkedUser.id);assert.equal(f.linkedCalls.length,1);
 assert.ok(f.calls.filter(c=>c.path.startsWith('issues/')).every(c=>!c.body));
 assert.deepEqual(result.requestedBy,{id:linkedUser.id,login:linkedUser.login});assert.equal(result.complete,false);
 await requestConnectorReview(f.input);assert.equal(f.linkedCalls.length,1);
});
test('lost linked comment response is reconciled without another request',async()=>{
 const f=linkedFixture(),create=f.requester.api;
 f.requester.api=async(...args)=>{await create(...args);throw Error('response lost');};
 await requestConnectorReview(f.input);await requestConnectorReview(f.input);assert.equal(f.linkedCalls.length,1);assert.equal(f.comments.length,1);
});
test('missing or nonhuman linked requester identities fail before main GitHub reads or writes',async()=>{
 for(const requester of [undefined,{}, {api:async()=>{},user:{...linkedUser,id:0}}, {api:async()=>{},user:{...linkedUser,type:'Bot'}},{api:async()=>{},user:{...linkedUser,login:'bad\nactor'}},{api:async()=>{},user:{...linkedUser,login:undefined}},{api:async()=>{},user:{...linkedUser,login:123}}]){
  const f=fixture();await assert.rejects(requestConnectorReview({api:f.api,repository:'owner/repo',before,after,runUrl:'https://github.com/owner/repo/actions/runs/1',trigger:'linked-mention',requester}),/linked|requester/i);assert.equal(f.calls.length,0);
 }
});
test('an unexpected linked comment author cannot be claimed as a valid request',async()=>{
 const f=linkedFixture();f.requester.api=async(path,body)=>({id:55,body:body.body,user:{...linkedUser,id:999}});
 await assert.rejects(requestConnectorReview(f.input),/identity|requester/i);
});
test('linked credential resolves the authenticated human without leaking it into repository data',async()=>{
 const {linkedConnectorRequester}=await import('../review/connector-requester.mjs');const calls=[];
 const fetchImpl=async(url,options)=>{calls.push({url,options});return {ok:true,json:async()=>({...linkedUser,email:'private@example.test'})};};
 const requester=await linkedConnectorRequester({repository:'owner/repo',token:'fixture-token',fetchImpl});
 assert.deepEqual(requester.user,linkedUser);assert.equal(typeof requester.api,'function');assert.equal(calls.length,1);assert.equal(calls[0].url,'https://api.github.com/user');assert.equal(calls[0].options.redirect,'error');assert.equal(calls[0].options.headers.authorization,'Bearer fixture-token');
});
test('linked credential lookup fails closed for missing credentials, errors and bot identities',async()=>{
 const {linkedConnectorRequester}=await import('../review/connector-requester.mjs');let reads=0;
 const fetchImpl=async()=>{reads++;return {ok:true,json:async()=>linkedUser};};
 await assert.rejects(linkedConnectorRequester({repository:'owner/repo',token:'',fetchImpl}),/credential|token/i);assert.equal(reads,0);
 await assert.rejects(linkedConnectorRequester({repository:'owner/repo',token:'fixture-token',fetchImpl:async()=>({ok:false,status:401})}),/401/);
 await assert.rejects(linkedConnectorRequester({repository:'owner/repo',token:'fixture-token',fetchImpl:async()=>({ok:true,json:async()=>({...linkedUser,type:'Bot'})})}),/human|user/i);
});

test('missing or nonstring linked logins are not coerced into valid identities',async()=>{
 const {linkedConnectorRequester}=await import('../review/connector-requester.mjs');
 for(const login of [undefined,123])await assert.rejects(linkedConnectorRequester({repository:'owner/repo',token:'fixture-token',fetchImpl:async()=>({ok:true,json:async()=>({...linkedUser,login})})}),/human|user/i);
});
