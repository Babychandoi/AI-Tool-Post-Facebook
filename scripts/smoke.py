#!/usr/bin/env python3
"""Creates synthetic demo data only. Refuses a live workspace before any mutation."""
import urllib.request, urllib.parse, http.cookiejar, json, uuid, time, datetime, sys
base=sys.argv[1] if len(sys.argv)>1 else 'http://127.0.0.1:58080'
jar=http.cookiejar.CookieJar()
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
csrf=''
def request(path, method='GET', payload=None, key=None):
 headers={'Content-Type':'application/json'}
 if method!='GET': headers['X-XSRF-TOKEN']=csrf
 if key: headers['Idempotency-Key']=key
 data=None if payload is None else json.dumps(payload).encode()
 with client.open(urllib.request.Request(base+'/v1'+path,data=data,headers=headers,method=method),timeout=10) as r:
  raw=r.read()
  return json.loads(raw) if raw else {}
config=request('/auth/config')
assert config['demo'], 'Smoke refuses a non-demo workspace'
csrf=request('/auth/csrf')['token']
login=urllib.request.Request(base+'/v1/auth/login',data=urllib.parse.urlencode({'username':'owner@demo.local','password':'demo-marketing'}).encode(),headers={'Content-Type':'application/x-www-form-urlencoded','X-XSRF-TOKEN':csrf},method='POST')
client.open(login,timeout=10).close()
csrf=request('/auth/csrf')['token']
ai=request('/ai/chatgpt')
assert ai['active_id'] is None and not ai['api_configured'], 'Smoke refuses real AI credentials or active ChatGPT plans'
brand=request('/brands')[0]
account=next(a for a in request('/brands/'+brand['id']+'/accounts') if a['provider']=='DEMO')
run=request('/runs','POST',{'brand_id':brand['id'],'campaign_id':None,'topic':'Kiểm thử: chuẩn bị buổi trao đổi'},str(uuid.uuid4()))
for _ in range(40):
 run=request('/runs/'+run['id'])
 if run['status'] not in ('QUEUED','RUNNING'):break
 time.sleep(0.5)
assert run['status']=='WAITING_REVIEW', run
revision=next(r for r in request('/contents?brand_id='+brand['id']) if r['id']==run['output_revision_id'])
request('/revisions/'+revision['id']+'/approvals','POST',{'decision':'APPROVED','note':'Synthetic smoke test verified evidence','expected_hash':revision['snapshot_hash']})
due=datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=3)
key=str(uuid.uuid4())
schedule={'revision_id':revision['id'],'account_id':account['id'],'due_at':due.isoformat(),'intent':key}
publication=request('/publications','POST',schedule,key)
assert request('/publications','POST',schedule,key)['id']==publication['id']
for _ in range(40):
 current=next(p for p in request('/calendar?brand_id='+brand['id']) if p['id']==publication['id'])
 if current['status'] not in ('QUEUED','DISPATCHING'):break
 time.sleep(0.5)
assert current['status']=='PUBLISHED', current
assert current['remote_post_id'].startswith('demo_')
request('/forms/'+brand['public_form_key'],'POST',{'name':'Khách smoke demo','contact':'smoke@example.com','interest':'Synthetic smoke test','purpose':'Kiểm thử form có nguồn','consent':True,'reference':current['tracking_key']})
leads=request('/leads?brand_id='+brand['id'])['items']
lead=next(l for l in leads if l['contact']=='smoke@example.com')
assert lead['attribution_confidence']=='EXACT'
assert lead['source_publication_id']==publication['id']
request('/leads/'+lead['id'],'PATCH',{'stage':'QUALIFIED','assigned_user':'sales@demo.local','next_action':'Kiểm thử phân công','expected_version':lead['version']})
with client.open(base+'/v1/revisions/'+revision['id']+'/image',timeout=10) as image:
 assert image.read(8)==b'\x89PNG\r\n\x1a\n'
print(json.dumps({'result':'PASS','workflow':run['id'],'publication':publication['id'],'lead':lead['id'],'provider':'DEMO','real_facebook_posts':0},indent=2))
