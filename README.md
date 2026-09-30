# Pumpkin Watch

A cozy Halloween defense game for 2–8 players. A Java server runs the shared farm; each player joins in their browser. Includes solo practice, original pixel artwork, keyboard/touch controls, and five waves ending with the Skeleton King.

## Run in IntelliJ IDEA

1. Open `C:\Users\antho\IdeaProjects\Pumpkin Watch` (with the space).
2. Use JDK 21 or newer as the Project SDK. Your Corretto 23 is suitable. Load `pom.xml` as a Maven project if prompted.
3. Open `src/main/java/watch/PumpkinWatch.java`. Click the green Run triangle beside `main`.
4. Open **http://localhost:3000** in your browser.

If port 3000 is already in use, another copy is running. Use the existing page, stop that copy, or set `PORT=3001` in your Run configuration and open that port instead.

Alternatively, use PowerShell from this folder:

```powershell
.\run.ps1
```

The script compiles Java and copies web resources. There are no third-party runtime dependencies; Node.js is not needed to play.

## Friends on their own devices

Everyone should be on the same Wi-Fi/LAN. The Run console prints `Same Wi-Fi: http://...:3000`. Share the address for your real network adapter, rather than a VPN or virtual adapter. Phones must use that address, not `localhost`.

If Windows asks about Java network access, allow your private network. Guest Wi-Fi with client isolation may prevent devices connecting. One player creates a patch; friends open the server address and enter its five-character room code. Everyone readies up; the host starts.

This first version uses local-network play. Internet hosting is not configured. Rooms live in memory and reset when the server stops. Abandoned rooms expire after 30 minutes. Reloading the same tab resumes your player. Disconnected lobby seats are held for one minute. The simulation pauses when everyone disconnects.

## Controls and rules

- WASD / arrows to move; click/tap the ground to walk there.
- Click/tap a marked plot to walk near it and select it. Choose a defense card or use keys 1–4 to plant.
- Hold E or the repair button near damaged defenses. Repairs cost time, not seeds.
- Upgrade a selected defense to increase damage/health and restore its health. Level cap: 10 per night.
- Collect glowing seeds for the shared bank. Small garden beds around the farmhouse and moonflowers generate more.
- Skeletons stop to attack nearby players: distract them, but keep moving. At zero health, return after five seconds as a ghost.

Start with 60 seconds to prepare. Survive five 90-second waves, clearing remaining enemies after each timer ends. Between waves, vote for a charm during a 20-second break. Majority wins; ties are random among tied choices. Minimize the vote panel to keep gathering and repairing; reopen it with **Choose harvest charm**.

The farmer starts with 100% sleep. Skeletons reaching the house cost 5 points; armored skeletons cost 9. At zero you lose. The King reaching the house wakes the farmer immediately. Defeat him and clear the final wave to win.

Score: 100 per enemy, 500 per wave, 2,000 additional for the King, plus 100 per remaining sleep percentage on victory. Results celebrate repairs and seeds collected. The host can start a fresh run afterward.

## Circular defense

The farmhouse sits in the center of a circular clearing. Skeletons spawn around the entire perimeter and walk inward. Twelve planting plots form two rings. Brambles block enemies within their local area; there are no fixed lanes.

Cannons face away from the farmhouse and launch nine projectiles across a 180-degree semicircle. Select an empty plot or cannon to preview its firing arc. Shots travel at a fixed speed and stop at the first skeleton they hit. One skeleton takes damage at most once per volley; a spray can hit several different enemies. Big Harvest turns every fifth volley into boosted splash projectiles. Lanterns can attack in any direction.

Starting seeds are now 160 + 30 per player to help cover the wider perimeter. Existing wave/team scaling remains in place; the new layout needs group balance playtesting.

## Growing the numbers

Six stackable charms cover lantern adjacency, repair-charged attacks, fifth-shot cannon splash, moonflower income, brambles, and multiplicative damage. Wave health grows exponentially and spawn cadence rises; both account for the starting team size. Scaling does not secretly increase in response to a successful build.

Scores use Java BigInteger, travel as decimal strings, and display compactly when very large. Combat uses doubles and upgrades currently have a per-night cap. Endless nights, huge-number combat arithmetic, deeper combinations and extensive balance tuning are future work.

## Verification

Run `./run.ps1 -Test` in PowerShell, or run `src/test/java/watch/GameTest.java` in IntelliJ. The dependency-free suite checks simulation rules and real HTTP/event-stream clients, including eight players and simultaneous construction. It uses an ephemeral port and shuts itself down.

Optional `scripts/browser-smoke.cjs` requires Playwright and an installed Edge browser (set `PW_CHANNEL=chrome` for Chrome). Start the server on port 3000, then run the script with Node. It checks desktop/mobile layouts, two-player joining, readiness, shared building/upgrading, safe names, reload reconnect, and help. Screenshots are saved under ignored `test-results/`.

The first build has automated checks and browser verification. Difficulty needs real group playtesting. Mobile layout has been emulated; a physical phone connection has not been verified.

## Code map

- `Game.java`: authoritative simulation, balance and charms.
- `PumpkinWatch.java`: rooms, sessions, HTTP and state streams.
- `Json.java`: JSON codec.
- `public/app.js`: networking, controls, UI and sound effects.
- `public/art.js`: original pixel sprites and canvas renderer.
- `public/style.css` / `public/index.html`: responsive interface.

The server ticks at 20 Hz and broadcasts at 10 Hz. Browsers send intent; the server owns movement, spending, combat, voting and scoring. Mutations are synchronized per room.
