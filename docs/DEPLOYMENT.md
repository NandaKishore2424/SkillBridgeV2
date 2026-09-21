# Deployment

Three pieces, each where it costs least and breaks least. Phase 7.

```
        browser
           │  https
           ▼
      Vercel  ── serves the SPA
           │  rewrites /api/* server-side
           ▼
   EC2 (ap-south-1, off unless in use)
     Caddy ─▶ backend ─▶ RabbitMQ ─▶ AI service
                 │                      │
                 └──────────┬───────────┘
                            ▼
                        Supabase
```

This is not a production architecture and does not pretend to be. It is a demo
host for one viewer at a time, and every decision below is made for that. Where
a real deployment would differ, it says so — being able to explain the gap is
worth more in an interview than pretending there isn't one.

---

## Why the pieces are where they are

**The SPA is on Vercel** because static hosting with a CDN and automatic TLS is
free and needs no instance running. It also means the demo's front door works
even when the EC2 host is off — the page loads and the API calls fail, which is
a far better failure than a dead link.

**`/api/*` is proxied by Vercel, not called directly.** This is the decision
that matters most, and `skillbridge-frontend/VERCEL.md` explains it at length.
Short version: the session's refresh token is an HttpOnly `SameSite=Lax`
cookie. Called cross-site, the browser would not send it, the session would die
at the first token expiry, and a page refresh would drop the user to sign-in.
The rewrite keeps the cookie first-party and removes CORS entirely.

**The database is Supabase** — managed, free at this size, and nothing to back
up, patch or size on the instance. It also removes PostgreSQL's memory from the
host budget, which is most of why the rest fits.

**Everything else is on EC2** because RabbitMQ, the outbox relay and a 2.3 GB
AI service are not serverless-shaped. The instance is off unless an interview
is happening.

**The host never builds.** CI builds both images, smoke-tests them and pushes
to GHCR; the host pulls. The AI image carries PyTorch and the MiniLM weights —
building that on a t3.medium would take a long time and might not finish.

> **Integronix** is deployed the same shape and is worth comparing. Two
> deliberate differences: it uses **nginx + certbot** where this uses **Caddy**
> (one container instead of a package, a renewal hook and two config files),
> and its frontend calls the API **directly with CORS** because its session is
> a Supabase bearer token with no cookie to lose. The constraint is the cookie,
> not the hosting.

### Why t3.medium is the floor

Memory limits total **2880m**, leaving about 1.2 GiB for the OS:

| | |
|---|---|
| `ai` | 1536m |
| `backend` | 768m |
| `rabbitmq` | 384m |
| `caddy` | 128m |
| `mailpit` | 64m |

A t3.small has 2 GiB. The AI service alone wants 1.5 GiB of that, because
PyTorch plus the model is about a gigabyte resident before it does any work,
and it cannot be traded away — it is the feature. Anyone can try; what they
will see is the kernel's OOM killer removing whichever container was
unluckiest, which reads as a random crash.

---

## What it costs

**Check the AWS pricing calculator for `ap-south-1` before quoting a number.**
These are indicative and were not verified against a live price list; the shape
is what matters and the shape does not change.

| | Charged | Roughly |
|---|---|---|
| EC2 t3.medium | **only while running** | ~$0.04/hour |
| EBS 30 GiB gp3 | **always** | ~$2–3/month |
| Vercel (hobby) | — | free |
| Supabase (free tier) | — | free |
| Elastic IP | **not used** | — |

**There is no Elastic IP**, and that is deliberate. AWS charges for one every
hour it is not attached to a *running* instance — on a box used four hours a
week, that is a standing charge for the other 164. Instead `skillbridge-dns`
points a free deSEC hostname at the instance's current address at every boot.
The name stays fixed, which is all the certificate and the Vercel rewrite
need; only the address moves.

So the floor is the EBS volume, a few dollars a month, against a $100 credit
that expires **2027-01-27**.

> **The mistake is forgetting to stop it.** An interview ends in a
> conversation, the tab closes, the box runs for three weeks. At ~$0.04/hour
> that is about $30 — a third of the credit, for nothing. Four hours a week is
> about **$0.70 a month**.

