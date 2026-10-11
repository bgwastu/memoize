import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// Uses an isolated browser profile, localhost and synthetic native fixtures.
// No Google account, meeting, invitation or saved note is accessed.
const directory = path.resolve(process.argv[2] ?? '.build/presentation-browser-smoke');
const { chromium } = await import(pathToFileURL(path.resolve('.build/presentation-browser-test/node_modules/playwright/index.mjs')));
await mkdir(directory, { recursive: true });
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const state = async () => JSON.parse(await readFile(path.join(directory, 'browser-state.json'), 'utf8'));
async function command(value) {
  await writeFile(path.join(directory, 'browser-command.txt'), value);
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    if ((await state()).command === value) return await state();
    await delay(100);
  }
  throw new Error(`Native fixture did not acknowledge ${value}.`);
}
const server = createServer((request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/html' });
  response.end('<!doctype html><title>Memoize capture diagnostic</title><button id="share">Share test window</button><video id="received" autoplay muted playsinline></video>');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  const initial = await state();
  if (!initial.presenting || initial.shared) throw new Error('Native fixture must start private and presenting.');
  browser = await chromium.launch({
    executablePath: process.env.MEMOIZE_TEST_BROWSER ?? '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser',
    headless: process.env.MEMOIZE_TEST_HEADED !== '1',
    args: [
      `--auto-select-window-capture-source-by-title=${initial.windowTitle}`,
      '--enable-usermedia-screen-capturing',
      '--autoplay-policy=no-user-gesture-required',
      '--window-position=0,70', '--window-size=500,650',
    ],
  });
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.evaluate(() => {
    document.querySelector('#share').addEventListener('click', async () => {
      try {
        const capture = await navigator.mediaDevices.getDisplayMedia({ video: { displaySurface: 'window' }, audio: false });
        const sender = new RTCPeerConnection({ iceServers: [] });
        const receiver = new RTCPeerConnection({ iceServers: [] });
        const pendingSender = [], pendingReceiver = [];
        sender.onicecandidate = event => {
          if (!event.candidate) return;
          if (receiver.remoteDescription) receiver.addIceCandidate(event.candidate);
          else pendingReceiver.push(event.candidate);
        };
        receiver.onicecandidate = event => {
          if (!event.candidate) return;
          if (sender.remoteDescription) sender.addIceCandidate(event.candidate);
          else pendingSender.push(event.candidate);
        };
        receiver.ontrack = event => { document.querySelector('#received').srcObject = event.streams[0]; };
        for (const track of capture.getTracks()) sender.addTrack(track, capture);
        await sender.setLocalDescription(await sender.createOffer());
        await receiver.setRemoteDescription(sender.localDescription);
        for (const candidate of pendingReceiver) await receiver.addIceCandidate(candidate);
        await receiver.setLocalDescription(await receiver.createAnswer());
        await sender.setRemoteDescription(receiver.localDescription);
        for (const candidate of pendingSender) await sender.addIceCandidate(candidate);
        await document.querySelector('#received').play();
        window.diagnostic = { capture, sender, receiver, settings: capture.getVideoTracks()[0].getSettings() };
      } catch (error) { window.captureError = `${error.name}: ${error.message}`; }
    });
  });
  await page.locator('#share').click();
  await page.waitForFunction(() => window.captureError || (window.diagnostic && document.querySelector('#received').videoWidth > 0), undefined, { timeout: 15000 });
  const error = await page.evaluate(() => window.captureError);
  if (error) throw new Error(error);
  const settings = await page.evaluate(() => window.diagnostic.settings);
  if (settings.displaySurface !== 'window') throw new Error(`Captured ${settings.displaySurface}, expected a window.`);
  console.log(`Browser ${browser.version()}; real window capture ${settings.width}×${settings.height}, WebRTC receiver connected.`);
  async function samples() {
    return await page.evaluate(async () => {
      const video = document.querySelector('#received');
      const canvas = document.createElement('canvas');
      canvas.width = video.videoWidth; canvas.height = video.videoHeight;
      const context = canvas.getContext('2d'); context.drawImage(video, 0, 0);
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
      let nonGreen = 0, dark = 0;
      // The shared note is at the right; avoid native title bars and borders.
      for (let y = Math.ceil(canvas.height * 0.10); y < canvas.height * 0.75; y += 2) {
        for (let x = Math.ceil(canvas.width * 0.62); x < canvas.width * 0.95; x += 2) {
          const index = (y * canvas.width + x) * 4;
          const r = pixels[index], g = pixels[index + 1], b = pixels[index + 2];
          if (!(g > r + 45 && g > b + 45)) nonGreen++;
          if (r < 160 && g < 160 && b < 160) dark++;
        }
      }
      let frames = 0;
      for (const report of (await window.diagnostic.receiver.getStats()).values()) {
        if (report.type === 'inbound-rtp' && report.kind === 'video') frames = report.framesDecoded;
      }
      return { nonGreen, dark, frames };
    });
  }
  async function check(name, predicate) {
    const deadline = Date.now() + 8000;
    let value;
    while (Date.now() < deadline) {
      value = await samples();
      if (predicate(value)) { console.log(`${name}: ${JSON.stringify(value)}`); return value; }
      await delay(150);
    }
    // The native fixture is synthetic and the desktop is covered by the green
    // diagnostic process. Save the received window, never the physical screen.
    const fixture = await page.evaluate(() => {
      const video = document.querySelector('#received');
      const canvas = document.createElement('canvas');
      canvas.width = video.videoWidth; canvas.height = video.videoHeight;
      canvas.getContext('2d').drawImage(video, 0, 0);
      return canvas.toDataURL('image/png').split(',')[1];
    });
    await writeFile(path.join(directory, 'browser-receiver-failure.png'), Buffer.from(fixture, 'base64'));
    throw new Error(`${name} failed: ${JSON.stringify(value)}`);
  }
  await check('Private note omitted at receiver', value => value.nonGreen < 20 && value.frames > 0);
  await command('verifyLocalCapture');
  console.log('Independent local-window capture passed while Brave sharing is active.');
  await command('share');
  const shared = await check('Shared note rendered at receiver', value => value.nonGreen > 500 && value.dark > 50);
  await command('edit');
  await check('Editing updates the shared note', value => value.dark > shared.dark * 1.25 && value.frames > shared.frames);
  await command('resize');
  const resized = await check('Resizing updates the shared note', value => value.nonGreen > shared.nonGreen * 1.15);
  const hidden = await command('hideLocal');
  if (hidden.localVisible || !hidden.presenting) throw new Error('Hiding the local note also hid the presentation.');
  await check('Local hide preserves presentation', value => value.nonGreen > 500 && value.frames >= resized.frames);
  const privateState = await command('private');
  if (privateState.shared) throw new Error('Native fixture did not remove the note overlay.');
  await check('Hiding clears a live receiver on an idle desktop', value => value.nonGreen < 20 && value.frames > resized.frames);
  const shown = await command('showLocal');
  if (!shown.localVisible || shown.shared) throw new Error('Restoring the local note changed audience visibility.');
  await check('Local restore remains private', value => value.nonGreen < 20);
  await command('finish');
  console.log('PASS: real browser getDisplayMedia → WebRTC receiver, private/shared toggles, editing, resizing, local hide/restore and native shutdown. Google Meet itself was not joined.');
} finally {
  await writeFile(path.join(directory, 'browser-command.txt'), 'finish');
  if (browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
