# ink sunset

**ink music 2.1.26 with a neon-sunset theme**: night-violet background, sunset-pink type, and a striped sun setting
between two palms as the app icon. GitHub builds the APK from the **official source at tag `v2.1.26`**, with this
repo's `theme/` folder laid over it. Nothing else is changed. Unofficial, and not made by or affiliated with ink.

## What's different from the official app

| | official 2.1.26 | ink sunset |
|---|---|---|
| Background | black `#000000` | night violet `#170A29` |
| Type and lines (the "ink") | warm white `#F1F0EC` | sunset pink `#FFA6D2` |
| Raised panels | `#141414` | dusk violet `#2A1442` |
| Heart / gold heart | `#E8302A` / `#E8B830` | neon red `#FF3D6E` / sunset gold `#FFB938` |
| Dither prints, taste map, radio art | dots on black | dots on night violet |
| Home-screen widget, launch screen | black | night violet and pink |
| App icon | INK MUSIC on black | striped sun, sea and palms on a dusk sky |
| App name / package | ink music / `app.ink.music` | ink sunset / `app.ink.music.sunset` (installs beside the official app) |
| In-app updates | on | off (they would fetch the official build) |

The same two fonts, layouts and features. **Last.fm is not included**: it needs the developer's private API key,
which isn't in the public source. Video keeps a true-black backdrop.

## Build it (about 5 minutes of clicking, then 20 to 30 minutes of waiting)

On your PC, signed in to GitHub:

1. **Make a new repository** at <https://github.com/new>. Name it `ink-music-sunset`, set it to **Public**
   (Private works too, but you'll need to be signed in to GitHub on your phone to download), and leave
   "Add a README" unticked. Click **Create repository**.
2. **(Recommended) Add the signing key**, so later builds can update this one without an uninstall:
   **Settings > Secrets and variables > Actions > New repository secret**, twice, using the values in
   `signing-secrets.txt` (sent separately, **not** part of this folder):
   - Name `KEYSTORE_PASSWORD`, value: the password line
   - Name `KEYSTORE_B64`, value: the long line (paste all of it)

   Skip this step and the build still works. It's then signed with a throwaway key, so each new build
   has to be uninstalled before the next one goes on.
3. **Upload this folder**: on the repo's main page click **uploading an existing file**. Open the unzipped
   `ink-music-sunset` folder in File Explorer, select **everything inside it** (`.github`, `theme`, `README.md`,
   `LICENSE`, `sunset-theme.patch`) and drag it onto the page. Wait for the list to finish, then **Commit changes**.
   Check that `.github/workflows/build.yml` appears in the repo afterwards.
4. **Wait for the build**: open the **Actions** tab. "Build ink sunset" starts by itself. A green tick means
   it's done. If nothing started, click **Build ink sunset > Run workflow**.

## Install it on your phone

1. On the phone, open `github.com/thanmayroobanathans/ink-music-sunset`, tap **Releases**, then
   **ink-sunset-2.1.26.apk**.
2. Open the download. If Android asks, allow your browser to **install unknown apps**, then tap **Install**.
3. It shows up as **ink sunset** next to the official ink music, if you have that. They don't share a library,
   so you'll set it up fresh.

## If the build fails

Open the failed run in **Actions**, click the red step, and copy the last 30 or so lines. That shows exactly what to
fix. The usual cause is a temporary download problem on GitHub's side, so first try **Re-run all jobs**.

## What's in here

- `.github/workflows/build.yml`: the build. It gets ink music `v2.1.26`, copies `theme/` over it, then runs
  `./gradlew -PphoneOnly :app:assembleFullRelease`.
- `theme/`: the changed files, at the same paths as in ink music's source.
- `sunset-theme.patch`: the same changes as a diff against `v2.1.26`, to read or to `git apply`.
- `theme/tools/make_sunset_icon.py`: makes the icon's drawables.

## Licence

ink music is free software under the GNU GPL v3 (see `LICENSE`). The files in `theme/` are modified versions
of ink music's files and are under the same licence. The complete source of any APK this repo builds is ink music
at tag `v2.1.26` (<https://github.com/inkappdev/ink-music>) plus this repo's `theme/` folder.
