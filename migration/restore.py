#!/usr/bin/env python3
"""Restore a migration bundle to NEW Docker volumes; works on Windows and WSL.

Does not overwrite existing volumes, restore raw PostgreSQL, or enable posting.
No binary backup data passes through a PowerShell text pipeline.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

def command(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)

def output(args):
    return subprocess.check_output(args, text=True).strip()

def verify(bundle):
    manifest = json.loads((bundle / 'MANIFEST.json').read_text(encoding='utf-8'))
    for item in manifest['files']:
        file = (bundle / item['path']).resolve()
        if not file.is_relative_to(bundle) or not file.is_file():
            raise RuntimeError('Missing or unsafe backup path: ' + item['path'])
        digest = hashlib.sha256()
        with file.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        if file.stat().st_size != item['bytes'] or digest.hexdigest() != item['sha256']:
            raise RuntimeError('Backup checksum mismatch: ' + item['path'])
    print('All backup file checksums match.')
    return manifest

def wait_health(compose, service, seconds=180):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        cid = output(compose + ['ps', '-q', service])
        if cid:
            state = json.loads(output(['docker', 'inspect', cid]))[0]['State']
            if state.get('Health', {}).get('Status') == 'healthy':
                return cid
            if state.get('Status') in ('exited', 'dead'):
                break
        time.sleep(2)
    raise RuntimeError(service + ' is not healthy. Inspect docker compose logs; do not delete restored volumes.')

def set_env_file(path, key, value):
    text = path.read_text(encoding='utf-8')
    pattern = r'^' + re.escape(key) + r'=.*$'
    text = re.sub(pattern, key + '=' + value, text, flags=re.M) if re.search(pattern, text, re.M) else text.rstrip() + '\n' + key + '=' + value + '\n'
    path.write_text(text, encoding='utf-8', newline='\n')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle-root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--project', default='ai-marketing-windows')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--build-from-artifacts', action='store_true', help='Requires Internet; skips prebuilt image bundle')
    args = parser.parse_args()
    bundle = args.bundle_root.resolve()
    manifest = verify(bundle)
    if args.verify_only:
        return
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,50}', args.project):
        raise RuntimeError('Invalid Docker project name')
    info = json.loads(output(['docker', 'info', '--format', '{{json .}}']))
    if info.get('OSType') != 'linux':
        raise RuntimeError('Docker must use Linux containers / WSL 2.')
    architecture = info.get('Architecture', '')
    if architecture not in ('x86_64', 'amd64', 'aarch64', 'arm64'):
        raise RuntimeError('Unsupported Docker architecture: ' + architecture)
    if architecture in ('aarch64', 'arm64') and not args.build_from_artifacts:
        raise RuntimeError('Prebuilt images are AMD64. Use --build-from-artifacts for ARM64, with Internet access.')
    project = bundle / 'project'
    state_file = bundle / 'restore-state.json'
    if state_file.exists():
        raise RuntimeError('This bundle was already used to restore. Use Start-Windows.ps1; do not restore over existing data.')
    suffixes = ['postgres-data', 'minio-data', 'redis-data', 'image-bridge-state']
    existing = set(output(['docker', 'volume', 'ls', '--format', '{{.Name}}']).splitlines())
    if any(args.project + '_' + suffix in existing for suffix in suffixes):
        raise RuntimeError('Destination volumes already exist. Choose a NEW --project; no existing data will be overwritten.')
    set_env_file(project / '.env', 'LIVE_PUBLISHING', 'false')
    platform = 'linux/arm64' if architecture in ('arm64', 'aarch64') else 'linux/amd64'
    set_env_file(project / '.env', 'MIGRATION_PLATFORM', platform)
    compose = ['docker', 'compose', '--project-directory', str(project), '--env-file', str(project / '.env'), '-p', args.project, '-f', str(project / 'compose.yaml'), '-f', str(project / 'migration/compose.windows.yaml')]
    command(compose + ['config', '--quiet'])
    if args.build_from_artifacts:
        for service, ref in manifest['dependency_images'].items():
            command(['docker', 'pull', '--platform', platform, ref])
            command(['docker', 'tag', ref, 'ai-marketing-' + service + ':windows-20261008'])
        command(compose + ['build', 'api', 'frontend', 'minio', 'image-bridge'])
    else:
        command(['docker', 'load', '-i', str(bundle / 'images/linux-amd64-images.tar.gz')])
    for suffix in suffixes:
        command(['docker', 'volume', 'create', args.project + '_' + suffix])
    # Only these three archives are restored. Raw Mac PostgreSQL is archival only.
    for suffix in ('minio-data', 'redis-data', 'image-bridge-state'):
        command(['docker', 'run', '--rm', '--network', 'none', '--platform', platform,
                 '--mount', 'type=volume,source=' + args.project + '_' + suffix + ',target=/target',
                 '--mount', 'type=bind,source=' + str(bundle / 'data') + ',target=/backup,readonly',
                 '--entrypoint', 'sh', 'ai-marketing-redis:windows-20261008', '-c',
                 'test -z "$(ls -A /target)" && tar -xzf /backup/' + suffix + '.tar.gz -C /target'])
    command(compose + ['up', '-d', '--no-build', '--pull', 'never', 'postgres', 'redis', 'minio', 'image-bridge'])
    pg = wait_health(compose, 'postgres')
    command(['docker', 'cp', str(bundle / 'data/postgres.dump'), pg + ':/tmp/migration.dump'])
    command(compose + ['exec', '-T', 'postgres', 'pg_restore', '-U', 'marketing', '-d', 'marketing', '--exit-on-error', '--no-owner', '--no-privileges', '/tmp/migration.dump'])
    command(compose + ['exec', '-T', 'postgres', 'rm', '/tmp/migration.dump'])
    # A restore does not constitute permission to run a Facebook schedule.
    command(compose + ['exec', '-T', 'postgres', 'psql', '-U', 'marketing', '-d', 'marketing', '-v', 'ON_ERROR_STOP=1', '-c', 'UPDATE autopost_config SET enabled=false, live_consent=false WHERE enabled OR live_consent;'])
    expected = manifest['database_counts']
    for table, count in expected.items():
        actual = int(output(compose + ['exec', '-T', 'postgres', 'psql', '-U', 'marketing', '-d', 'marketing', '-Atc', 'SELECT count(*) FROM public."' + table + '"']))
        if actual != count:
            raise RuntimeError('Restored row count differs for ' + table)
    print('Database row counts match the source snapshot.')
    command(compose + ['up', '-d', '--no-build', '--pull', 'never', 'api'])
    wait_health(compose, 'api', 240)
    command(compose + ['up', '-d', '--no-build', '--pull', 'never', 'worker', 'frontend'])
    state = {'project': args.project, 'platform': platform, 'compose_arguments': compose[2:], 'app_url': 'http://127.0.0.1:5173', 'live_publishing': False}
    state_file.write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
    print('Restored. Open http://127.0.0.1:5173 on the Windows host. Log in and reconnect the browser image extension.')
    print('Facebook publishing and daily schedules remain OFF.')

if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError) as error:
        print('Restore stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
