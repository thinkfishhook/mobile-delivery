import {githubApi} from '../report-failure/report.mjs';
const check=(condition,message)=>{if(!condition)throw Error(message);};
export async function linkedConnectorRequester({repository,token,fetchImpl=fetch}){
 check(/^[\w.-]+\/[\w.-]+$/.test(repository),'Expected a caller repository');
 check(typeof token==='string'&&token.trim().length>0,'Set the linked GitHub requester credential');
 const response=await fetchImpl('https://api.github.com/user',{headers:{authorization:`Bearer ${token}`,accept:'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'},redirect:'error',signal:AbortSignal.timeout(30000)});
 check(response.ok,`Linked GitHub requester identity returned HTTP ${response.status}`);
 const user=await response.json();
 check(user?.type==='User'&&Number.isSafeInteger(user.id)&&user.id>0&&typeof user.login==='string'&&/^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/.test(user.login),'Linked requester must be an authenticated human GitHub user');
 return {user:{id:user.id,login:user.login,type:user.type},api:githubApi(repository,token)};
}
