#!/usr/bin/env python3
"""Source-controlled API contract for the implemented MVP (not provider endpoints)."""
from pathlib import Path
import json
S=lambda max=1000:{'type':'string','maxLength':max}
U={'type':'string','format':'uuid'}
I={'type':'integer','minimum':0}
T={'type':'string','format':'date-time'}
B={'type':'boolean'}
L={'type':'array','items':U,'maxItems':30}
def obj(props,required):return {'type':'object','properties':props,'required':required,'additionalProperties':False}
schemas={
 'BrandConfig':obj({'name':S(150),'industry':S(500),'voice':S(2000),'audience':S(1000),'color':{'type':'string','pattern':'^#[a-fA-F0-9]{6}$'},'timezone':S(100),'mode':{'type':'string','enum':['ASSISTED','RULED_AUTOPILOT']},'monthly_budget_micros':I,'expected_version':I,'require_separate_reviewer':B},['name','industry','voice','audience','color','timezone','mode','monthly_budget_micros']),
 'Draft':obj({'hook':S(250),'body':S(10000),'cta':S(1000),'source_refs':L},['hook','body','cta','source_refs']),
 'Knowledge':obj({'title':S(200),'body':S(200000),'source_url':{'type':['string','null'],'maxLength':2000},'effective_until':{'type':['string','null'],'format':'date-time'}},['title','body']),
 'Generate':obj({'brand_id':U,'campaign_id':{'type':['string','null'],'format':'uuid'},'topic':S(250)},['brand_id','topic']),
 'Review':obj({'decision':{'type':'string','enum':['APPROVED','CHANGES_REQUESTED']},'note':S(2000),'expected_hash':S(64)},['decision','note','expected_hash']),
 'Schedule':obj({'revision_id':U,'account_id':U,'due_at':T,'intent':S(100)},['revision_id','account_id','due_at','intent']),
 'Connect':obj({'page_id':S(100),'page_token':S(5000)},['page_id','page_token']),
 'Capture':obj({'name':S(150),'contact':S(200),'interest':S(2000),'purpose':S(500),'consent':{'type':'boolean','const':True},'reference':{'type':['string','null'],'format':'uuid'}},['name','contact','interest','purpose','consent']),
 'LeadUpdate':obj({'stage':{'type':'string','enum':['NEW','QUALIFIED','CONTACTED','WON','LOST','DISQUALIFIED']},'assigned_user':{'type':['string','null'],'maxLength':250},'next_action':{'type':['string','null'],'maxLength':2000},'expected_version':I},['stage','expected_version']),
 'Campaign':obj({'brand_id':U,'name':S(200),'objective':S(500),'pillars':{'type':'array','items':S(250),'maxItems':10},'generation_enabled':B,'daily_generation_time':{'type':'string','pattern':'^[0-2][0-9]:[0-5][0-9]$'}},['brand_id','name','objective','pillars']),
 'Automation':obj({'enabled':B,'daily_generation_time':S(8),'expected_version':I},['enabled','daily_generation_time','expected_version']),
 'Error':obj({'code':S(100),'message':S(3000),'trace_id':S(100),'retryable':B},['code','message']),
}
schemas['AutopostSettings']=obj({'account_id':{'type':['string','null'],'format':'uuid'},'objective':S(500),'pillars':{'type':'array','items':S(250),'minItems':1,'maxItems':20},'mode':{'type':'string','enum':['REVIEW','DEMO','AUTO']},'enabled':B,'daily_post_time':S(8),'image_mode':{'type':'string','enum':['TEMPLATE','OPENAI','GOOGLE','CHATGPT_WEB']},'image_prompt':S(2000),'image_api_key':{'type':['string','null'],'maxLength':5000,'writeOnly':True},'clear_image_key':B,'image_model':{'type':'string','enum':['gpt-image-2.5-sunburst','gpt-image-2.5-flare','gpt-image-2','gpt-image-1.5','gpt-image-1','gemini-3.1-flash-image','chatgpt-web-image']},'image_reserve_micros':{'type':'integer','minimum':10000,'maximum':10000000},'live_consent':B,'expected_version':I},['objective','pillars','mode','enabled','daily_post_time','image_mode','image_prompt','image_model','image_reserve_micros','live_consent','expected_version'])
schemas['AutopostStop']=obj({'expected_version':I},['expected_version'])
schemas['NewContent']=obj({'brand_id':U,'draft':{'$ref':'#/components/schemas/Draft'}},['brand_id','draft'])
schemas['NewLead']=obj({'brand_id':U,'lead':{'$ref':'#/components/schemas/Capture'}},['brand_id','lead'])
paths={}
def endpoint(path,method,summary,body=None,code=200,public=False,brand=False,key=False,extra=None):
 params=[]
 for part in path.split('/'):
  if part.startswith('{'):params.append({'name':part[1:-1],'in':'path','required':True,'schema':U})
 if brand:params.append({'name':'brand_id','in':'query','required':True,'schema':U})
 if key:params.append({'name':'Idempotency-Key','in':'header','required':True,'schema':S(160)})
 if method!='get' and not (public and '/forms/' in path) and '/webhooks/' not in path:params.append({'name':'X-XSRF-TOKEN','in':'header','required':True,'schema':S(500)})
 params.extend(extra or [])
 op={'summary':summary,'operationId':method+'_'+path.replace('/','_').replace('{','').replace('}',''),'security':[] if public else [{'session':[]}],'parameters':params,'responses':{str(code):{'description':'Successful response'},'400':{'description':'Invalid input'},'401':{'description':'Session required'},'403':{'description':'Role/scope/CSRF/signature denied'},'404':{'description':'Not found in granted scope'},'409':{'description':'Version, approval, intent or state conflict'},'429':{'description':'Rate limited'}}}
 if body:op['requestBody']={'required':True,'content':{'application/json':{'schema':{'$ref':'#/components/schemas/'+body}}}}
 paths.setdefault(path,{})[method]=op
