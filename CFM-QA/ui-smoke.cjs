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
    {name:"sample.html",isDir:false,path:downloads+"/sample.html",size:250},
    {name:"photo.jpg",isDir:false,path:downloads+"/photo.jpg",size:1088},
    {name:"legacy.txt",isDir:false,path:downloads+"/legacy.txt",size:77},
    {name:"sample.apk",isDir:false,path:downloads+"/sample.apk",size:30000}
  ],
  [trash]:[
    {name:"deleted.txt",isDir:false,path:trash+"/deleted.txt",size:7},
    {name:"another.txt",isDir:false,path:trash+"/another.txt",size:8}
  ]
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
  case "openWith":return {message:"已打开系统打开方式菜单"};
  case "icons":return {icons:Object.fromEntries((args.paths||[])
    .filter(p=>p.endsWith(".apk"))
    .map(p=>[p,"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Yf7fXsAAAAASUVORK5CYII="]))};
  case "search":return {results:[{
    name:"sample.zip",path:downloads+"/sample.zip",isDir:false
  }],scanned:12,truncated:false};
  case "openTrash":return mode==="android"
    ? {path:trash,message:"已打开 CFM 回收站"}
    : {message:"已在 Windows 资源管理器中打开回收站"};
  case "restore":return {count:args.paths.length,message:"已恢复"};
  case "permanentDelete":return {count:args.paths.length,message:"已永久删除"};
  case "recycle":return {count:args.paths.length,message:"已移入回收站"};
  case "copy":case "move":return {count:args.paths.length};
  case "mkdir":case "newFile":case "newHtml":
    return {path:args.path+"/"+args.name};
  case "rename":return {path:args.path};
  case "extract":return {folder:home+"/out",files:1};
  case "zip":return {path:home+"/test.zip"};
  case "read":
    if(args.path.endsWith("legacy.txt"))throw Error("不是 UTF-8 编码，暂不支持自动转码");
    return {path:args.path,text:"text",name:"sample.html",bom:false,html:true};
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
    assert.equal(w.document.querySelector("#path1").value,home);
    assert.ok(w.document.querySelectorAll("#entries0 .entry").length>=2);
  });
  await test("right pane aligns with left even if right was active",async()=>{
    await setPath(1,downloads);
    assert.equal(w.document.querySelector("#path1").value,downloads);
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
    // Wait for navigation to finish; a closed modal does not mean the async
    // file listing has finished. Otherwise the following test races it.
    await waitFor(()=>w.document.querySelector("#path0").value===downloads);
    await new Promise(resolve=>setTimeout(resolve,30));
  });

  await test("search locates exact file and temporarily highlights its name green",async()=>{
    await setPath(0,home);
    // Search acts on the currently active pane, so explicitly activate left.
    w.document.querySelector("#entries0 .entry[data-path]").click();
    w.document.querySelector("#search").click();
    w.document.querySelector("#search-query").value="sample";
    w.document.querySelector("#search-submit").click();
    await waitFor(()=>w.document.querySelectorAll(".search-result").length===1);
    w.document.querySelector(".search-result").click();
    await new Promise(r=>setTimeout(r,100));
    console.log("DIAG SEARCH | mode="+mode+
      " path0="+w.document.querySelector("#path0").value+
      " path1="+w.document.querySelector("#path1").value+
      " active="+[...w.document.querySelectorAll(".pane")].findIndex(e=>e.classList.contains("active"))+
      " hits="+w.document.querySelectorAll("#entries0 .entry.search-hit").length+
      " entries="+w.document.querySelectorAll("#entries0 .entry[data-path]").length+
      " lastCall="+JSON.stringify(requests[requests.length-1]));
    await waitFor(()=>w.document.querySelector("#path0").value===downloads);
    await waitFor(()=>!!w.document.querySelector("#entries0 .entry.search-hit"));
    const target=[...w.document.querySelectorAll("#entries0 .entry")]
      .find(x=>x.dataset.path===downloads+"/sample.zip");
    assert.ok(target);
    assert.ok(target.classList.contains("search-hit"));
    await new Promise(r=>setTimeout(r,2750));
    assert.equal(target.classList.contains("search-hit"),false);
  });
  await test("APK entries obtain image data from native bridge and display it",async()=>{
    await setPath(0,downloads);
    await waitFor(()=>requests.some(x=>x.action==="icons" &&
      x.args.paths.some(p=>p.endsWith("sample.apk"))));
    await waitFor(()=>!!w.document.querySelector(
      '#entries0 .entry[data-path$="sample.apk"] img.native-file-icon'));
    const icon=w.document.querySelector(
      '#entries0 .entry[data-path$="sample.apk"] img.native-file-icon');
    assert.ok(icon.src.startsWith("data:image/png;base64,"));
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
  await test("trash item long press offers permanent delete & confirmation flow",async()=>{
    w.document.querySelector("#delete").click();
    await waitFor(()=>w.document.querySelector("#path0").value===trash);
    const row=w.document.querySelector("#entries0 .entry[data-path]");
    assert.ok(row,"No trashed file to test");
    row.dispatchEvent(new w.MouseEvent("contextmenu",{
      bubbles:true,cancelable:true,clientX:50,clientY:50
    }));
    w.document.querySelector("#selection-more").click();
    const menu=w.document.querySelector("#context");
    assert.match(menu.textContent,/永久删除/);
    assert.match(menu.textContent,/恢复/);
    assert.ok(!menu.textContent.includes("移入回收站"));
    const action=[...menu.querySelectorAll("button")].find(e=>e.textContent.includes("永久删除"));
    action.click();
    await waitFor(()=>requests.some(x=>x.action==="permanentDelete"));
    assert.equal(requests.find(x=>x.action==="permanentDelete").args.paths.join(","),
      trash+"/deleted.txt");
    w.document.querySelector("#selection-cancel").click();
  });
  await test("permanent delete button only appears inside CFM trash",async()=>{
    await setPath(0,downloads);
    assert.ok(w.document.querySelector("#permanent-trash").classList.contains("hidden"));
    w.document.querySelector("#delete").click();
    await waitFor(()=>w.document.querySelector("#path0").value===trash);
    assert.ok(!w.document.querySelector("#permanent-trash").classList.contains("hidden"));
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
  await test("non-text files reach Android openWith instead of being blocked",async()=>{
    for(const name of ["photo.jpg","book.pdf","video.mp4","sample.apk","unknown.bin"]){
      const before=requests.length;
      const filePath=downloads+"/"+name;
      if(!fixture[downloads].some(x=>x.path===filePath))fixture[downloads].push({name,isDir:false,path:filePath,size:10});
      await setPath(0,downloads);
      const row=[...w.document.querySelectorAll("#entries0 .entry[data-path]")].find(x=>x.dataset.path===filePath);
      row.dispatchEvent(new w.MouseEvent("dblclick",{bubbles:true}));
      await waitFor(()=>requests.slice(before).some(x=>x.action==="openWith"&&x.args.path===filePath));
    }
  });
  await setPath(0,downloads);
  await test("Android non-text file opens via system viewer",async()=>{
    const before=requests.filter(x=>x.action==="openWith").length;
    w.document.querySelector('#entries0 .entry[data-path$="photo.jpg"]').dispatchEvent(new w.MouseEvent("dblclick",{bubbles:true}));
    await waitFor(()=>requests.filter(x=>x.action==="openWith").length===before+1);
  });
  await test("Android non-UTF8 text falls back to external viewer",async()=>{
    const oldReads=requests.filter(x=>x.action==="read").length;
    const oldOpen=requests.filter(x=>x.action==="openWith").length;
    w.document.querySelector('#entries0 .entry[data-path$="legacy.txt"]').dispatchEvent(new w.MouseEvent("dblclick",{bubbles:true}));
    await waitFor(()=>requests.filter(x=>x.action==="read").length===oldReads+1);
    await waitFor(()=>requests.filter(x=>x.action==="openWith").length===oldOpen+1);
  });
  await test("Android archive file opens through installed file viewer",async()=>{
    const oldOpen=requests.filter(x=>x.action==="openWith").length;
    w.document.querySelector('#entries0 .entry[data-path$="sample.zip"]').dispatchEvent(new w.MouseEvent("dblclick",{bubbles:true}));
    await waitFor(()=>requests.filter(x=>x.action==="openWith").length===oldOpen+1);
  });
  await test("HTML and archive buttons exist",async()=>{
    assert.ok(w.document.querySelector("#html-button"));
    assert.ok(w.document.querySelector("#extract"));
    assert.ok(w.document.querySelector("#zip"));
  });

  await test("multi-select keeps choices across both panes",async()=>{
    w.document.querySelector("#clear-selection").click();
    await setPath(0,home);
    await setPath(1,downloads);
    w.document.querySelector("#multi-select").click();
    assert.equal(w.document.querySelector("#multi-select").getAttribute("aria-pressed"),"true");
    const left=w.document.querySelector('#entries0 .entry[data-path$="/alpha.txt"]');
    assert.ok(left);
    left.click();
    w.document.querySelector('#entries1 .entry[data-path$="/sample.zip"]').click();
    w.document.querySelector('#entries1 .entry[data-path$="/sample.html"]').click();
    assert.match(w.document.querySelector("#multi-count").textContent,/左 1.*右 2.*共 3/);
    assert.ok(left.classList.contains("selected"));
    assert.ok(w.document.querySelector('#entries1 .entry[data-path$="/sample.zip"]').classList.contains("selected"));
  });
  await test("long-press selected file retains both panes and recycles all",async()=>{
    const oldCount=requests.filter(x=>x.action==="recycle").length;
    const right=w.document.querySelector('#entries1 .entry[data-path$="/sample.zip"]');
    // Use the same 480ms hold gesture as a real Android long press,
    // not only a synthetic desktop right-click event.
    const start=new w.Event("touchstart",{bubbles:true,cancelable:true});
    Object.defineProperty(start,"touches",{value:[{clientX:62,clientY:51}]});
    right.dispatchEvent(start);
    await new Promise(r=>setTimeout(r,530));
    const end=new w.Event("touchend",{bubbles:true,cancelable:true});
    Object.defineProperty(end,"touches",{value:[]});
    right.dispatchEvent(end);
    assert.ok(w.document.querySelector("#context").classList.contains("hidden"));
    assert.ok(!w.document.querySelector("#selection-bar").classList.contains("hidden"));
    assert.match(w.document.querySelector("#multi-count").textContent,/左 1.*右 2.*共 3/);
    const button=w.document.querySelector("#selection-delete");
    assert.ok(button);
    button.click();
    await waitFor(()=>requests.filter(x=>x.action==="recycle").length>oldCount);
    const call=requests.filter(x=>x.action==="recycle").at(-1);
    assert.equal(call.args.paths.length,3);
    const got=[...call.args.paths].sort();
    const expected=[home+"/alpha.txt",downloads+"/sample.zip",downloads+"/sample.html"].sort();
    assert.equal(JSON.stringify(got),JSON.stringify(expected));
    await waitFor(()=>w.document.querySelector("#busy").classList.contains("hidden"));
  });
  await test("holding an unselected row adds it without losing earlier selections",async()=>{
    // Previous test held sample.zip; Android intentionally suppresses the
    // synthetic tap on THAT row for 900 ms after the long press.
    await new Promise(resolve=>setTimeout(resolve,950));
    w.document.querySelector("#clear-selection").click();
    await setPath(0,home);
    await setPath(1,downloads);
    const left=w.document.querySelector('#entries0 .entry[data-path$="/alpha.txt"]');
    const right=w.document.querySelector('#entries1 .entry[data-path$="/sample.zip"]');
    const unrelated=w.document.querySelector('#entries1 .entry[data-path$="/sample.html"]');
    left.click();
    right.click();
    assert.match(w.document.querySelector("#multi-count").textContent,/共 2 项/);
    // Holding another row adds it; no file is deleted by this gesture.
    unrelated.dispatchEvent(new w.MouseEvent("contextmenu",{
      bubbles:true,cancelable:true,clientX:32,clientY:45
    }));
    assert.match(w.document.querySelector("#multi-count").textContent,/左 1.*右 2.*共 3/);
    assert.equal(left.classList.contains("selected"),true);
    assert.equal(unrelated.classList.contains("selected"),true);
    assert.ok(!w.document.querySelector("#context").textContent.includes("选中的 2 项"));
    // Don't actually recycle this fixture; continue testing with clear selection.
    w.document.querySelector("#clear-selection").click();
  });
  await test("file action menu finds the only selected file on the other pane",async()=>{
    await setPath(0,home);
    await setPath(1,downloads);
    w.document.querySelector("#clear-selection").click();
    w.document.querySelector('#entries0 .entry[data-path$="/alpha.txt"]').click();
    // Switch active pane without selecting anything on that side.
    w.document.querySelector("#entries1 .entry:not([data-path])").click();
    w.document.querySelector("#mobile-file-actions").click();
    assert.ok(w.document.querySelector("#context").textContent.includes("移入回收站"));
    assert.match(w.document.querySelector("#multi-count").textContent,/共 1 项/);
    w.document.querySelector("#clear-selection").click();
  });
  await test("trash multi-select permanent delete sends exactly the checked entries",async()=>{
    w.document.querySelector("#clear-selection").click();
    await setPath(0,trash);
    w.document.querySelector('#entries0 .entry[data-path$="/deleted.txt"]').click();
    w.document.querySelector('#entries0 .entry[data-path$="/another.txt"]').click();
    const selected=w.document.querySelector('#entries0 .entry[data-path$="/deleted.txt"]');
    selected.dispatchEvent(new w.MouseEvent("contextmenu",{
      bubbles:true,cancelable:true,clientX:35,clientY:35
    }));
    assert.equal(w.document.querySelector("#selection-delete").textContent,"永久删除");
    const oldCount=requests.filter(x=>x.action==="permanentDelete").length;
    w.document.querySelector("#selection-delete").click();
    await waitFor(()=>requests.filter(x=>x.action==="permanentDelete").length>oldCount);
    const call=requests.filter(x=>x.action==="permanentDelete").at(-1);
    assert.equal(call.args.paths.length,2);
    assert.equal(JSON.stringify([...call.args.paths].sort()),
      JSON.stringify([trash+"/deleted.txt",trash+"/another.txt"].sort()));
    await waitFor(()=>w.document.querySelector("#busy").classList.contains("hidden"));
  });
  await test("cancelled permanent deletion never invokes native API",async()=>{
    w.document.querySelector('#entries0 .entry[data-path$="/deleted.txt"]').click();
    const old=w.confirm;
    w.confirm=()=>false;
    const before=requests.filter(x=>x.action==="permanentDelete").length;
    w.document.querySelector("#permanent-trash").click();
    await new Promise(r=>setTimeout(r,90));
    assert.equal(requests.filter(x=>x.action==="permanentDelete").length,before);
    w.confirm=old;
  });
  await test("turning off multi-select clears both panes",async()=>{
    w.document.querySelector("#multi-select").click();
    assert.equal(w.document.querySelector("#multi-select").getAttribute("aria-pressed"),"false");
    assert.match(w.document.querySelector("#multi-count").textContent,/共 0 项/);
  });
  await test("long press enters selection directly, scroll cancels hold",async()=>{
    await setPath(0,home);
    const row=w.document.querySelector('#entries0 .entry[data-path$="/alpha.txt"]');
    function touch(type,x,y){const e=new w.Event(type,{bubbles:true,cancelable:true});Object.defineProperty(e,"touches",{value:type==="touchend"?[]:[{clientX:x,clientY:y}]});row.dispatchEvent(e);}
    touch("touchstart",30,60);touch("touchmove",30,100);
    await new Promise(r=>setTimeout(r,530));touch("touchend");
    assert.ok(w.document.querySelector("#selection-bar").classList.contains("hidden"));
    touch("touchstart",30,60);await new Promise(r=>setTimeout(r,530));touch("touchend");
    assert.ok(!w.document.querySelector("#selection-bar").classList.contains("hidden"));
    assert.equal(w.document.querySelector("#selection-total").textContent,"已选 1 项");
    assert.ok(w.document.querySelector("#context").classList.contains("hidden"));
    w.document.querySelector("#selection-cancel").click();
    assert.ok(w.document.querySelector("#selection-bar").classList.contains("hidden"));
  });
  await test("mixed trash and normal selection disables destructive button",async()=>{
    await new Promise(r=>setTimeout(r,950));
    await setPath(0,home);await setPath(1,trash);
    w.document.querySelector("#multi-select").click();
    assert.ok(w.document.querySelector("#selection-delete").disabled);
    w.document.querySelector('#entries0 .entry[data-path$="/alpha.txt"]').click();
    w.document.querySelector('#entries1 .entry[data-path$="/deleted.txt"]').click();
    assert.ok(w.document.querySelector("#selection-delete").disabled);
    assert.match(w.document.querySelector("#selection-hint").textContent,/分开选择/);
    w.document.querySelector("#selection-cancel").click();
  });
  await test("every required control exists in DOM",async()=>{
    for(const id of ["search","refresh","delete","send-trash","copy","move","new",
        "rename","edit","html-button","terminal-button","extract","zip",
        "multi-select","select-all","clear-selection","multi-count",
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

