import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import {connectorPlan} from '../review/connector-plan.mjs';
async function repository(run){
 const root=await mkdtemp(join(tmpdir(),'connector-plan-'));
 const git=(...args)=>execFileSync('git',['-C',root,'-c','core.hooksPath=/dev/null',...args],{encoding:'utf8',stdio:'pipe',env:{...process.env,GIT_CONFIG_GLOBAL:'/dev/null',GIT_CONFIG_NOSYSTEM:'1'}}).trim();
 const commit=async text=>{await writeFile(join(root,'store.txt'),text);git('add','store.txt');git('commit','-m','Fixture change');return git('rev-parse','HEAD');};
 try{git('init','-b','trunk');git('config','user.name','Fixture');git('config','user.email','fixture@example.test');await run({root,git,commit});}finally{await rm(root,{recursive:true,force:true});}
}
const input=(root,before,after)=>({root,updates:[{localRef:'refs/heads/trunk',remoteRef:'refs/heads/trunk',base:before,head:after}],remote:'origin'});
test('a push reviews both its combined diff and every intermediate committed change',()=>repository(async({root,commit})=>{
 const before=await commit('compatible\n'),risk=await commit('destructive\n'),revert=await commit('compatible\n'),after=await commit('compatible\nother change\n');
 const plan=await connectorPlan(input(root,before,after));assert.deepEqual(plan.commits,[risk,revert,after]);
 assert.deepEqual(plan.views.map(v=>[v.before,v.after]),[[before,after],[before,risk],[risk,revert],[revert,after]]);
 assert.match(plan.context,/destructive/);assert.ok(plan.views.every(v=>v.after!==before));
}));
test('a single commit needs only one immutable review view',()=>repository(async({root,commit})=>{
 const before=await commit('old\n'),after=await commit('new\n');const plan=await connectorPlan(input(root,before,after));assert.equal(plan.views.length,1);assert.deepEqual(plan.commits,[after]);
}));
test('merge histories retain merged branch commits and their authoritative first parents',()=>repository(async({root,git,commit})=>{
 const before=await commit('base\n');git('switch','-c','topic');const branch=await commit('branch\n');git('switch','trunk');await writeFile(join(root,'other.txt'),'other\n');git('add','other.txt');git('commit','-m','Other');const trunk=git('rev-parse','HEAD');git('merge','--no-ff','topic','-m','Merge');const after=git('rev-parse','HEAD');
 const plan=await connectorPlan(input(root,before,after));assert.deepEqual(new Set(plan.commits),new Set([branch,trunk,after]));assert.ok(plan.views.some(v=>v.before===trunk&&v.after===after));
}));
test('initial deletion rewrite shallow and oversized pushes are explicitly incomplete',()=>repository(async({root,git,commit})=>{
 const before=await commit('before\n'),after=await commit('after\n'),third=await commit('third\n');
 for(const [a,b]of [['0'.repeat(40),after],[after,'0'.repeat(40)],[third,before]])await assert.rejects(connectorPlan(input(root,a,b)),/unsupported|incomplete/);
 await assert.rejects(connectorPlan({...input(root,before,third),maxReviews:1}),/bound/);
 const real=git('rev-parse','--git-dir');await writeFile(join(root,real,'shallow'),before+'\n');await assert.rejects(connectorPlan(input(root,before,third)),/Shallow/);
}));
test('empty committed changes are recorded separately without requesting a nonexistent diff',()=>repository(async({root,git,commit})=>{
 const before=await commit('same\n');git('commit','--allow-empty','-m','No source change');const after=git('rev-parse','HEAD');const plan=await connectorPlan(input(root,before,after));assert.deepEqual(plan.views,[]);assert.deepEqual(plan.unchanged,[{before,after}]);assert.deepEqual(plan.commits,[after]);
}));
