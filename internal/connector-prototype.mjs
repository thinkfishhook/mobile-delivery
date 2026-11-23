import { readFile, appendFile } from 'node:fs/promises';
import { requestConnectorReview } from '../review/connector-request.mjs';
import { linkedConnectorRequester } from '../review/connector-requester.mjs';
import { githubApi, redact } from '../report-failure/report.mjs';
const env=process.env;
try {
 if(env.GITHUB_EVENT_NAME!=='workflow_dispatch'||env.GITHUB_REPOSITORY!=='thinkfishhook/mobile-delivery')throw Error('This bounded prototype runs manually in the shared tool repository');
 const event=JSON.parse(await readFile(env.GITHUB_EVENT_PATH,'utf8'));
 const trigger=event.inputs.trigger||'automatic';
 if(!['automatic','linked-mention'].includes(trigger))throw Error('Expected automatic or linked-mention prototype trigger');
 if(trigger==='linked-mention'&&(env.GITHUB_SERVER_URL!=='https://github.com'||env.GITHUB_API_URL!=='https://api.github.com'))throw Error('Linked requester prototype supports github.com only');
 const requester=trigger==='linked-mention'?await linkedConnectorRequester({repository:env.GITHUB_REPOSITORY,token:env.CODEX_CONNECTOR_REQUEST_TOKEN}):undefined;
 const result=await requestConnectorReview({api:githubApi(env.GITHUB_REPOSITORY,env.GH_TOKEN,env.GITHUB_API_URL),repository:env.GITHUB_REPOSITORY,before:event.inputs.before,after:event.inputs.after,trigger,requester,runUrl:`${env.GITHUB_SERVER_URL}/${env.GITHUB_REPOSITORY}/actions/runs/${env.GITHUB_RUN_ID}/attempts/${env.GITHUB_RUN_ATTEMPT}`});
 const requestMode=trigger==='automatic'?'automatic PR-open only; no mention was posted':`linked human mention by ${result.requestedBy.login}; workflow token retains branch/PR ownership`;
 await appendFile(env.GITHUB_STEP_SUMMARY,`\nConnector prototype requested [review-only PR #${result.number}](${result.url}).\n\nExact commit ${result.after}; parent ${result.before}. Model: Connector-managed, not reported. Disposable base: ${result.base}. Review trigger: ${requestMode}.\n\nRequest creation alone is not completed inference, a clean review, or issue-publication evidence. Inspect the trusted Connector response for this exact head. No app build or deployment was requested.\n`);
 console.log(`Requested Connector review: ${result.url}`);
}catch(error){
 const message=redact(error.message,[env.GH_TOKEN,env.CODEX_CONNECTOR_REQUEST_TOKEN]);
 if(env.GITHUB_STEP_SUMMARY)await appendFile(env.GITHUB_STEP_SUMMARY,`\nConnector prototype failed/incomplete: ${message.replace(/[<>]/g,'')}\n`);
 console.error(message);process.exitCode=1;
}
