# Restore drill evidence — `<release-id>`

This document contains metadata and counts only. Do not paste credentials, connection
strings, row samples, personal data, tokens, URLs, or file keys.

## Identification

- Drill ID:
- Release ID:
- Operator:
- Independent reviewer:
- Source snapshot/PITR ID:
- Source snapshot timestamp (UTC):
- New isolated target identifier:
- Exact-clone sentinel ID:
- Sentinel release ID (must equal Release ID):

## Timeline and RTO

- Approval time (UTC):
- Restore requested (UTC):
- Provider restore completed (UTC):
- Sentinel installed (UTC):
- Read-only verification completed (UTC):
- Total elapsed:
- RTO target: 4 hours
- Target met: yes / no

## Verification

- Expected database suffix `_exact`: pass / fail
- Host and database differ from production: pass / fail
- Network isolation reviewed: pass / fail
- Schema fingerprint SHA-256:
- Row-count evidence SHA-256:
- Financial aggregate evidence SHA-256:
- Sequence evidence SHA-256:
- Pre-migration snapshot evidence comparison: pass / fail
- Flyway was not run on restored rollback copy: pass / fail
- Application writes were not run: pass / fail

## Cleanup

- Sentinel marked destroyed (UTC):
- Clone roles revoked (UTC):
- Restored database destroyed (UTC):
- Provider deletion evidence reference:
- Non-PII evidence archive reference:

## Decision

- Result: PASS / FAIL
- Deviations and owner:
- Operator signature/time:
- Reviewer signature/time:
