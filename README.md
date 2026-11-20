# Lynxos Retro

Play classic games on your tablet and Google TV. Lynxos Retro is a
single-page web app wrapped in an Android WebView. It embeds
[EmulatorJS](https://emulatorjs.org/) (RetroArch cores compiled to
WebAssembly) and plays games right in the app — no separate emulator
to install.

## Systems in v1

NES, SNES, Game Boy, Game Boy Color, Game Boy Advance, Sega Genesis,
Sega Master System, Game Gear, Atari 2600, Nintendo 64, Nintendo DS.

PSP and PlayStation are NOT in v1: PSP needs SharedArrayBuffer and
cross-origin isolation headers, which a `file://` WebView page cannot
provide; PlayStation needs a copyrighted BIOS file and CD images that
are too big for the browser and over Supabase's 50 MB per-file limit.

## Games included — legal only

The 12 games bundled in `assets/roms/` are all free homebrew or
public-domain games, released free by their authors. No commercial
games are included, and the app does not download copyrighted ROMs.

- NES: Thwaite, Concentration Room, Nova the Squirrel
- Game Boy: Libbet and the Magic Floor
- Game Boy Color: uCity
- Game Boy Advance: CASCADE7
- SNES: Super Boss Gaiden
- Master System: Platform Explorer
- Genesis: genmddj — this is a Mega Drive **music tracker, not a game**,
  and the app labels it honestly as one.
- Atari 2600: FIREFLY
- Nintendo 64: Pong by Oman (public domain)
- Nintendo DS: Negative Space

Game Gear has no bundled game yet (no verified free homebrew with a
direct download was found) — the tab is there, and you can add your
own Game Gear files.

## Adding your own games

Add Game takes a ROM link or a file picked from the device. Files you
pick are played from the device; links are played from the link.

## Cloud sync (Supabase)

Sign in with email + password (Account screen) and your library syncs
between tablet and TV: a game added on the tablet shows up on the TV.

- New project "Lynxos Retro" in Supabase (free tier, no card).
- Run the SQL in `supabase-setup.sql` in the SQL Editor: creates the
  `game_library` table with Row Level Security (each user only sees
  their own rows) and the private `roms` storage bucket.
- Copy the project URL + **anon** key into `supabase-config.js`.
  Never put the service-role key in the app.
- Turn OFF "Confirm email" (Authentication -> Providers -> Email) so
  sign-up signs straight in.
- Free projects pause after 1 week of inactivity — set up the 48-hour
  keep-alive the same way Metalhead Social does.
- Until the config is filled in, the app shows "Cloud sync is not set
  up yet" and works fully offline.

## Controls

- Tablet: the on-screen gamepad EmulatorJS shows.
- TV: remote D-pad = game D-pad, OK/Enter = Start. A Bluetooth
  gamepad is the best option on both.
- Quick save = 1, quick load = 2, fast forward = +.

## First device test — what to check

1. The library opens and TalkBack reads every game card.
2. A bundled NES game starts and plays (this proves the bundled
   EmulatorJS files load from the APK and the ROM fetch works).
3. N64 and DS are the heaviest cores — check them on the tablet
   before promising smooth play.
4. Sign in, add a game on the tablet, check it appears on the TV.

## Build

GitHub Actions workflow `.github/workflows/build-apk.yml` (same WebView
wrapper pattern as the other Lynxos apps). Package
`com.lynxosretro.app`, artifact `lynxos-retro-apk`, signed with the
Lynxos key from the repo secrets. Run it from the Actions tab with
the version name/code, or use the defaults (1.0.0 / 1).
