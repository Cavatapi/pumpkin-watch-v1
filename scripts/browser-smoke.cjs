// Optional browser QA: install Playwright or set NODE_PATH to an existing installation.
// The Java game itself needs no npm dependencies.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
(async()=>{
  const browser=await chromium.launch({headless:true,channel:process.env.PW_CHANNEL || 'msedge'});
  const errors=[];const dir=path.join(__dirname,'../test-results');fs.mkdirSync(dir,{recursive:true});
  const desktop=await browser.newContext({viewport:{width:1440,height:1150}}),mobile=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,deviceScaleFactor:1});
  const host=await desktop.newPage(),guest=await mobile.newPage();
  for(const p of [host,guest])p.on('pageerror',e=>errors.push(e.message));
  try{
    await host.goto('http://localhost:3000');await host.locator('.build-card').first().waitFor();
    await host.screenshot({path:path.join(dir,'desktop-welcome.png'),fullPage:true});
    await guest.goto('http://localhost:3000');await guest.locator('.build-card').first().waitFor();
    assert.equal(await guest.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Mobile has no horizontal overflow');
    await guest.screenshot({path:path.join(dir,'mobile-welcome.png'),fullPage:true});
    await host.locator('#name').fill('Autumn');await host.locator('#create').click();await host.locator('#code').filter({hasText:/[A-F0-9]{5}/}).waitFor();const code=await host.locator('#code').innerText();
    await guest.locator('#name').fill('<b>Sprout</b>');await guest.locator('[data-skin="scarecrow"]').click();await guest.locator('#room-code').fill(code);await guest.locator('#join-form button').click();await guest.locator('#room-panel').waitFor({state:'visible'});
    await host.locator('#player-count').filter({hasText:'2 / 8'}).waitFor();assert.equal(await host.locator('#roster b').count(),0,'Player names are rendered as text');
    await host.locator('#ready').click();await guest.locator('#ready').click();await host.locator('#start:not([disabled])').waitFor();await host.locator('#start').click();await host.locator('#phase-label').filter({hasText:'DUSK · TIME TO PLANT'}).waitFor();
    await guest.locator('#phase-label').filter({hasText:'DUSK · TIME TO PLANT'}).waitFor();
    const farm=await host.locator('#farm').boundingBox();await host.mouse.click(farm.x+670/960*farm.width,farm.y+300/600*farm.height);
    await host.locator('[data-type="cannon"]:not([disabled])').waitFor();await host.locator('[data-type="cannon"]').click();await guest.locator('#seeds').filter({hasText:'180'}).waitFor();
    await host.locator('#plot-detail').filter({hasText:'Pumpkin cannon'}).waitFor();await host.locator('#upgrade').click();await guest.locator('#seeds').filter({hasText:'150'}).waitFor();
    await guest.reload();await guest.locator('#room-panel').waitFor({state:'visible'});await guest.locator('#phase-label').filter({hasText:'DUSK · TIME TO PLANT'}).waitFor();assert.equal(await guest.locator('#code').innerText(),code,'Reload resumes same room');
    await host.locator('#help').click();await host.locator('#help-dialog').waitFor({state:'visible'});await host.locator('.dialog-close-button').click();
    await guest.locator('#touch-controls').waitFor({state:'visible'});
    const pad=await guest.locator('#touch-controls').boundingBox(),board=await guest.locator('#farm').boundingBox();assert.ok(pad.y+pad.height<=844&&pad.y>board.y,'Mobile movement controls and farm fit together on screen');
    await host.screenshot({path:path.join(dir,'desktop-game.png'),fullPage:true});await guest.screenshot({path:path.join(dir,'mobile-game.png'),fullPage:true});
    assert.deepEqual(errors,[],'No browser runtime errors');
    console.log('PASS: desktop/mobile rendering, two-client room, safe names, ready/start, shared build/upgrade spending, reload reconnect, help dialog.');
    console.log('Screenshots: '+dir);
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
