# Caddy with the Cloudflare DNS module: a wildcard certificate (*.caderly.app) can only be
# issued through the DNS-01 challenge, and that needs a DNS-provider plugin the stock image
# lacks. Base images are pinned by digest (CLAUDE.md §6 A08).
FROM caddy:2-builder@sha256:c2fc374fd352439d76e1777b28d97d9f6d9c1dd86116db9227460374d46097d5 AS builder
RUN xcaddy build --with github.com/caddy-dns/cloudflare

FROM caddy:2@sha256:504a44d7d74bd2bc28a66afc4774912fc21223ca8a67734af1c54ab2f999b489
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