Two guardrails in `deploy/aws/cost-guardrails.yaml`: a **nightly auto-stop**
(the one that does the work) and a **budget** that warns at 50%, at 100%, and
when the *forecast* passes 100%. Apply once:

```bash
aws cloudformation deploy \
  --template-file deploy/aws/cost-guardrails.yaml \
  --stack-name skillbridge-cost-guardrails \
  --region ap-south-1 --capabilities CAPABILITY_IAM \
  --parameter-overrides InstanceId=i-0... NotifyEmail=you@example.com
```

AWS emails a subscription confirmation. **Until you accept it the budget exists
and notifies nobody.**

### The Supabase catch

**The free tier pauses a project after about a week of inactivity.** Interviews
are weeks apart, so the realistic failure is arriving at one with a paused
database and a few minutes of unpausing in front of an interviewer.

Open the Supabase dashboard as part of starting the instance, not after. The
backend's healthcheck will fail while the database is paused, so
`skillbridge-deploy` reports it rather than leaving you to discover it in the
browser.

---

## First-time setup

### 1. Supabase

1. Create a project in a region near you. **Save the database password** — it
   is shown once.
2. SQL Editor: `create extension if not exists vector;` and
   `create extension if not exists pg_trgm;`. V1 also creates them, but
   creating them first means a migration failure is about the migration.
3. **Connect → Session pooler.** Port **5432** on
   `aws-N-ap-south-1.pooler.supabase.com`, user `postgres.<project-ref>`. Not
   the direct host — it is **IPv6-only**, and an EC2 instance in a default VPC
   has no IPv6 route — and not the transaction pooler on 6543.
   `deploy/app.env.example` explains both at the `DATABASE_URL` entry.

### 2. A hostname

Register a free dynamic-DNS name at [desec.io](https://desec.io) (the same
provider integronix uses) — e.g. `skillbridge.dedyn.io` — and create a token.
No domain purchase, no Elastic IP.

### 3. The instance

- **Region** `ap-south-1`, **type** `t3.medium`, **storage** 30 GiB gp3
- **AMI** Amazon Linux 2023 or Ubuntu 24.04 (these notes assume `ec2-user`)
- **No Elastic IP.**

Security group:

| Port | From | Why |
|---|---|---|
| 22 | your IP only | SSH |
| 80 | anywhere | the ACME challenge; Caddy redirects to 443 |
| 443 | anywhere | Vercel's proxy reaches the API here |

443 open to the world is a real exposure, and worth being straight about: the
control is that **the instance is off except during an interview**, so the
window is the interview. The nightly rule closes it if you forget.

### 4. The host

```bash
sudo dnf install -y docker git && sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user      # log out and back in

sudo mkdir -p /opt/skillbridge && sudo chown ec2-user:ec2-user /opt/skillbridge
git clone https://github.com/NandaKishore2424/SkillBridgeV2.git ~/skillbridge-src
cd ~/skillbridge-src

# Configuration and the files the units expect
cp deploy/docker-compose.prod.yml /opt/skillbridge/compose.yml
cp deploy/Caddyfile               /opt/skillbridge/Caddyfile
cp deploy/app.env.example         /opt/skillbridge/app.env
sudo chown root:root /opt/skillbridge/app.env && sudo chmod 600 /opt/skillbridge/app.env
sudo -e /opt/skillbridge/app.env          # fill it in; generate secrets HERE

# Dynamic DNS
sudo mkdir -p /etc/skillbridge
echo skillbridge.dedyn.io | sudo tee /etc/skillbridge/dns-host >/dev/null
sudo -e /etc/skillbridge/dns-token        # paste the deSEC token
sudo chmod 600 /etc/skillbridge/dns-*
sudo install -m 755 deploy/bin/skillbridge-dns    /usr/local/sbin/
sudo install -m 755 deploy/bin/skillbridge-deploy /usr/local/bin/
sudo install -m 644 deploy/systemd/*.service deploy/systemd/*.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now skillbridge-dns.timer
sudo systemctl enable skillbridge.service
```

Generate every secret **on the host** — a box reachable from the internet
should not share a secret with a laptop:

```bash
openssl rand -hex 16     # RABBITMQ_PASSWORD
openssl rand -base64 48  # JWT_SECRET
openssl rand -hex 32     # AI_SERVICE_TOKEN
```

