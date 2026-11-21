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
  if(path==='issues/1/comments'&&!body)return comments;
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
