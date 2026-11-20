"""Neon Coast's web app tried as a person on a phone would: in Safari's engine (WebKit) as an iPhone 15, and in
Chrome's as an Android phone (whose builds play AAC and MP3 streams for certain). The stations come, a search finds
some, a station plays, a show's episodes come and one plays, a song from the phone is added, plays, and is still
there after a reload. Pictures of each step (WebKit's) go to shots/, what happened to shots/results.json.
Run beside a server of site/ on localhost:8000."""
import asyncio, json, os, subprocess, sys

BASE = 'http://localhost:8000/app/'
OUT = os.path.abspath('shots')
results = {}


def song():
    """A song as a phone's files have one: an mp3 with its title, artist, album and cover in its ID3 tag."""
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=0xFF6FB5:s=300x300', '-frames:v', '1', 'cover.png'], check=True)
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'sine=frequency=330:duration=30', '-i', 'cover.png',
                    '-map', '0:a', '-map', '1:v', '-c:a', 'libmp3lame', '-b:a', '128k', '-c:v', 'mjpeg', '-id3v2_version', '3',
                    '-metadata', 'title=Night Drive', '-metadata', 'artist=Neon Coast', '-metadata', 'album=After Dark',
                    '-metadata:s:v', 'comment=Cover (front)', 'Night_Drive.mp3'], check=True)
    return os.path.abspath('Night_Drive.mp3')


async def sound(page, wait=10):
    """Whether the player is playing, a few seconds on: paused, how far in, how ready, any error."""
    await page.wait_for_timeout(wait * 1000)
    return await page.evaluate("""() => { const a = document.querySelector('audio');
        return { playing: !a.paused, at: Math.round(a.currentTime * 10) / 10, ready: a.readyState, error: a.error && a.error.code,
                 said: document.querySelector('#player-said').textContent, title: document.querySelector('#mini-title').textContent } }""")


async def flow(p, kind, mp3):
    r = results[kind] = {}
    b = await getattr(p, kind).launch()
    ctx = await b.new_context(**(p.devices['iPhone 15'] if kind == 'webkit' else p.devices['Pixel 7']))
    page = await ctx.new_page()
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)[:200]))
    shoot = kind == 'webkit'

    async def step(name, f):
        try:
            r[name] = await f()
        except Exception as e:
            r[name] = f'failed: {str(e).splitlines()[0][:200]}'
        print(kind, name, r[name], flush=True)

    async def shot(n):
        if shoot:
            await page.screenshot(path=f'{OUT}/{n}.png')

    async def stations():
        await page.goto(BASE)
        await page.wait_for_selector('#ink-radio .row', timeout=20000)
        await page.wait_for_timeout(800)
        await shot('web-01-airwaves')
        return await page.locator('#ink-radio .row').count()
    await step('ink radio stations', stations)

    async def india():
        await page.wait_for_function("document.querySelectorAll('#india .row').length > 0 || /answer/.test(document.querySelector('#india').textContent)", timeout=25000)
        return await page.locator('#india .row').count()
    await step('stations from india', india)

    async def search():
        await page.fill('#radio-q', 'lofi')
        await page.wait_for_selector('#radio-found .row', timeout=20000)
        await shot('web-02-radio-search')
        n = await page.locator('#radio-found .row').count()
        await page.fill('#radio-q', '')
        return n
    await step('radio search "lofi"', search)

    async def station():
        await page.click('.row[data-key="ink:wfmu"]')
        s = await sound(page, 12)
        await shot('web-03-station-playing')
        await page.click('#mini-open')
        await page.wait_for_timeout(600)
        await shot('web-04-player')
        await page.click('#player-close')
        return s
    await step('a station plays (WFMU, mp3)', station)

    async def talk():
        await page.click('.places a[data-place="talk"]')
        await page.wait_for_function("document.querySelectorAll('#top-shows .row').length > 0 || /answer/.test(document.querySelector('#top-shows').textContent)", timeout=25000)
        await shot('web-05-talk')
        return await page.locator('#top-shows .row').count()
    await step('top shows in india', talk)

    async def show():
        await page.fill('#talk-q', 'the daily')
        await page.wait_for_selector('#talk-found .row', timeout=20000)
        await page.locator('#talk-found .row').first.click()
        await page.wait_for_selector('#show .row', timeout=25000)
        await page.wait_for_timeout(800)
        await shot('web-06-show')
        n = await page.locator('#show .row').count()
        await page.locator('#show .row').first.click()
        s = await sound(page, 12)
        return {'episodes': n, 'first plays': s}
    await step('a show and an episode ("the daily")', show)

    async def tracks():
        await page.click('.places a[data-place="tracks"]')
        await page.set_input_files('#add-files', mp3)
        await page.wait_for_selector('#track-list .row', timeout=15000)
        await page.wait_for_timeout(1500)
        await shot('web-07-tracks')
        name = await page.locator('#track-list .row .name').first.text_content()
        sub = await page.locator('#track-list .row .sub').first.text_content()
        cover = await page.locator('#track-list .row img').first.get_attribute('src')
        await page.locator('#track-list .row').first.click()
        s = await sound(page, 6)
        await page.click('#mini-open')
        await page.wait_for_timeout(600)
        await shot('web-08-song-player')
        await page.click('#player-close')
        return {'name': name, 'by': sub, 'its own cover': (cover or '').startswith('blob:'), 'plays': s}
    await step('a song from the phone', tracks)

    async def kept():
        await page.reload()
        await page.wait_for_selector('#track-list .row', timeout=15000)
        sw = await page.evaluate("navigator.serviceWorker ? navigator.serviceWorker.getRegistration().then(r => !!r) : false")
        return {'songs after a reload': await page.locator('#track-list .row').count(), 'works offline (service worker)': sw}
    await step('kept', kept)
    r['page errors'] = errors
    await b.close()


async def main():
    os.makedirs(OUT, exist_ok=True)
    mp3 = song()
    from playwright.async_api import async_playwright
    async with async_playwright() as p:
        for kind in ('webkit', 'chromium'):
            try:
                await flow(p, kind, mp3)
            except Exception as e:
                results[kind] = f'failed: {e}'
    json.dump(results, open(f'{OUT}/results.json', 'w'), indent=2)
    print(json.dumps(results, indent=2))


asyncio.run(main())
