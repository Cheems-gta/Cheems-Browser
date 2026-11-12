const {chromium}=require('playwright');
const fs=require('fs');
const path=require('path');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try {
  const page=await browser.newPage();
  const root=path.join(__dirname,'../Cheems-File-Manager-PC/ui');
  const html=fs.readFileSync(path.join(root,'index.html'),'utf8')
   .replace('<link rel="stylesheet" href="style.css">','<style>'+fs.readFileSync(path.join(root,'style.css'),'utf8')+'</style>')
   .replace(/<script[^>]*src="app.js"[^>]*><\/script>/,'');
  await page.setContent(html);
  for(const [width,height] of [[790,520],[1240,760],[1680,950]]) {
   await page.setViewportSize({width,height});
   const boxes=await page.evaluate(()=>['pane0','pane1','send-trash','path1'].map(id=>{
    const r=document.getElementById(id).getBoundingClientRect();
    return {id,x:r.x,y:r.y,right:r.right,bottom:r.bottom,height:r.height};
   }));
   for(const r of boxes) assert.ok(r.x>=0 && r.y>=0 && r.right<=width+1 && r.bottom<=height+1 && r.height>15,JSON.stringify({width,height,...r}));
   console.log('PASS | layout '+width+'x'+height);
  }
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