endpoint('/v1/auth/csrf','get','Get CSRF token and header name',public=True)
endpoint('/v1/auth/config','get','Get demo/OIDC capabilities',public=True)
endpoint('/v1/auth/me','get','Current membership')
endpoint('/v1/ai/chatgpt','get','Current local owner registrations; never returns credentials')
endpoint('/v1/ai/chatgpt/connect','post','Start OpenAI PKCE authorization; credentials remain backend-side')
paths['/v1/ai/chatgpt/connect']['post']['requestBody']={'required':True,'content':{'application/json':{'schema':obj({'connection_id':{'anyOf':[U,{'type':'null'}]},'consent':B},[])}}}
endpoint('/v1/ai/chatgpt/callback','get','Session-bound, one-use state; verifies OpenAI identity; redirects to AI settings',code=303,extra=[{'name':n,'in':'query','schema':S(8192)} for n in ['state','code','client_id','error']])
endpoint('/v1/ai/chatgpt/{id}/models','get','Account-specific model catalog from OpenAI')
endpoint('/v1/ai/chatgpt/{id}/model','put','Validate and save model available to this registration')
paths['/v1/ai/chatgpt/{id}/model']['put']['requestBody']={'required':True,'content':{'application/json':{'schema':obj({'model':S(200)},['model'])}}}
endpoint('/v1/ai/chatgpt/{id}/activate','post','Select a registration owned by the current local member')
endpoint('/v1/ai/chatgpt/{id}','delete','Revoke renewable session and clear local credentials; reports remote revocation status')

