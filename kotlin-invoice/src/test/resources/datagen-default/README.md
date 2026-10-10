# Synthetic generation-recovery fixture

`generation-recovery.properties` projects only the first generated invoice number from the
full synthetic datagen default. `provenance.json` retains the exact historical generator
revision, command, seed, generation date and SHA-256 of the equivalent SQL output before the
level selector was removed. No remote database, object store, production fixture or existing
runtime artifact was used.

The current single-variant generator reproduces the full fixture in a dedicated
`little-trotter-admin-tools` worktree with:

```sh
env -i PATH=/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin \
  AWS_EC2_METADATA_DISABLED=true /usr/local/bin/python3 generate.py \
  --seed 42 --sql-file /private/tmp/lti-arc-i01-datagen.sql
```

Working directory: the dedicated worktree's `datagen/` directory. SQL mode avoids database
and object-store connections. The generator may create synthetic receipt files locally;
use a disposable worktree and never a shared data directory.

The projection selects the first `INSERT INTO invoice` statement's `invoice_number` value.
The processor unit suite needs no other invoice business attributes. Its local job IDs,
retry counts and injected exceptions are control values, not imported business records.
Startup lifecycle tests have no business dataset. Generating this fixture does not make
other existing unit/IT/Playwright fixtures compliant automatically; audit each before running.
