#!/usr/bin/env bash
# Nightly logical backup of the Caderly database. Run as root (cron): the dump is taken as the
# `postgres` superuser over the local socket. The app's own role cannot do this, because every
# tenant table has FORCE ROW LEVEL SECURITY (CLAUDE.md §5) and pg_dump as that role is refused;
# a superuser bypasses RLS and sees every tenant's rows. No password is involved.
# Keeps 30 local dumps (PRD §19.8); set S3_URI to also copy each one off-site.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/caderly}"
DB_NAME="${DB_NAME:-caderly}"
KEEP="${KEEP:-30}"

umask 077
mkdir -p "$BACKUP_DIR"
file="$BACKUP_DIR/${DB_NAME}-$(date +%Y%m%d-%H%M%S).dump"

# Custom format: compressed, and pg_restore can restore it selectively. The redirect is done by
# this (root) shell so postgres needs no write access to the backup directory.
runuser -u postgres -- pg_dump -Fc "$DB_NAME" > "$file"

# An empty or truncated dump must fail loudly, not sit in the rotation as a "backup".
runuser -u postgres -- pg_restore --list < "$file" > /dev/null

# Optional off-site copy (needs the AWS CLI configured for S3_URI's bucket).
if [[ -n "${S3_URI:-}" ]]; then
    aws s3 cp "$file" "$S3_URI/"
fi

ls -1t "$BACKUP_DIR"/${DB_NAME}-*.dump | tail -n +"$((KEEP + 1))" | xargs -r rm --
echo "backup ok: $file"
