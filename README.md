# ink music editions: Neon Coast and Vigil

Two restyled editions of **ink music 2.1.26**. GitHub builds each one from the **official source at tag `v2.1.26`**,
with that edition's folder from `themes/` laid over it. They install side by side, and beside the official app.
Unofficial, and not made by or affiliated with ink, or with any game or film studio.

| | **Neon Coast** | **Vigil** |
|---|---|---|
| Mood | Miami at night | a rain-soaked city after midnight |
| APK / package | `neon-coast-2.1.26.apk` / `app.ink.music.sunset` | `vigil-2.1.26.apk` / `app.ink.music.vigil` |
| Background | midnight `#0F0820` | cold black `#08090C` |
| Headlines | **Anton**, lit with a pink, coral and gold sunset gradient, glowing | **Bebas Neue**, bone white with a steel sheen and a hard shadow |
| Small type | **Chakra Petch**, neon pink | **Courier Prime** (typewriter), bone white |
| Secondary, lines | neon teal | sodium-streetlight amber, wet steel |
| Over everything | VHS scanlines, a pink glow low on the screen | rain on the glass, the dark closing in at the edges |
| Under the top bar | a sunset-gradient neon rule | a streetlight's amber line |
| Player, blur look | tinted dusk, the sunset glowing up | gone cold, darkest at the foot |
| Places | CRUISE, THE SCENE, RECORDS, CREWS, TRACKS, AIRWAVES, TALK RADIO, MIXTAPES | THE BEAT, THE CITY, CASE FILES, FACES, TRACKS, NIGHT RADIO, TALK, DOSSIERS |
| Settings | SAFEHOUSE | HIDEOUT |
| Welcome | NEON COAST, "the soundtrack to your city, after dark" | VIGIL, NIGHT SHIFT, "music for the long night" |
| Icon | a striped sun setting between palms | a pale moon over a rainy city, lit windows |

Both editions have frosted blur where content meets the bars, and in-app updates are off (they would fetch the
official build). The features and the layout of each screen are ink music's own. **Last.fm is not included**: it
needs the developer's private API key, which isn't in the public source.

## Get the APKs

Every push to the default branch builds both editions (about 6 minutes) and publishes each under **Releases**.
On the phone, open `github.com/thanmayroobanathans/ink-music-sunset`, tap **Releases**, then the APK, and allow
your browser to install unknown apps when asked.

After each build, a second job photographs each app on an Android emulator and saves the screenshots to **draft**
releases, which only people with access to the repo can see.

**Signing:** add the `KEYSTORE_PASSWORD` and `KEYSTORE_B64` secrets (**Settings > Secrets and variables > Actions**),
using the values from `signing-secrets.txt`, which is kept outside the repo. Every build is then signed with the same
key and installs over the last one. Without them, each build gets a throwaway key, so you have to uninstall the old
build before installing a new one.

## If a build fails

Open the failed run in **Actions**, click the red step, and copy the last 30 or so lines. That shows what to fix.
If it's a download problem on GitHub's side, **Re-run all jobs** first.

## What's in here

- `.github/workflows/build.yml`: for each edition, gets ink music `v2.1.26`, copies `themes/<edition>/` over it,
  and runs `./gradlew -PphoneOnly :app:assembleFullRelease`. Then the emulator screenshots.
- `.github/screens.sh`: the emulator walk-through.
- `themes/neon-coast/`, `themes/vigil/`: each edition's changed files, at the same paths as in ink music's source.
- `patches/`: the same changes as diffs against `v2.1.26`.
- `fonts/`: the licences of the four fonts (SIL Open Font License 1.1).

## Licence

ink music is free software under the GNU GPL v3 (see `LICENSE`). The files in `themes/` are modified versions of
ink music's files and are under the same licence. The fonts Anton, Chakra Petch, Bebas Neue and Courier Prime are
under the SIL Open Font License 1.1 (see `fonts/`). The complete source of any APK this repo builds is ink music at
tag `v2.1.26` (<https://github.com/inkappdev/ink-music>) plus that edition's folder in `themes/`.
