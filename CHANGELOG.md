# Changelog

## 1.0.0 (2026-11-06)

- First release. Scheduled and manual backups to S3-compatible storage (R2, S3, B2, Wasabi, MinIO).
- Multipart uploads with retries and abort on failure.
- Retention by count and age that only touches its own backups, after the new upload is verified.
- Paper 26.x world layout support, free disk check, Discord webhook, env variable secrets.
