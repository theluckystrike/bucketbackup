# BucketBackup

Scheduled world backups for Paper, uploaded straight to S3-compatible storage. Works with Cloudflare R2, AWS S3, Backblaze B2, Wasabi, MinIO, Hetzner and DigitalOcean Spaces. One small jar, no extra libraries, free and open source.

## Why another backup plugin

A backup on the same disk as the server dies with that disk. Object storage is cheap and far away from your host, and Cloudflare R2 gives you 10 GB for free with no egress fees. Most backup plugins write zips to the local disk or speak FTP and Google Drive. BucketBackup talks S3 directly and cleans up after itself.

## Features

- Zips every loaded world (or the ones you pick) plus files such as `server.properties`, then uploads the zip.
- Pauses auto-save while it zips and turns it back on before the upload, so the copy is consistent.
- Multipart upload with retries for big worlds. Failed uploads are aborted, so no half files stay behind.
- Retention keeps the newest N backups and can drop backups older than N days. It only deletes its own backups in its own folder, and only after the new backup shows up in the bucket with the right size.

Safety and setup:

- Free disk check before zipping, Discord webhook on failure, and keys from env variables so secrets stay out of your config and your backups.
- Ready for the Paper 26.x world layout: `level.dat`, player data and `data/` are always included.

## Commands

`/bucketbackup now`, `status`, `list`, `test`, `reload` (permission `bucketbackup.admin`, alias `/bbackup`).

## Setup

1. Drop the jar in `plugins/` and start the server once.
2. Create a bucket and a key that can only read and write that bucket.
3. Fill in `endpoint`, `region`, `bucket` and the keys in `plugins/BucketBackup/config.yml`.
4. Run `/bucketbackup reload` and `/bucketbackup test`, then `/bucketbackup now`.

The full guide, with Cloudflare R2 steps and restore steps, is in the README on GitHub.

## Tested

Tested with the same jar on Paper 26.3 and Paper 1.21.11 against a real S3 server that checks request signatures. Folia is not supported yet.

## Support

BucketBackup is free, and every feature stays free. If it saved your world, you can support the work on GitHub Sponsors (link on the right). Bug reports and ideas go to the issue tracker.
