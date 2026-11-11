/* CFM UI smoke tests.
 * Run: npm install --no-save --prefix CFM-QA jsdom@26.1.0
 *      node CFM-QA/ui-smoke.cjs [pc|android]
 * Simulates DOM and file bridge; does NOT certify native device behavior.
 */
const fs=require("fs");
const path=require("path");
const {JSDOM}=require("jsdom");
const assert=require("node:assert/strict");
const mode=process.argv[2]||"pc";
const uiDir=mode==="android"
  ?path.join(__dirname,"../Cheems-File-Manager-Android/app/src/main/assets/pcui")
  :path.join(__dirname,"../Cheems-File-Manager-PC/ui");
const html=fs.readFileSync(path.join(uiDir,"index.html"),"utf8");
const code=fs.readFileSync(path.join(uiDir,"app.js"),"utf8");
const home="/storage/emulated/0";
const downloads=home+"/Download";
const trash=home+"/.CFM_RecycleBin";
const fixture={
  [home]:[
    {name:"Download",isDir:true,path:downloads,size:0},
    {name:"alpha.txt",isDir:false,path:home+"/alpha.txt",size:12}
  ],
  [downloads]:[
    {name:"sample.zip",isDir:false,path:downloads+"/sample.zip",size:532},
    {name:"sample.html",isDir:false,path:downloads+"/sample.html",size:250}
  ],
  [trash]:[{name:"deleted.txt",isDir:false,path:trash+"/deleted.txt",size:7}]
};
let passed=0,failed=0;
const requests=[],listeners=[];
const dom=new JSDOM(html,{
  url:"https://cfm.local/index.html",
  runScripts:"outside-only",
  pretendToBeVisual:true
});
const w=dom.window;
w.chrome={webview:{
  addEventListener(type,fn){if(type==="message")listeners.push(fn);},
  postMessage(request){
    requests.push(request);
    const {id,action,args}=request;
    queueMicrotask(()=>{
      try{
        const data=mockAction(action,args||{});
        listeners.forEach(fn=>fn({data:{id,ok:true,data}}));
      }catch(e){
        listeners.forEach(fn=>fn({data:{id,ok:false,error:String(e.message)}}));
      }
    });
  }
}};
if(mode==="android")w.AndroidFileApi={postMessage(){}};
w.confirm=()=>true;
w.prompt=(title,def="")=>def||"new.txt";
w.alert=()=>{};
function mockAction(action,args){
 switch(action){
  case "init":return {home,desktop:home,documents:home,downloads,
    trash,drives:[{path:home,label:"内部存储"}]};
  case "list":return {path:args.path,
    parent:args.path===home?null:home,
    items:(fixture[args.path]||[]).map(item=>({
      ...item,modified:"2026-11-11 11:11",isLink:false
    })),truncated:false};
  case "search":return {results:[{
    name:"sample.zip",path:downloads+"/sample.zip",isDir:false
  }],scanned:12,truncated:false};
  case "openTrash":return mode==="android"
    ? {path:trash,message:"已打开 CFM 回收站"}
    : {message:"已在 Windows 资源管理器中打开回收站"};
  case "restore":return {count:args.paths.length,message:"已恢复"};
  case "recycle":return {count:args.paths.length,message:"已移入回收站"};
  case "copy":case "move":return {count:args.paths.length};
  case "mkdir":case "newFile":case "newHtml":
    return {path:args.path+"/"+args.name};
  case "rename":return {path:args.path};
  case "extract":return {folder:home+"/out",files:1};
  case "zip":return {path:home+"/test.zip"};
  case "read":return {path:args.path,text:"text",name:"sample.html",bom:false,html:true};
  case "save":return {path:args.path};
  case "shell":return {output:"QA",exitCode:0,cwd:args.cwd};
  default:throw Error("Unknown mocked action: "+action);
 }
}
async function waitFor(condition,limit=1700){
 const started=Date.now();
 while(!condition()){
  if(Date.now()-started>limit)throw Error("Condition timeout");
  await new Promise(resolve=>setTimeout(resolve,12));
 }
}
async function setPath(pane,path){
  const input=w.document.querySelector("#path"+pane);
  input.value=path;
  input.dispatchEvent(new w.KeyboardEvent("keydown",{
    key:"Enter",bubbles:true,cancelable:true
  }));
  await waitFor(()=>w.document.querySelector("#path"+pane).value===path);
  await new Promise(resolve=>setTimeout(resolve,25));
}
async function test(label,fn){
 try{await fn();passed++;console.log("PASS | "+label);}
 catch(error){failed++;console.error("FAIL | "+label+" | "+error.message);}
}
(async()=>{
 try{
  w.eval(code);
  await waitFor(()=>w.document.querySelector("#path0")?.value===home);
  await test("initial two independent panes",async()=>{
    assert.equal(w.document.querySelector("#path0").value,home);
    assert.equal(w.document.querySelector("#path1").value,downloads);
    assert.ok(w.document.querySelectorAll("#entries0 .entry").length>=2);
  });
  await test("per-pane path synchronization preserves active pane",async()=>{
    await w.CFMNav.sync();
    await waitFor(()=>w.document.querySelector("#path1").value===home);
    assert.equal(w.document.querySelector("#path0").value,home);
  });
  await test("forward / back history works",async()=>{
    await setPath(0,downloads);
    await w.CFMNav.back();
    assert.equal(w.document.querySelector("#path0").value,home);
    await w.CFMNav.forward();
    assert.equal(w.document.querySelector("#path0").value,downloads);
  });
  await test("search opens HTML dialog and renders results",async()=>{
    w.document.querySelector("#search").click();
    assert.ok(!w.document.querySelector("#search-overlay").classList.contains("hidden"));
    w.document.querySelector("#search-query").value="sample";
    w.document.querySelector("#search-submit").click();
    await waitFor(()=>w.document.querySelectorAll(".search-result").length===1);
    assert.match(w.document.querySelector("#search-summary").textContent,/找到 1 项/);
    w.document.querySelector(".search-result").click();
    await waitFor(()=>w.document.querySelector("#search-overlay").classList.contains("hidden"));
  });
  await test("Open Trash works without selecting a file",async()=>{
    const before=requests.filter(x=>x.action==="recycle").length;
    w.document.querySelector("#delete").click();
    await waitFor(()=>requests.some(x=>x.action==="openTrash"));
    if(mode==="android")
      await waitFor(()=>w.document.querySelector("#path0").value===trash);
    else assert.equal(w.document.querySelector("#path0").value,downloads);
    assert.equal(requests.filter(x=>x.action==="recycle").length,before);
  });
  await test("Send To Trash only acts on selected files",async()=>{
    if(mode==="android")await setPath(0,downloads);
    const row=w.document.querySelector("#entries0 .entry[data-path]");
    assert.ok(row);
    row.click();
    w.document.querySelector("#send-trash").click();
    await waitFor(()=>requests.some(x=>x.action==="recycle"));
    const used=requests.find(x=>x.action==="recycle");
    assert.equal(used.args.paths.length,1);
    assert.ok(used.args.paths[0].startsWith(downloads));
  });
  await test("Restore action routes to Android native backend",async()=>{
    const restore=w.document.querySelector("#restore-trash");
    if(!restore)return;
    w.document.querySelector("#delete").click();
    await waitFor(()=>w.document.querySelector("#path0").value===trash);
    w.document.querySelector("#entries0 .entry[data-path]").click();
    restore.click();
    await waitFor(()=>requests.some(x=>x.action==="restore"));
  });
  await test("plus menu opens and creates an empty file",async()=>{
    w.CFMNav.plus();
    const menu=w.document.querySelector("#context");
    assert.ok(!menu.classList.contains("hidden"));
    assert.match(menu.textContent,/新建空文件/);
    [...menu.querySelectorAll("button")].find(e=>e.textContent.includes("空文件")).click();
    await waitFor(()=>requests.some(x=>x.action==="newFile"));
    await waitFor(()=>w.document.querySelector("#busy").classList.contains("hidden"));
  });
  await test("HTML and archive buttons exist",async()=>{
    assert.ok(w.document.querySelector("#html-button"));
    assert.ok(w.document.querySelector("#extract"));
    assert.ok(w.document.querySelector("#zip"));
  });
  await test("every required control exists in DOM",async()=>{
    for(const id of ["search","refresh","delete","send-trash","copy","move","new",
        "rename","edit","html-button","terminal-button","extract","zip",
        "search-overlay","search-query","search-submit","close-search"]) {
      assert.ok(w.document.getElementById(id),"Missing #"+id);
    }
    if(mode==="pc")for(const id of ["nav-back","nav-forward","nav-plus","nav-sync","nav-up"])
      assert.ok(w.document.getElementById(id),"Missing PC nav "+id);
  });
 }finally{
  await new Promise(resolve=>setTimeout(resolve,90));
  dom.window.close();
 }
 console.log("RESULT | MODE="+mode+"; PASS="+passed+"; FAIL="+failed);
 process.exitCode=failed?1:0;
})().catch(e=>{console.error("FAIL | QA setup | "+e.stack);process.exitCode=1;dom.window.close();});
