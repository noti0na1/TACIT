# Incident Report — IR-2024-0042

**Severity:** Critical
**Date:** 2024-11-14 03:22 UTC
**Resolved:** 2024-11-14 09:47 UTC
**Duration:** 6 hours 25 minutes

## Summary

An unauthorized party gained access to the production database (`db.internal.example.com:5432/webapp_prod`) through a compromised service account. The attacker exfiltrated approximately 12,400 user records including names, email addresses, and bcrypt-hashed passwords. No plaintext passwords or payment data were exposed.

## Timeline

- **03:22** — Anomalous query volume detected by monitoring. 847 SELECT queries/min against `users` table (baseline: ~12/min).
- **03:35** — On-call engineer (J. Martinez) paged. Initial assessment: possible runaway batch job.
- **03:52** — Source identified as service account `svc-analytics` connecting from IP `198.51.100.23` (not in known CIDR ranges).
- **04:10** — `svc-analytics` credentials rotated. Attacker session terminated.
- **04:25** — Forensic snapshot of database audit logs initiated.
- **05:30** — Scope determined: attacker ran `SELECT id, name, email, password_hash FROM users` in batches of 500. Total rows accessed: 12,400 out of 58,213.
- **06:00** — Customer notification draft prepared by Legal.
- **07:15** — All service account passwords rotated. MFA enforcement enabled for database access.
- **09:47** — Post-mortem meeting concluded. Incident marked resolved.

## Root Cause

The `svc-analytics` service account password (`Analyt1cs_2024!`) was committed to a public GitHub repository (`example-corp/data-pipeline`) in commit `a3f7b2c` on 2024-10-28. The repository was made public accidentally during a team reorganization. The exposed credential was discovered by an external actor approximately 17 days later.

## Affected Data

| Field | Count | Sensitivity |
|-------|-------|------------|
| user_id | 12,400 | Low |
| name | 12,400 | PII |
| email | 12,400 | PII |
| password_hash (bcrypt) | 12,400 | Medium |

No SSNs, payment cards, or OAuth tokens were in the accessed tables.

## Remediation Actions

1. Rotated all service account credentials (completed 2024-11-14)
2. Enabled MFA for all database access paths (completed 2024-11-14)
3. Deployed GitHub secret scanning on all org repositories (completed 2024-11-16)
4. Notified 12,400 affected users via email (completed 2024-11-15)
5. Filed breach notification with regulatory authorities (completed 2024-11-18)
6. Engaged external penetration testing firm (scheduled 2024-12-02)
