# Installing Caderly on a Debian server

Caderly runs as two containers (`app` and `caddy`) from `docker-compose.yml`. PostgreSQL is
installed natively on the host. Examples use `caderly.app`; substitute your own domain.

## 1. Prerequisites

- Debian 12 with `sudo`, ports 80 and 443 free.
- Docker Engine with the Compose plugin.
- PostgreSQL 17 (`apt install postgresql-17`). Caderly's tests run on 17 and production should
  too. PostgreSQL 16 also works (no migration uses 17-only SQL) and is acceptable for a demo.
- A domain whose DNS you control. This guide assumes Cloudflare for DNS, because Caddy needs a
  DNS API to get the wildcard certificate.

## 2. DNS

Two `A` records, both pointing at the server's IP and **not proxied** (grey cloud in Cloudflare):

| Name | Type | Value |
|---|---|---|
| `@` (caderly.app) | A | server IP |
| `*` (*.caderly.app) | A | server IP |

Check: `dig +short acme.caderly.app` returns the server IP. Moving to another server later means
restoring a backup there (section 8) and editing these two records.

Create a Cloudflare API token from the "Edit zone DNS" template, limited to this one zone.

## 3. Database

Create the role and database:

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE caderly LOGIN PASSWORD '<db password>';
CREATE DATABASE caderly OWNER caderly;
SQL
```

Nothing else changes in Postgres. The containers use host networking and connect to
`localhost:5432`, so the default `listen_addresses = 'localhost'` and the stock `pg_hba.conf`
rule for `127.0.0.1` are enough, and the database is never reachable from outside the server.
The role must be able to log in with a password over TCP: the default Debian
`host all all 127.0.0.1/32 scram-sha-256` (or `md5`) line covers it.

## 4. Configuration

```bash
sudo mkdir -p /var/lib/caderly/files && sudo chown 65532:65532 /var/lib/caderly/files
git clone <repo> caderly && cd caderly
cp .env.production.example .env && chmod 600 .env
```

Edit `.env`. Generate the secrets on the server so they never travel through chat or email:

```bash
openssl rand -base64 32      # CADERLY_EMPLOYEE_ENCRYPTION_KEY, back this up off the server
openssl rand -base64 24      # DB_PASSWORD
```

Set `CADERLY_SUPERADMIN_IP_ALLOWLIST` to the addresses that may open `/superadmin`. Leave it empty
and the console is unreachable.

## 5. Firewall

Only SSH, 80 and 443 should be reachable from outside. With `firewalld`:

```bash
sudo firewall-cmd --permanent --add-service=http --add-service=https
sudo firewall-cmd --reload
sudo firewall-cmd --list-all        # 5432 and 8080 must not appear
```

The app listens on `127.0.0.1:8085` only (`SERVER_ADDRESS` in `docker-compose.yml`), so only
Caddy on the same machine can reach it. That matters: the app trusts the first `X-Forwarded-For`
hop as the client address, and Caddy sets that header from the real peer. A directly reachable
app port would let a client forge it and bypass the Super Admin IP allowlist and login rate
limits. On a server where `firewalld` does not cover the public interface (an LXC container, for
example), the provider's own firewall is what must allow 80 and 443; confirm from another
machine with `nc -z <server ip> 443`.

Logs: `docker compose logs app` / `caddy`, kept by Docker under `/var/lib/docker/containers`
and rotated at 5 files of 10 MB each per container.

## 6. Start

```bash
docker compose up -d --build
docker compose logs -f caddy        # wait for "certificate obtained successfully"
```

Open `https://caderly.app`. Flyway creates the schema on first start.

## 7. Backups

`deploy/backup/backup.sh` writes a compressed `pg_dump`, checks it can be read back, keeps 14,
and optionally copies it to S3. It runs as root and dumps as the `postgres` superuser: the app's
own role is refused by `pg_dump` because every tenant table forces row-level security, and a
superuser is exempt from it. No database password is needed.

```bash
# 02:30 every night
echo '30 2 * * * root /opt/caderly/deploy/backup/backup.sh >> /var/log/caderly-backup.log 2>&1' | sudo tee /etc/cron.d/caderly-backup
```

Uploaded files live in `/var/lib/caderly/files` and are not in the dump; back that directory up
too. The encryption key in `.env` is also not in the dump, and the encrypted columns are
unreadable without it.

## 8. Restore drill

Run this once after the first backup, and again after any change to the backup setup:

```bash
sudo deploy/backup/restore-drill.sh /var/backups/caderly/<dump file>
```

It restores into a throwaway database, counts tenants and migrations, then drops it. To restore
for real on a new server: install Postgres, create the role and database (section 3), run
`sudo -u postgres pg_restore --no-owner --role=caderly -d caderly < <dump>`, copy `.env` and the files directory, and start the
stack.

## 9. Operations

- Update: `git pull && docker compose up -d --build`.
- Logs: `docker compose logs app` (structured JSON, with request, tenant and actor IDs).
- Certificates renew automatically; keep the `caddy_data` volume, because it holds them.
- Self-serve sign-up stays off until `CADERLY_SIGNUP_ENABLED=true`.
