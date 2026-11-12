const {chromium}=require('playwright');const fs=require('fs');const assert=require('assert');
const path=require('path');const dir=path.join(__dirname,'../Cheems-File-Manager-Android/app/src/main/assets/pcui');
const test=fs.readFileSync(path.join(__dirname,'ui-smoke.cjs'),'utf8');
const fixture=test.slice(test.indexOf('const home='),test.indexOf('let passed='));
const mock=test.slice(test.indexOf('function mockAction'),test.indexOf('async function waitFor'));
(async()=>{const browser=await chromium.launch({headless:true});const page=await browser.newPage({viewport:{width:390,height:720},isMobile:true,hasTouch:true});
await page.route('https://cfm.local/**',route=>{const name=new URL(route.request().url()).pathname.slice(1);if(name==='android-bridge.js')return route.fulfill({body:''});try{route.fulfill({body:fs.readFileSync(dir+'/'+name),contentType:name.endsWith('.css')?'text/css':name.endsWith('.js')?'application/javascript':'text/html'});}catch{route.fulfill({status:404,body:''});}});
await page.addInitScript(fixture+mock+`;const mode='android';const listeners=[];window.AndroidFileApi={};window.chrome={webview:{addEventListener(t,f){listeners.push(f)},postMessage(r){queueMicrotask(()=>listeners.forEach(f=>f({data:{id:r.id,ok:true,data:mockAction(r.action,r.args)}})))}}};`);
await page.goto('https://cfm.local/index.html');await page.waitForSelector('.entry[data-path]');
await page.evaluate(()=>{beginTouchSelection(0,state.panes[0].items[1]);});
await page.locator('#toast').evaluate(e=>e.classList.add('hidden'));
await page.screenshot({path:path.join(__dirname,'phone-selection.png')});
for(const width of [320,390,600]){await page.setViewportSize({width,height:720});const box=await page.locator('#selection-delete').boundingBox();assert(box.width>=100&&box.height>=44&&box.x>=0&&box.x+box.width<=width);assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));}
await page.evaluate(async()=>{clearAllSelections();await navigate(0,state.locations.trash);beginTouchSelection(0,state.panes[0].items[0]);});assert.equal(await page.locator('#selection-delete').textContent(),'永久删除');
console.log('PASS phone layouts 320/390/600, bottom action size and trash action');await browser.close();})();
