#!/usr/bin/env python3 -I
"""Checks the backup zips that the real run uploaded. Usage: check-zips.py <folder in the S3 data dir>"""
import os, sys, zipfile
d = sys.argv[1]
zips = sorted(f for f in os.listdir(d) if f.endswith('.zip'))
print('objects in bucket folder:', zips)
assert len(zips) == 1, 'keep-last 1 must leave exactly one backup'
z = zipfile.ZipFile(os.path.join(d, zips[0]))
assert z.testzip() is None, 'zip CRC check failed'
names = z.namelist()
regions = [n for n in names if n.endswith('.mca')]
level = [n for n in names if n.endswith('level.dat')]
print(f'{zips[0]}: {len(names)} entries, {len(regions)} region files, level.dat: {level[:3]}, '
      f'session.lock present: {any(n.endswith("session.lock") for n in names)}, '
      f'server.properties: {"server.properties" in names}, size {os.path.getsize(os.path.join(d, zips[0]))} bytes')
assert regions and 'server.properties' in names
assert 'world/level.dat' in names, 'level.dat of the main world must be in the zip'
assert not any(n.endswith('session.lock') for n in names)
print('ZIP CHECK OK')