endpoint('/v1/auth/logout','post','Invalidate session',code=204)
endpoint('/v1/brands','get','Brands in member scope')
endpoint('/v1/brands','post','Create brand (Owner/Admin)','BrandConfig')
endpoint('/v1/brands/{id}','put','Update config; advances policy version','BrandConfig')
endpoint('/v1/brands/{id}/pause','post','Set enabled; block new workflow/dispatch')
paths['/v1/brands/{id}/pause']['post']['requestBody']={'required':True,'content':{'application/json':{'schema':obj({'enabled':B},['enabled'])}}}
endpoint('/v1/brands/{brand}/knowledge','get','List source bodies in brand scope')
endpoint('/v1/brands/{brand}/knowledge','post','Import text in PENDING state','Knowledge')
endpoint('/v1/brands/{brand}/knowledge/{id}/approve','post','Approve valid source')
endpoint('/v1/brands/{brand}/accounts','get','Read account health; no token')
endpoint('/v1/brands/{brand}/accounts','post','Verify Meta token and connect Page','Connect')
endpoint('/v1/brands/{brand}/autopost','get','Daily setup, readiness and recent content; no secrets')
endpoint('/v1/brands/{brand}/autopost','put','Owner/Admin configure one daily slot; explicit live consent; version fences queued work','AutopostSettings')
endpoint('/v1/brands/{brand}/autopost/preview','post','Create draft with configured image; preview never schedules publishing',code=202)
endpoint('/v1/brands/{brand}/autopost/stop','post','Stop daily generation; cancel pending internal publications; keep dispatched outcomes','AutopostStop')
endpoint('/v1/contents','get','Latest revision per content',brand=True)
endpoint('/v1/contents','post','Create manual draft and private PNG','NewContent')
endpoint('/v1/contents/{id}/revisions','get','Immutable revision history')
endpoint('/v1/contents/{id}/revisions','post','Create new revision, requiring review','Draft')
endpoint('/v1/revisions/{id}/submit','post','Submit a DRAFT for review')
endpoint('/v1/revisions/{id}/approvals','post','Review the exact snapshot; expiry 7 days','Review')
endpoint('/v1/revisions/{id}/image','get','Authorized private PNG')
paths['/v1/revisions/{id}/image']['get']['responses']['200']={'description':'Private immutable asset','content':{'image/png':{'schema':{'type':'string','format':'binary'}}}}
endpoint('/v1/runs','get','Recent workflow runs',brand=True)
endpoint('/v1/runs','post','Queue asynchronous generation with budget reservation','Generate',202,key=True)
endpoint('/v1/runs/{id}','get','Workflow progress and steps')
endpoint('/v1/campaigns','get','Campaigns and cadence',brand=True)
endpoint('/v1/campaigns','post','Create campaign','Campaign')
endpoint('/v1/campaigns/{id}/automation','put','Configure one daily generation; optimistic lock','Automation')
endpoint('/v1/publications','post','Freeze approved snapshot and queue due job','Schedule',202,key=True)
endpoint('/v1/publications/{id}/cancel','post','Cancel confirmed internal schedule')
endpoint('/v1/publications/{id}/reconcile','post','Confirm UNKNOWN by Page/message and remote ID')
paths['/v1/publications/{id}/reconcile']['post']['requestBody']={'required':True,'content':{'application/json':{'schema':obj({'remote_post_id':S(200)},['remote_post_id'])}}}
endpoint('/v1/calendar','get','UTC publications; UI uses brand timezone',brand=True,extra=[{'name':n,'in':'query','schema':T} for n in ['from','to']])
endpoint('/v1/leads','get','Keyset page; Analyst redacted, Sales assigned or unassigned',brand=True,extra=[{'name':'limit','in':'query','schema':{'type':'integer','maximum':100}},{'name':'cursor','in':'query','schema':S(500)}])
endpoint('/v1/leads','post','Capture contact and consent','NewLead')
endpoint('/v1/leads/{id}','patch','Stage/assignment with expected version','LeadUpdate')
endpoint('/v1/forms/{key}','get','Public brand form metadata',public=True)
endpoint('/v1/forms/{key}','post','Public consented lead; reference is publication tracking key','Capture',public=True)
endpoint('/v1/dashboard','get','Real internal counts; demo is explicit',brand=True)
endpoint('/v1/reports','get','Metric snapshots and lead stages; missing value is NULL',brand=True)
endpoint('/v1/operations','get','Owner/Admin jobs, attempts, audit and events',brand=True)
endpoint('/v1/webhooks/meta','get','Challenge verification',public=True,extra=[{'name':n,'in':'query','required':True,'schema':S(300)} for n in ['hub.mode','hub.verify_token','hub.challenge']])
endpoint('/v1/webhooks/meta','post','HMAC raw-body verification, durable ACK, replay dedup',public=True,extra=[{'name':'X-Hub-Signature-256','in':'header','required':True,'schema':S(80)}])
paths['/v1/webhooks/meta']['post']['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','description':'Meta Page raw body, max 1MB; account mapping controls tenant'}}}}
paths['/v1/auth/login']={'post':{'summary':'Demo-only form login; local authentication disabled outside demo','security':[],'parameters':[{'name':'X-XSRF-TOKEN','in':'header','required':True,'schema':S(500)}],'requestBody':{'required':True,'content':{'application/x-www-form-urlencoded':{'schema':obj({'username':S(250),'password':S(250)},['username','password'])}}},'responses':{'200':{'description':'Session established; fetch a new CSRF token'},'401':{'description':'Invalid credentials'}}}}
contract={'openapi':'3.1.0','info':{'title':'AI Marketing MVP','version':'0.1.0','description':'Implemented application contract. Cookie session + CSRF for authenticated writes; provider APIs are separate.'},'servers':[{'url':'http://127.0.0.1:58080'}],'paths':paths,'components':{'securitySchemes':{'session':{'type':'apiKey','in':'cookie','name':'JSESSIONID'}},'schemas':schemas}}
Path('docs/openapi.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n')
print(f'Wrote {len(paths)} API paths')
