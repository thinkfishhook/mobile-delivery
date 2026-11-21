import {git,gitContext} from './ranges.mjs';
const check=(condition,message)=>{if(!condition)throw Error(message);};
export async function connectorPlan({root,updates,remote,fetchExact,maxReviews=32}){
 check(updates.length===1&&/^refs\/heads\//.test(updates[0].remoteRef),'Connector review requires one integration-branch push');
 check(Number.isSafeInteger(maxReviews)&&maxReviews>=1&&maxReviews<=32,'Invalid native review bound');
 const context=await gitContext(root,updates,remote,{fetchExact});
 const update=context.updates[0];
 check(update.kind==='fast-forward'&&update.baseCommit===update.base&&update.tipCommit===update.head,'Connector range is unsupported/incomplete: initial, deletion, unchanged and rewritten histories need explicit handling');
 const views=[],unchanged=[],seen=new Set();
 const add=(before,after)=>{
  const key=`${before}:${after}`;if(seen.has(key))return;seen.add(key);
  const changed=git(root,['diff-tree','--no-commit-id','--no-renames','-r','--name-only',before,after,'--']);
  if(changed)views.push({before,after});else unchanged.push({before,after});
 };
 add(update.baseCommit,update.tipCommit);
 for(const sha of update.added){
  const parent=git(root,['rev-list','--parents','-n','1',sha]).split(/\s+/)[1];
  check(/^[a-f0-9]{40}$/.test(parent||''),'Root commit review is unsupported/incomplete');
  add(parent,sha);
 }
 check(views.length<=maxReviews,'Native review count exceeds bound; no partial range may be requested');
 return {before:update.baseCommit,after:update.tipCommit,commits:update.added,views,unchanged,context:context.prompt};
}
