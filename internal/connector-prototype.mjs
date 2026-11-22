import { readFile, appendFile } from 'node:fs/promises';
import { requestConnectorReview } from '../review/connector-request.mjs';
import { githubApi, redact } from '../report-failure/report.mjs';
const env=process.env;
try {
 if(env.GITHUB_EVENT_NAME!=='workflow_dispatch'||env.GITHUB_REPOSITORY!=='thinkfishhook/mobile-delivery')throw Error('This bounded prototype runs manually in the shared tool repository');
 const event=JSON.parse(await readFile(env.GITHUB_EVENT_PATH,'utf8'));
 const result=await requestConnectorReview({api:githubApi(env.GITHUB_REPOSITORY,env.GH_TOKEN,env.GITHUB_API_URL),repository:env.GITHUB_REPOSITORY,before:event.inputs.before,after:event.inputs.after,trigger:'automatic',runUrl:`${env.GITHUB_SERVER_URL}/${env.GITHUB_REPOSITORY}/actions/runs/${env.GITHUB_RUN_ID}/attempts/${env.GITHUB_RUN_ATTEMPT}`});
 await appendFile(env.GITHUB_STEP_SUMMARY,`\nConnector prototype requested [review-only PR #${result.number}](${result.url}).\n\nExact commit ${result.after}; parent ${result.before}. Model: Connector-managed, not reported. Disposable base: ${result.base}. Review trigger: automatic PR-open only; no bot mention was posted.\n\nRequest creation alone is not completed inference, a clean review, or issue-publication evidence. Inspect the trusted Connector response for this exact head. No app build or deployment was requested.\n`);
 console.log(`Requested Connector review: ${result.url}`);
}catch(error){
 const message=redact(error.message,[env.GH_TOKEN]);
 if(env.GITHUB_STEP_SUMMARY)await appendFile(env.GITHUB_STEP_SUMMARY,`\nConnector prototype failed/incomplete: ${message.replace(/[<>]/g,'')}\n`);
 console.error(message);process.exitCode=1;
}
