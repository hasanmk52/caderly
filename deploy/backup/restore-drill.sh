#!/usr/bin/env bash
# Restores a dump into a throwaway database and checks the schema is there, proving the backup
# is usable. Never touches the live database. Run as root:
#   restore-drill.sh /var/backups/caderly/<dump file>
set -euo pipefail

# Same minimal-PATH reason as backup.sh: runuser lives in /usr/sbin.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

dump="${1:?usage: restore-drill.sh <dump file>}"
scratch="caderly_restore_drill"
as_postgres() { runuser -u postgres -- "$@"; }

as_postgres dropdb --if-exists "$scratch"
as_postgres createdb "$scratch"
# --no-owner: the app role need not exist on the machine doing the drill.
as_postgres pg_restore -d "$scratch" --no-owner < "$dump"

tenants=$(as_postgres psql -d "$scratch" -Atc "select count(*) from caderly_hr.tenant")
migrations=$(as_postgres psql -d "$scratch" -Atc "select count(*) from caderly_hr.flyway_schema_history")
echo "restore drill ok: $tenants tenant(s), $migrations migration(s) in $dump"

as_postgres dropdb "$scratch"