### 5. First deploy

```bash
sudo systemctl start skillbridge-dns    # publish the address before asking for a cert
sudo skillbridge-deploy
docker compose -f /opt/skillbridge/compose.yml logs -f caddy   # watch the certificate
```

Flyway builds the schema against Supabase on the backend's first start.

### 6. The corpus — do not skip this

**A fresh database has no job descriptions**, and the skill-gap report is the
point of the product. The 1,500 embeddings are not in the migrations and cannot
be regenerated: the ETL's source CSV is gone, and `~/skillbridge-backup/` on
the development machine is the only other copy.

After step 5 — Flyway has to have built the table — and from the development
machine, with the local stack up:

```bash
docker compose exec -T postgres pg_dump -U skillbridge -d skillbridge \
  --table=industry_job_descriptions --data-only | gzip > /tmp/corpus.sql.gz

gzip -dc /tmp/corpus.sql.gz | scripts/db/pg.sh psql -q -v ON_ERROR_STOP=1
```

`scripts/db/pg.sh` asks for the session-pooler URL without echoing it, so the
password stays out of shell history and off the screen, and runs `psql` in a
throwaway `pgvector/pgvector:pg17` container — there is no PostgreSQL client on
the laptop or the host. Then confirm — it must print `1500|1500`:

```bash
scripts/db/pg.sh psql -tAc \
  "select count(*), count(embedding) from industry_job_descriptions"
```

Rehearsed 2026-09-21 into a throwaway pg17 with the schema: `1500|1500`, 384
dimensions, and `search_similar_jobs` returns a stored embedding's own row at
similarity 1.0.

### 7. Seed the demo data

On the host, from the source checkout:

```bash
sudo ./scripts/db/seed-demo.sh --yes --app-env /opt/skillbridge/app.env
```

It reads `AI_DATABASE_URL` from `app.env` (root-only, hence `sudo`) and prints
`16/16 reports` once the real pipeline has produced them.

### 8. Vercel

`skillbridge-frontend/VERCEL.md` has the detail. **Set the backend hostname in
`vercel.json` first** — it ships with `REPLACE-ME.dedyn.io` — then:

```bash
./deploy/vercel.test.sh --deploy     # refuses while the placeholder is there
```

Import the repo, root directory `skillbridge-frontend`, framework Vite, no
environment variables. Then set `MAIL_LOGIN_URL` in `app.env` to the Vercel URL
and redeploy the backend.

### 9. Control it from the laptop

```bash
cat > deploy/instance.env <<'EOF'
SKILLBRIDGE_INSTANCE_ID=i-0123456789abcdef0
SKILLBRIDGE_REGION=ap-south-1
SKILLBRIDGE_HOST=skillbridge.dedyn.io
SKILLBRIDGE_SSH_KEY=~/.ssh/skillbridge.pem
EOF
```

`deploy/instance.env` is gitignored.

---

## Everyday use

```bash
deploy/instance.sh start     # start, wait for the app, print the URL
deploy/instance.sh stop      # THE IMPORTANT ONE
deploy/instance.sh status
deploy/instance.sh logs
```

Before an interview: start it, open the Supabase dashboard to be sure the
project is awake, and reseed if the data has been clicked about:

```bash
deploy/instance.sh ssh
cd ~/skillbridge-src && sudo ./scripts/db/seed-demo.sh --yes --app-env /opt/skillbridge/app.env
```

After: `deploy/instance.sh stop`. The nightly rule catches this if you forget.
Do not rely on it — it is a net, not a plan.

To ship a change: push to `main`, wait for CI to publish, then
`sudo skillbridge-deploy`. To roll back, `sudo skillbridge-deploy sha-<commit>`.

---

## Backups

`deploy/backup.sh`, from cron on the host:

```cron
15 2 * * *  cd ~/skillbridge-src && deploy/backup.sh >> /var/log/skillbridge-backup.log 2>&1
```

It dumps the whole `public` schema from Supabase through a throwaway
`pg_dump` container, keeps the last `BACKUP_KEEP_LOCAL` locally, and copies to
S3 when `BACKUP_S3_BUCKET` is set.

