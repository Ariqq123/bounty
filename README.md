# Bounty

`Bounty` is a Paper plugin for Minecraft servers that lets players place a money bounty on other players. When a valid player kill happens, the killer receives the full reward pool through Vault.

This project currently targets `Paper 1.21.x` and uses `Vault` plus a Vault-compatible economy plugin.

## Features

- Player-funded bounties with escrow payout
- Combined reward pool when multiple players target the same player
- Repeat placements from the same player are merged into one contribution
- Reward is paid automatically on a valid player kill
- Player GUI for browsing bounty targets, top bounties, personal contributions, and placing bounties
- SQLite storage for active bounties, claim history, and anti-abuse cooldown tracking
- Admin commands for adding, removing, refunding, and inspecting bounty history
- Optional Discord webhook embed notifications for bounty activity

## Requirements

- Java 21
- Paper `1.21.x`
- Vault
- A Vault-compatible economy plugin such as EssentialsX Economy, CMI Economy, or another supported provider

## Gameplay Rules

- Players cannot place a bounty on themselves.
- The default minimum bounty is `100`.
- Money is withdrawn immediately when a bounty is placed.
- If a player adds more money to the same target, the amount is merged into their existing contribution.
- The killer receives the full active pool for that target when the kill is valid.
- Bounties are not paid for self-kills, non-player kills, or deaths without a player killer.
- The same killer-target pair is blocked by a cooldown after a claim to reduce farming.
- Players can cancel their own contribution before the target is killed.
- By default, cancelling returns `80%` of the contribution and keeps `20%` as a penalty.

## Installation

1. Build the plugin or download the compiled jar.
2. Put the plugin jar into your server `plugins/` folder.
3. Install `Vault` and a supported economy plugin.
4. Start the server once so the plugin can create its config and SQLite database.
5. Edit `plugins/Bounty/config.yml` if you want to change defaults.
6. Restart the server or use `/bounty reload`.

## Commands

### Player Commands

- `/bounty`
  Opens the main GUI.
- `/bounty place <player> <amount>`
  Place a bounty on a player.
- `/bounty info <player>`
  Show the active bounty total for a player.
- `/bounty list [page]`
  List active bounty targets.
- `/bounty top [limit]`
  Show the highest bounty pools.
- `/bounty my`
  Show your active contributions.
- `/bounty cancel <player>`
  Cancel your own contribution on that target.

### Admin Commands

- `/bounty admin add <player> <amount> [placer]`
  Add a bounty contribution without withdrawing player money.
- `/bounty admin remove <player>`
  Remove only non-refundable contributions for a target. If player-funded money is still active, use `/bounty admin refund`.
- `/bounty admin refund <player> [placer]`
  Refund active contributions back to their original placers.
- `/bounty admin history <player>`
  Show recent claim history for a target.
- `/bounty admin testdiscord`
  Send a Discord webhook test embed immediately.
- `/bounty reload`
  Reload the plugin configuration.

## Permissions

- `bounty.use`
- `bounty.place`
- `bounty.cancel.own`
- `bounty.admin.manage`
- `bounty.admin.reload`
- `bounty.admin.history`

## Configuration

Default configuration:

```yaml
bounty:
  min-amount: 100
  max-amount: 0
  cancel-refund-percent: 80

anti-abuse:
  claim-cooldown-seconds-per-pair: 3600

gui:
  page-size: 28

messages:
  broadcast-place: true
  broadcast-claim: true

discord:
  enabled: false
  webhook-url: ""
  username: "Bounty"
  avatar-url: ""
  embed:
    footer-text: "Bounty"
    show-timestamp: true
    colors:
      place: "#F1C40F"
      claim: "#2ECC71"
      cancel: "#E74C3C"
      admin: "#3498DB"
  events:
    place: true
    claim: true
    cancel: true
    admin: true
```

Notes:

- `max-amount: 0` means no upper limit.
- `claim-cooldown-seconds-per-pair` is the cooldown for the same killer farming the same target repeatedly.
- Bounty data is stored in an SQLite database file created in the plugin data folder.
- Discord integration uses a standard Discord webhook URL, sends embeds asynchronously, and does not block the main server thread.

## Discord Integration

If you want bounty activity in a Discord channel:

1. Create a webhook in your Discord server channel settings.
2. Copy the webhook URL into `discord.webhook-url`.
3. Set `discord.enabled: true`.
4. Optionally customize `discord.username`, `discord.avatar-url`, footer text, timestamp behavior, embed colors, and enabled event toggles.

Supported webhook embed events:

- New player bounty placements
- Bounty claims
- Player bounty cancellations
- Admin add/remove/refund actions
- Manual test embeds via `/bounty admin testdiscord`

Embed color keys:

- `discord.embed.colors.place`
- `discord.embed.colors.claim`
- `discord.embed.colors.cancel`
- `discord.embed.colors.admin`

## GUI Flow

- `/bounty` opens the main menu.
- Players can browse active bounty targets and top bounty pools from the GUI.
- Placing a bounty through the GUI opens a target selector.
- After choosing a target, the plugin asks for the amount in chat.
- Typing `cancel` in chat aborts the placement prompt.

## Building

Build with Gradle:

```bash
./gradlew clean build
```

The compiled jar will be created in:

```text
build/libs/Bounty-1.0.0.jar
```

## Project Layout

- `src/main/java` plugin source
- `src/main/resources/plugin.yml` Paper plugin metadata
- `src/main/resources/config.yml` default configuration
- `src/test/java` service-level tests

## Current Scope

This version focuses on single-server Paper deployment with local SQLite storage. It does not currently include cross-server sync, MySQL support, Discord bot commands, or alt-account detection.
