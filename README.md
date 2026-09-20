# RoyalVotes

A vote receiver and a vote-reward plugin in one jar. RoyalVotes speaks the Votifier protocols
itself, so there is nothing else to install — and it holds votes for offline players until they
join, which a bare Votifier does not.

Requires **Paper 26.2 or newer** and Java 21+.

> **RoyalVotes replaces Votifier / NuVotifier — do not install them together.** Moving over? Copy
> the old plugin's `rsa` folder into `plugins/RoyalVotes/` and every vote site keeps working
> without touching its settings.

## What it does

- **Receives votes** over Votifier v1 (RSA) and v2 (token), and fires the standard
  `VotifierEvent`, so any plugin that listens for votes works with it.
- **Offline vote queue.** A vote for someone who is offline is stored and paid out when they next
  join — and the vote event is fired *then*, so plugins that ignore votes for offline players
  still count it.
- **Rewards** per vote, per vote site, and chance-based bonuses: console commands, messages and
  broadcasts, with PlaceholderAPI support.
- **Streaks and milestones**, each at exact counts (`7`) or repeating (`every-7`).
- **Vote parties** — a server-wide counter that rewards everyone online when it fills.
- `/vote` with clickable links, `/votetop`, a join reminder, and placeholders.

## EcoBattlepass and the rest of the eco suite

libreforge ships a `register_vote` trigger and a `vote_service` filter, which is what
EcoBattlepass's `vote_server` task uses. libreforge only switches them on when a plugin *named*
"Votifier" is installed, so without one the task fails to load.

RoyalVotes switches that integration on itself at startup. You should see:

```
[RoyalVotes] Enabled libreforge's register_vote trigger — EcoBattlepass and other eco plugins can now react to votes.
```

`/royalvotes status` shows whether it worked. Nothing needs changing in your eco configs:

```yaml
# plugins/EcoBattlepass/tasks/vote_server.yml — as shipped
xp-gain-methods:
  - trigger: register_vote
```

Because queued votes are replayed on join, a player who votes from their phone before logging in
still gets battlepass progress — with plain Votifier that vote is silently lost.

## Setup

1. Drop the jar in `plugins/`, start the server once.
2. Open the listener port (`8192` by default) in your firewall or host panel.
3. On each vote site enter your server IP, the port, and either
   - the contents of `plugins/RoyalVotes/rsa/public.key` (most sites — Votifier v1), or
   - the token from `listener.tokens.default` in `config.yml` (sites offering "NuVotifier v2 / token").
4. Put your real links under `links.sites` and your rewards under `rewards`.
5. Test without a vote site: `/royalvotes fakevote <player>`. It takes the same path as a real
   vote — queue, rewards, vote event and all.

If a site's test vote does not arrive, set `settings.debug: true`; every rejected connection is
logged with the reason (wrong key, wrong token, unknown service).

## Commands

| Command | Permission | |
| --- | --- | --- |
| `/vote` | — | Vote links. |
| `/votetop [page]` | — | Most votes. |
| `/royalvotes reload` | `royalvotes.admin` | Reload config and restart the listener. |
| `/royalvotes status` | `royalvotes.admin` | Listener, libreforge hook and party state. |
| `/royalvotes fakevote <player> [service]` | `royalvotes.admin` | Send a test vote. |
| `/royalvotes stats <player>` | `royalvotes.admin` | A player's totals and queued votes. |
| `/royalvotes party <start\|set <n>>` | `royalvotes.admin` | Start a party or set the counter. |

## Placeholders

`%royalvotes_total%` · `%royalvotes_streak%` · `%royalvotes_best_streak%` ·
`%royalvotes_voted_today%` · `%royalvotes_pending%` · `%royalvotes_party_current%` ·
`%royalvotes_party_required%` · `%royalvotes_party_remaining%` · `%royalvotes_top_name_<n>%` ·
`%royalvotes_top_votes_<n>%`

## For developers

Listen for `com.vexsoftware.votifier.model.VotifierEvent` exactly as you would with Votifier. It
is fired on the main thread and only while the voter is online. The `com.vexsoftware` classes in
this jar are an independent implementation of that public surface, provided for compatibility.

## Building

```bash
mvn -B package
```

The jar is `target/RoyalVotes.jar`.