**Supabase takes its own backups, and this is still worth having**: the free
tier's retention is short, and those copies live in the same account as the
thing they back up. The failure they do not cover is the project being paused,
deleted, or lost with the account.

**It refuses to keep a dump that does not contain the corpus.** A dump that
ran, produced a plausible file and holds no job descriptions is the failure
worth catching, and it is otherwise silent. The check counts rows rather than
looking for the table, because an empty table dumps with a header too.

Verified 2026-09-20 against the development database: dump → restore into a
throwaway container → 1,500 rows, 1,500 embeddings, 384 dimensions.

To restore:

```bash
gzip -dc deploy/backups/skillbridge-<stamp>.sql.gz | scripts/db/pg.sh psql -q -v ON_ERROR_STOP=1
```

On the host, add `sudo` and `--app-env /opt/skillbridge/app.env` after
`pg.sh` to restore into the database the app uses.

The dump is `--clean --if-exists`, so it drops what it replaces and needs no
manual preparation — which is the state you are in when you need it.

---

## When something is wrong

| Symptom | Where to look |
|---|---|
| The site loads but every API call fails | The instance is off, or Supabase is paused. `deploy/instance.sh status` |
| `skillbridge-deploy` says the backend never got healthy | Almost always Supabase paused or a wrong connection string in `app.env` |
| Backend log says `Network is unreachable` or `UnknownHost` for `db.<ref>.supabase.co` | `app.env` names the direct host, which is IPv6-only. Use the session pooler — `app.env.example`, `DATABASE_URL` |
| `FATAL: Tenant or user not found` | The pooler wants the user `postgres.<project-ref>`, not `postgres`, or the host is the other `aws-N` cluster. Copy both from Connect → Session pooler |
| Certificate error | `docker compose -f /opt/skillbridge/compose.yml logs caddy`. Port 80 must be reachable and the DNS record current: `systemctl start skillbridge-dns` |
| API calls return HTML | The `vercel.json` rewrite order. `./deploy/vercel.test.sh` |
| Logged out after ~15 minutes | The refresh cookie is not first-party — the rewrite is missing or bypassed. `VERCEL.md` |
| Reports say "not analysed yet" | `docker compose exec ai wget -qO- localhost:8000/health` — 503 names which half is down |
| Reports empty for everyone | The corpus. `select count(*) from industry_job_descriptions` |
| Events piling up | `select status, count(*) from outbox_events group by status`. RabbitMQ is the usual answer; the rows are safe |
| Anything dead-lettered | `docs/RUNBOOK_DLQ.md` |

Actuator is **not** exposed — `/actuator/*` returns 404 from the internet. Read
it on the host:

```bash
docker compose -f /opt/skillbridge/compose.yml exec backend \
  wget -qO- localhost:8080/actuator/prometheus
```

Mailpit and the RabbitMQ UI are not published either. Forward them over SSH.

---

## What this deliberately does not do

Worth knowing before somebody asks.

- **No automated deploy to the host.** CI builds, tests and publishes; putting
  it on the host is one SSH command. Automating a push-to-deploy against a box
  that is switched off most of the time would be machinery for its own sake.
- **No second instance, no load balancer, no autoscaling.** One replica of
  each service. The outbox relay claims work with a lease
  (`OutboxStore.claimDue`), so a second backend would not double-publish — but
  nothing has ever run two, so that is a design property, not a tested one.
  Say it that way.
- **No zero-downtime deploys.** `skillbridge-deploy` restarts what changed. The
  demo is not running while it does.
- **Migrations run on startup**, not as a separate step. Flyway holds a lock so
  two backends could not race — again untested, because there is only one. A
  deployment with users would run migrations as their own job before the new
  version starts, following expand/contract so old and new schemas overlap.
- **No log aggregation, no metrics scraping, no alerting.** The Prometheus
  rules in `ops/prometheus/` are tested with `promtool` and loaded nowhere.
- **Backups are not restore-tested on a schedule.** Once, by hand, on
  2026-09-20. An untested backup is a hope.
- **Nothing here has ever run against a real AWS account.** There is no AWS CLI
  on the development machine. `deploy/instance.sh` is tested against a stubbed
  CLI and `cost-guardrails.yaml` against a YAML parser. The first real `aws`
  command is the first real test of both.
