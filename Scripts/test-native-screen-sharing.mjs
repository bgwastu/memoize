import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
const { chromium } = await import(pathToFileURL(path.resolve('.build/presentation-browser-test/node_modules/playwright/index.mjs')));
import { readFile,writeFile } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
const directory=path.resolve(process.argv[2]);
const settingsUI=process.argv.includes('--settings-ui');
const execute=promisify(execFile);
// Only synthetic notes, a local page, and an isolated browser profile are used.
const state=async()=>JSON.parse(await readFile(path.join(directory,'native-state.json'),'utf8'));
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function fixtureCommand(value){
  await writeFile(path.join(directory,'native-command.txt'),value);
  for(let i=0;i<80;i++){const s=await state();if(s.command===value)return s;await delay(100)}
  throw Error(`Native fixture failed to acknowledge ${value}`);
}
async function command(value){
  if(settingsUI&&['private','shared'].includes(value)){
    const s=await fixtureCommand('settings');
    const result=await execute('/usr/bin/swift',[path.resolve('Scripts/PrivacySettingsToggle.swift'),String(s.processID),value==='private'?'on':'off']);
    console.log(result.stdout.trim());
    return fixtureCommand('closeSettings');
  }
  return fixtureCommand(value);
}
async function status(label,predicate){
  let value;
  for(let i=0;i<80;i++){
    value=await state();
    if(predicate(value)){
      console.log(label+': '+JSON.stringify({private:value.private,captureDetected:value.captureDetected,privacyIndicatorVisible:value.privacyIndicatorVisible}));
      return value;
    }
    await delay(100);
  }
  throw Error(label+' failed: '+JSON.stringify(value));
}
const server=createServer((req,res)=>{
  res.writeHead(200,{'Content-Type':'text/html'});
  res.end('<!doctype html><title>Memoize native full-screen diagnostic</title><style>body{background:lime}video{width:1px;height:1px;opacity:0}</style><button id="share">Share entire screen</button><video autoplay muted playsinline></video>');
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
let browser;
try {
  if(settingsUI)await command('shared');
  await command('private');
  await delay(1000);
  const idle=await status('Idle notes protected without a sharing indicator',s=>s.private&&!s.captureDetected&&!s.privacyIndicatorVisible);
  if(!settingsUI)await command('shared');
  browser=await chromium.launch({executablePath:process.env.MEMOIZE_TEST_BROWSER ?? '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser',headless:false,args:['--auto-select-desktop-capture-source=Entire screen','--enable-usermedia-screen-capturing','--window-position=0,60','--window-size=600,400']});
  const page=await browser.newPage();await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.evaluate(()=>{
    document.querySelector('#share').onclick=async()=>{
      try {
        const capture=await navigator.mediaDevices.getDisplayMedia({video:{displaySurface:'monitor'},audio:false});
        const sender=new RTCPeerConnection({iceServers:[]}),receiver=new RTCPeerConnection({iceServers:[]});
        const toSender=[],toReceiver=[];
        sender.onicecandidate=e=>{if(e.candidate){if(receiver.remoteDescription)receiver.addIceCandidate(e.candidate);else toReceiver.push(e.candidate)}};
        receiver.onicecandidate=e=>{if(e.candidate){if(sender.remoteDescription)sender.addIceCandidate(e.candidate);else toSender.push(e.candidate)}};
        receiver.ontrack=e=>{document.querySelector('video').srcObject=e.streams[0]};
        capture.getTracks().forEach(t=>sender.addTrack(t,capture));
        await sender.setLocalDescription(await sender.createOffer());await receiver.setRemoteDescription(sender.localDescription);
        for(const c of toReceiver)await receiver.addIceCandidate(c);
        await receiver.setLocalDescription(await receiver.createAnswer());await sender.setRemoteDescription(receiver.localDescription);
        for(const c of toSender)await sender.addIceCandidate(c);
        await document.querySelector('video').play();
        window.diagnostic={capture,sender,receiver,settings:capture.getVideoTracks()[0].getSettings()};
      }catch(e){window.error=e.name+': '+e.message}
    };
  });
  await page.locator('#share').click();
  await page.waitForFunction(()=>window.error||(window.diagnostic&&document.querySelector('video').videoWidth>0),undefined,{timeout:20000});
  const error=await page.evaluate(()=>window.error);if(error)throw Error(error);
  const settings=await page.evaluate(()=>diagnostic.settings);if(settings.displaySurface!=='monitor')throw Error('Expected an entire-screen capture');
  console.log(`Brave ${browser.version()}, ordinary entire-screen capture ${settings.width}x${settings.height}, decoded WebRTC receiver.`);
  async function samples(){
    const s=await state();
    return page.evaluate(async s=>{
      const v=document.querySelector('video'),c=document.createElement('canvas');c.width=v.videoWidth;c.height=v.videoHeight;
      const ctx=c.getContext('2d');ctx.drawImage(v,0,0);const p=ctx.getImageData(0,0,c.width,c.height).data;
      let magenta=0,green=0,nonGreen=0,total=0;
      for(let y=Math.ceil((s.y+35)/s.screenHeight*c.height);y<(s.y+s.height-20)/s.screenHeight*c.height;y+=2){
        for(let x=Math.ceil((s.x+20)/s.screenWidth*c.width);x<(s.x+s.width-20)/s.screenWidth*c.width;x+=2){
          const i=(y*c.width+x)*4,r=p[i],g=p[i+1],b=p[i+2];total++;
          if(r>200&&g<70&&b>140)magenta++;
          if(g>r+60&&g>b+60)green++;else nonGreen++;
        }
      }
      let frames=0;for(const r of (await diagnostic.receiver.getStats()).values()){if(r.type==='inbound-rtp'&&r.kind==='video')frames=r.framesDecoded}
      return {magenta,green,nonGreen,total,frames,nativePrivate:s.private,realMemoize:!!s.realMemoize};
    },s);
  }
  async function check(label,predicate){
    let value;for(let i=0;i<80;i++){value=await samples();if(predicate(value)){console.log(label+': '+JSON.stringify(value));return value}await delay(100)}
    throw Error(label+' failed: '+JSON.stringify(value));
  }
  const visibleNote=v=>v.realMemoize&&v.nonGreen>1000;
  let initiallyProtected;
  if(settingsUI){
    await status('Settings enabled before capture detects sharing',s=>s.private&&s.captureDetected&&s.privacyIndicatorVisible);
    initiallyProtected=await check('Settings protection enabled before capture omits the note',v=>v.magenta<20&&v.green>v.total*.95&&v.frames>0);
    await command('shared');
  }
  const visible=await check('Visible note baseline',v=>visibleNote(v)&&v.frames>0);
  await command('private');
  const active=await status('Sharing indicator appears during capture',s=>s.private&&s.captureDetected&&s.privacyIndicatorVisible);
  const hidden=await check('Native private note omitted',v=>v.magenta<20&&v.green>v.total*.95&&v.frames>visible.frames);
  await command('edit');await check('Editing stays private',v=>v.magenta<20&&v.green>v.total*.95);
  await command('resize');await check('Resizing stays private',v=>v.magenta<20&&v.green>v.total*.95);
  await command('hide');await command('show');await check('Hide and restore stay private',v=>v.magenta<20&&v.green>v.total*.95);
  if((await state()).realMemoize){await command('preview');await check('Attachment preview stays private',v=>v.nonGreen<20&&v.green>v.total*.95)}
  await command('shared');
  await status('Disabling privacy clears its sharing indicator',s=>!s.private&&!s.privacyIndicatorVisible);
  await check('Clearing native privacy restores sharing',v=>visibleNote(v)&&v.frames>hidden.frames);
  await command('private');const final=await check('Private restored',v=>v.magenta<20&&v.green>v.total*.95);
  if(process.argv.includes('--hold')){
    console.log('Holding a private test note and the full-screen capture for local readability verification.');
    for(let i=0;i<600;i++){try{await readFile(path.join(directory,'stop'));break}catch{}await delay(500)}
  }
  await page.evaluate(()=>{
    diagnostic.capture.getTracks().forEach(track=>track.stop());
    diagnostic.sender.close();diagnostic.receiver.close();
    document.querySelector('video').srcObject=null;
  });
  const stopped=await status('Sharing indicator disappears after capture ends',s=>s.private&&!s.captureDetected&&!s.privacyIndicatorVisible);
  await writeFile(path.join(directory,'results.json'),JSON.stringify({settings,settingsUI,initiallyProtected,idle,active,stopped,final,passed:true},null,2));
  console.log('PASS: default Brave entire-screen capture omits the note, with automatic sharing status at capture start and end. Physical local readability is not established by these captures.');
} finally {
  if(browser)await browser.close();await new Promise(r=>server.close(r));
}
