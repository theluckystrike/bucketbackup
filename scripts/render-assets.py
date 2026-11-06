#!/usr/bin/env python3 -I
"""Draws the icon and renders real console logs into gallery images.
Usage: render-assets.py <console-26.3.log> <console-1.21.11.log> <outdir>"""
import re, sys
from PIL import Image, ImageDraw, ImageFont

log26, log121, out = sys.argv[1:4]
FONT = '/System/Library/Fonts/Menlo.ttc'


def icon(path):
    s = 512
    im = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, s - 1, s - 1], 96, fill=(24, 31, 42))
    # bucket body
    d.polygon([(118, 210), (394, 210), (360, 430), (152, 430)], fill=(70, 130, 200))
    d.ellipse([118, 180, 394, 240], fill=(110, 170, 235))
    d.ellipse([140, 192, 372, 230], fill=(40, 90, 150))
    # grass block in the bucket
    d.polygon([(256, 96), (330, 134), (256, 172), (182, 134)], fill=(108, 190, 72))
    d.polygon([(182, 134), (256, 172), (256, 214), (182, 176)], fill=(134, 96, 60))
    d.polygon([(330, 134), (256, 172), (256, 214), (330, 176)], fill=(110, 78, 48))
    # up arrow
    d.polygon([(256, 262), (310, 320), (276, 320), (276, 392), (236, 392), (236, 320), (202, 320)],
              fill=(240, 244, 248))
    im.save(path)
    im.resize((128, 128), Image.LANCZOS).save(path.replace('.png', '-128.png'))


def lines_of(log, keep):
    rows = []
    for ln in open(log, encoding='utf-8', errors='replace'):
        ln = ln.rstrip('\n')
        if keep(ln):
            rows.append(ln)
    return rows


def shot(title, rows, path, w=1920):
    f = ImageFont.truetype(FONT, 19)
    fb = ImageFont.truetype(FONT, 22)
    lh = 28
    h = 90 + lh * len(rows) + 30
    im = Image.new('RGB', (w, h), (18, 22, 28))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w, 50], fill=(34, 40, 50))
    for i, c in enumerate([(237, 106, 94), (245, 191, 79), (98, 197, 84)]):
        d.ellipse([18 + i * 28, 17, 34 + i * 28, 33], fill=c)
    d.text((120, 13), title, font=fb, fill=(220, 226, 234))
    y = 70
    for r in rows:
        col = (200, 206, 214)
        if r.startswith('> '):
            col = (250, 210, 120)
        elif 'Backup done' in r or 'Connection OK' in r or 'ZIP CHECK OK' in r:
            col = (120, 220, 140)
        elif 'Retention removed' in r or 'already running' in r:
            col = (140, 190, 255)
        elif '[BucketBackup]' in r or r.startswith('  '):
            col = (225, 230, 236)
        d.text((24, y), r.replace(" INFO]: ", "] ")[:165], font=f, fill=col)
        y += lh
    im.save(path)


icon(f'{out}/icon.png')
MARK = [('Target', '> bucketbackup status'), ('Connection OK', '> bucketbackup test'),
        ('Backup started (command', '> bucketbackup now'),
        ('Backup started (command', '> bucketbackup now   (sent twice in the same server tick)'),
        ('backup(s) in', '> bucketbackup list'),
        ('Backup started (schedule)', '   (60 s after start the scheduled startup backup runs)'),
        ('Target', '> bucketbackup status')]
pat = re.compile(r'\[BucketBackup\]|^\s{2}\S|This server is running')
rows = []
for ln in lines_of(log26, lambda l: bool(pat.search(l)) and not re.search('Loading server plugin|Enabling|Disabling', l)):
    if MARK and MARK[0][0] in ln:
        rows.append(MARK.pop(0)[1])
    rows.append(re.sub(r' sha256 [0-9a-f]{64}', '', ln))
rows.append('')
rows.append('$ python3 scripts/check-zips.py s3data/mc-backups/minecraft/test-server')
rows += [l.rstrip() for l in open(f'{out}/zipcheck-26.3.txt')]
shot('Paper 26.3 (build 244), headless, BucketBackup 1.0.0 -> local S3 server (signature checked)', rows,
     f'{out}/screenshot-paper-26.3.png')

rows = [re.sub(r' sha256 [0-9a-f]{64}', '', l) for l in lines_of(log121, lambda l: 'Backs up' in l or 'Backup done' in l
                                                                  or 'Retention removed' in l or 'This server is running' in l)]
rows.append('')
rows.append('$ python3 scripts/check-zips.py s3data/mc-backups/minecraft/test-server')
rows += [l.rstrip() for l in open(f'{out}/zipcheck-1.21.11.txt')]
shot('Paper 1.21.11 (build 132): same jar, worlds world, world_nether, world_the_end', rows,
     f'{out}/screenshot-paper-1.21.11.png')
print('assets ok')
