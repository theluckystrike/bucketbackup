#!/usr/bin/env python3 -I
"""Creates the BucketBackup project on Modrinth as a draft, uploads version 1.0.0, the icon and the gallery,
then (with --submit) sends it to moderation. Modrinth API v2, one request at a time.

Dry run (default): prints every request and sends nothing.
    python3 -I scripts/modrinth-publish.py
Real run (orchestrator, after the owner made the token):
    MODRINTH_TOKEN=mrp_... python3 -I scripts/modrinth-publish.py --publish [--submit]

The token needs the scopes PROJECT_CREATE, PROJECT_WRITE, VERSION_CREATE (and PROJECT_READ).
Run it from the product folder after `gradle build`.
"""
import json, os, sys, time, urllib.error, urllib.parse, urllib.request, uuid

API = 'https://api.modrinth.com/v2'
UA = 'theluckystrike/bucketbackup-publish/1.0 (github.com/theluckystrike/bucketbackup)'
PUBLISH = '--publish' in sys.argv
SUBMIT = '--submit' in sys.argv
L = json.load(open('listing/listing.json'))
TOKEN = os.environ.get('MODRINTH_TOKEN', '')


def multipart(fields, files):
    b = uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        out += f'--{b}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode() + value.encode() + b'\r\n'
    for name, (fname, data, ctype) in files.items():
        out += (f'--{b}\r\nContent-Disposition: form-data; name="{name}"; filename="{fname}"\r\n'
                f'Content-Type: {ctype}\r\n\r\n').encode() + data + b'\r\n'
    out += f'--{b}--\r\n'.encode()
    return bytes(out), f'multipart/form-data; boundary={b}'


def call(method, path, body=None, ctype=None, note=''):
    print(f'{method} {API}{path}  {note}')
    if not PUBLISH:
        return {}
    time.sleep(1.1)
    req = urllib.request.Request(API + path, data=body, method=method,
                                 headers={'Authorization': TOKEN, 'User-Agent': UA,
                                          **({'Content-Type': ctype} if ctype else {})})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        print('  HTTP', e.code, e.read().decode()[:500])
        sys.exit(1)


if PUBLISH and not TOKEN.startswith('mrp_'):
    sys.exit('Set MODRINTH_TOKEN to a personal access token (starts with mrp_).')
jar = L['version']['file']
if not os.path.exists(jar):
    sys.exit(f'{jar} is missing. Run gradle build first.')

project = {
    'slug': L['slug'], 'title': L['title'], 'description': L['summary'],
    'body': open('listing/modrinth-description.md').read(),
    'categories': L['categories'], 'additional_categories': L['additional_categories'],
    'client_side': L['client_side'], 'server_side': L['server_side'],
    'license_id': L['license_id'], 'project_type': L['project_type'],
    'source_url': L['source_url'], 'issues_url': L['issues_url'], 'wiki_url': L['wiki_url'],
    'donation_urls': L['donation_urls'], 'initial_versions': [], 'is_draft': True,
}
icon = open(L['icon'], 'rb').read()
body, ct = multipart({'data': json.dumps(project)}, {'icon': ('icon.png', icon, 'image/png')})
p = call('POST', '/project', body, ct, f'create draft project slug={L["slug"]}')
pid = p.get('id', '<project-id>')

v = L['version']
version = {
    'name': v['name'], 'version_number': v['version_number'], 'changelog': open(v['changelog_file']).read(),
    'dependencies': [], 'game_versions': v['game_versions'], 'version_type': v['version_type'],
    'loaders': v['loaders'], 'featured': v['featured'], 'project_id': pid, 'file_parts': ['file'],
    'primary_file': 'file',
}
body, ct = multipart({'data': json.dumps(version)},
                     {'file': (os.path.basename(jar), open(jar, 'rb').read(), 'application/java-archive')})
call('POST', '/version', body, ct, f'upload {os.path.basename(jar)} for {len(v["game_versions"])} game versions')

for g in L['gallery']:
    q = urllib.parse.urlencode({'ext': 'png', 'featured': str(g['featured']).lower(), 'title': g['title'],
                                'description': g['description']})
    call('POST', f'/project/{pid}/gallery?{q}', open(g['file'], 'rb').read(), 'image/png', f'gallery {g["file"]}')

if SUBMIT:
    call('PATCH', f'/project/{pid}', json.dumps({'status': 'processing'}).encode(), 'application/json',
         'submit for moderation')
print('done' if PUBLISH else 'dry run only, nothing was sent')
