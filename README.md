# Neon Coast

**ink music 2.1.26, restyled as a Miami-at-night crime-drama soundtrack app.** It has neon pink and teal on midnight,
every headline lit like a sunset and glowing, new type, frosted blur, VHS scanlines, and new names for the places.
GitHub builds the APK from the **official source at tag `v2.1.26`**, with this repo's `theme/` folder laid over it.
Unofficial, and not made by or affiliated with ink, or with any game studio.

## What changes

| | ink music 2.1.26 | Neon Coast |
|---|---|---|
| Background | black | midnight `#0F0820`, with VHS scanlines and a pink glow low on the screen |
| Headlines | warm white, Archivo Black | **Anton**, lit with a pink, coral and gold sunset gradient, glowing |
| Small type | Silkscreen (pixel) | **Chakra Petch**, in neon pink |
| Secondary words, lines | white at 70% / 12% | neon teal |
| Top bar | black | a sunset-gradient neon rule under it |
| The blur | a 1.5 dp edge, barely there | frosted glass with grain where content meets the bars |
| Player, blur look | the cover blurred | the cover blurred and tinted dusk, the sunset glowing up from below |
| Places | listen, ink, albums, artists, songs, radio, podcasts, lists | CRUISE, THE SCENE, RECORDS, CREWS, TRACKS, AIRWAVES, TALK RADIO, MIXTAPES |
| Settings | SETTINGS | SAFEHOUSE |
| Welcome | INK MUSIC | NEON COAST, "the soundtrack to your city, after dark" |
| App icon | INK MUSIC on black | a striped sun setting between two palms, teal neon on the water |
| App name / package | ink music / `app.ink.music` | Neon Coast / `app.ink.music.sunset` (installs beside the official app) |
| In-app updates | on | off (they would fetch the official build) |

The features and the layout of each screen are ink music's own. **Last.fm is not included**: it needs the developer's
private API key, which isn't in the public source.

## Get the APK

Every push to the default branch builds it (about 6 minutes) and publishes it under **Releases** as
`neon-coast-2.1.26.apk`. On the phone, open `github.com/thanmayroobanathans/ink-music-sunset`, tap **Releases**,
then the APK, and allow your browser to install unknown apps when asked.

After each build, a second job photographs the app on an Android emulator and saves the screenshots to a **draft**
release, which only people with access to the repo can see.

**Signing:** add the `KEYSTORE_PASSWORD` and `KEYSTORE_B64` secrets (**Settings > Secrets and variables > Actions**),
using the values from `signing-secrets.txt`, which is kept outside the repo. Every build is then signed with the same
key and installs over the last one. Without them, each build gets a throwaway key, so you have to uninstall the old
build before installing a new one.

## If a build fails

Open the failed run in **Actions**, click the red step, and copy the last 30 or so lines. That shows what to fix.
If it's a download problem on GitHub's side, **Re-run all jobs** first.

## What's in here

- `.github/workflows/build.yml`: gets ink music `v2.1.26`, copies `theme/` over it, and runs
  `./gradlew -PphoneOnly :app:assembleFullRelease`. Then the emulator screenshots.
- `.github/screens.sh`: the emulator walk-through.
- `theme/`: the changed files, at the same paths as in ink music's source.
- `neon-coast-theme.patch`: the same changes as a diff against `v2.1.26`.
- `fonts/`: the licences of the two fonts (SIL Open Font License 1.1).

## Licence

ink music is free software under the GNU GPL v3 (see `LICENSE`). The files in `theme/` are modified versions of
ink music's files and are under the same licence. The fonts Anton and Chakra Petch are under the SIL Open Font
License 1.1 (see `fonts/`). The complete source of any APK this repo builds is ink music at tag `v2.1.26`
(<https://github.com/inkappdev/ink-music>) plus this repo's `theme/` folder.
