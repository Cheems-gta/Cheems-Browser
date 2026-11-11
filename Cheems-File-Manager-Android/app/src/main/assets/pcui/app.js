"use strict";

const state = {
  panes: [
    {path:"", parent:null, items:[], selected:new Set(), filter:"", truncated:false},
    {path:"", parent:null, items:[], selected:new Set(), filter:"", truncated:false}
  ],
  active:0, locations:null, editor:null, pending:new Map(), sequence:0, tasks:0,
  previewVisible:false
};

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const ext = path => path.slice(path.lastIndexOf(".")).toLowerCase();

function call(action,args={}) {
  if (!window.chrome || !chrome.webview) {
    return Promise.reject(new Error("此页面需要在 CFM Windows EXE 内运行。"));
  }
  const id = ++state.sequence;
  return new Promise((resolve,reject) => {
    state.pending.set(id,{resolve,reject});
    chrome.webview.postMessage({id,action,args});
  });
}

if (window.chrome && chrome.webview) {
  chrome.webview.addEventListener("message",event => {
    const packet = event.data;
    if (!packet || !state.pending.has(packet.id)) return;
    const {resolve,reject} = state.pending.get(packet.id);
    state.pending.delete(packet.id);
    if (packet.ok) resolve(packet.data);
    else reject(new Error(packet.error || "文件操作失败"));
  });
}

let toastTimer;
function toast(message,error=false) {
  const bar=$("#toast");
  bar.textContent = message;
  bar.style.borderColor = error ? "#a15b64" : "#52779a";
  bar.classList.remove("hidden");
  clearTimeout(toastTimer);
  toastTimer=setTimeout(()=>bar.classList.add("hidden"),4200);
  $("#status-text").textContent = message;
}

function busy(enabled) {
  state.tasks += enabled ? 1 : -1;
  if(state.tasks<0)state.tasks=0;
  $("#busy").classList.toggle("hidden",state.tasks===0);
}

async function guarded(job) {
  busy(true);
  try { return await job(); }
  catch(error) { toast(error.message,true); return null; }
  finally { busy(false); }
}

const activePane = () => state.panes[state.active];
function switchActive(n) {
  state.active=n;
  $$(".pane").forEach((element,index)=>element.classList.toggle("active",index===n));
  return state.panes[n];
}
function formatBytes(bytes) {
  if (!Number.isFinite(bytes)) return "—";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1048576) return (bytes/1024).toFixed(1)+" KB";
  if (bytes < 1073741824) return (bytes/1048576).toFixed(1)+" MB";
  return (bytes/1073741824).toFixed(2)+" GB";
}
function isText(name) { return /\.(txt|md|htm|html|css|js|ts|json|xml|yaml|yml|ini|cfg|log|csv|py|cs|java|cpp|c|h|hpp|svg|bat|ps1)$/i.test(name); }
function isArchive(name) { return /\.(zip|7z|rar|tar|tgz|gz|bz2|xz|jar|apk|xapk|apks|apkm)$/i.test(name); }

async function navigate(index,path) {
  if (!path) return;
  const data = await call("list",{path});
  const pane=state.panes[index];
  pane.path=data.path;
  pane.parent=data.parent;
  pane.items=data.items;
  pane.selected.clear();
  pane.truncated=data.truncated;
  $("#path"+index).value=data.path;
  render(index);
  return data;
}
async function refresh(index) {
  const pane=state.panes[index];
  if (!pane.path) return;
  await navigate(index,pane.path);
}
async function refreshBoth() {
  await Promise.all([guarded(()=>refresh(0)),guarded(()=>refresh(1))]);
}
async function up(index=state.active) {
  if(state.panes[index].parent) await guarded(()=>navigate(index,state.panes[index].parent));
  else toast("已经到磁盘根目录了");
}

function entryIcon(item) {
  if(item.isLink)return {symbol:"↗",type:"link"};
  if(item.isDir)return {symbol:"▰",type:"folder"};
  if(isArchive(item.name))return {symbol:"▣",type:"archive"};
  if(isText(item.name))return {symbol:"〈〉",type:"code"};
  return {symbol:"▤",type:"file"};
}

function render(index) {
  const pane=state.panes[index], target=$("#entries"+index);
  const filter=$("#filter"+index).value.trim().toLowerCase();
  target.replaceChildren();
  const shown=pane.items.filter(item=>!filter||item.name.toLowerCase().includes(filter));
  const fragment=document.createDocumentFragment();

  if(pane.parent!==null) {
    const parent=document.createElement("div");
    parent.className="entry";
    parent.title="返回上一级";
    const title=document.createElement("span");
    title.className="file-title";
    const icon=document.createElement("span");
    icon.className="icon folder";
    icon.textContent="↑";
    const text=document.createElement("span");
    text.className="name";
    text.textContent="..";
    title.append(icon,text);
    parent.append(title,document.createElement("span"),document.createElement("span"));
    parent.addEventListener("click",()=>switchActive(index));
    parent.addEventListener("dblclick",()=>up(index));
    fragment.appendChild(parent);
  }

  for (const item of shown) {
    const row=document.createElement("div");
    row.className="entry"+(pane.selected.has(item.path)?" selected":"");
    row.dataset.path=item.path;
    row.title=item.path;
    const nameCell=document.createElement("span");
    nameCell.className="file-title";
    const icon=document.createElement("span");
    const glyph=entryIcon(item);
    icon.className="icon "+glyph.type;
    icon.textContent=glyph.symbol;
    const label=document.createElement("span");
    label.className="name";
    label.textContent=item.name; // Never inject filenames as HTML.
    nameCell.append(icon,label);
    const modified=document.createElement("span");
    modified.className="meta modified";
    modified.textContent=item.modified || "—";
    const size=document.createElement("span");
    size.className="meta size";
    size.textContent=item.isDir?"文件夹":formatBytes(item.size);
    row.append(nameCell,modified,size);

    row.addEventListener("click",e=>{
      switchActive(index);
      if(e.ctrlKey) {
        if(pane.selected.has(item.path)) pane.selected.delete(item.path);
        else pane.selected.add(item.path);
      } else {
        pane.selected.clear();
        pane.selected.add(item.path);
      }
      showSelection(index);
    });
    row.addEventListener("dblclick",()=>{
      switchActive(index);
      if(item.isDir) guarded(()=>navigate(index,item.path));
      else openItem(item);
    });
    row.addEventListener("contextmenu",e=>{
      e.preventDefault();
      switchActive(index);
      if(!pane.selected.has(item.path)) {
        pane.selected.clear(); pane.selected.add(item.path);
      }
      showSelection(index);
      contextMenu(e.clientX,e.clientY,item);
    });
    fragment.appendChild(row);
  }

  if(!shown.length) {
    const empty=document.createElement("p");
    empty.className="empty";
    empty.textContent=filter?"没有符合筛选条件的文件":"文件夹为空";
    fragment.append(empty);
  }
  target.append(fragment);
  $("#count"+index).textContent=pane.items.length+" 项";
  $("#foot"+index).textContent=
    (pane.selected.size ? "已选择 "+pane.selected.size+" 项 · " : "")+
    "显示 "+shown.length+"/"+pane.items.length+
    (pane.truncated?" · 已截取前 2000 项":"");
}
function showSelection(index) {
  const pane=state.panes[index];
  $$("#entries"+index+" .entry[data-path]").forEach(row=>{
    row.classList.toggle("selected",pane.selected.has(row.dataset.path));
  });
  $("#foot"+index).textContent=
    (pane.selected.size?"已选择 "+pane.selected.size+" 项 · ":"")+
    "目录共 "+pane.items.length+" 项";
}

function selectedPaths() {
  const paths=[...activePane().selected];
  if(!paths.length) throw new Error("请先点击文件；按住 Ctrl 可以多选");
  return paths;
}
function oneItem() {
  const paths=selectedPaths();
  if(paths.length!==1) throw new Error("此操作需要恰好选中一个项目");
  return activePane().items.find(item=>item.path===paths[0]);
}

async function mutate(action,args,successText) {
  return guarded(async()=>{
    const out=await call(action,args);
    await Promise.all(state.panes.map((pane,index)=>pane.path?navigate(index,pane.path).catch(()=>{}):Promise.resolve()));
    toast(successText || "操作已完成");
    return out;
  });
}
async function transfer(move) {
  try {
    const paths=selectedPaths(), target=state.panes[1-state.active].path;
    if(!target) throw new Error("另一栏尚未打开目标文件夹");
    if(!confirm((move?"移动":"复制")+" "+paths.length+" 项到：\n"+target+"\n\n如有重名，复制/移动会自动生成新文件名。")) return;
    await mutate(move?"move":"copy",{paths,target},(move?"移动":"复制")+"完成");
  } catch(e){toast(e.message,true)}
}
async function makeFolder() {
  const current=activePane().path;
  const name=prompt("新文件夹名称：","新建文件夹");
  if(name===null)return;
  await mutate("mkdir",{path:current,name},"已创建文件夹");
}
async function rename() {
  try {
    const item=oneItem();
    const name=prompt("新的文件名（包含扩展名）：",item.name);
    if(name===null||name===item.name)return;
    await mutate("rename",{path:item.path,name},"已重命名");
  } catch(e){toast(e.message,true)}
}
async function recycle() {
  try {
    const paths=selectedPaths();
    if(!confirm("将 "+paths.length+" 项送入 Windows 回收站？\n请先确认不是重要文件。"))return;
    await mutate("recycle",{paths},"已移入回收站");
  }catch(e){toast(e.message,true)}
}
async function extract() {
  try {
    const item=oneItem();
    if(item.isDir)throw new Error("请选择一个压缩文件");
    if(!isArchive(item.name))throw new Error("请选 ZIP / 7z / RAR / TAR 等支持的压缩文件");
    const target=state.panes[1-state.active].path;
    if(!confirm("解压："+item.name+"\n\n目标："+target+"\n\n部分加密包或分卷格式可能不支持；建议先用测试文件。"))return;
    await mutate("extract",{path:item.path,target},"已解压到另一栏");
  }catch(e){toast(e.message,true)}
}
async function makeZip() {
  try {
    const paths=selectedPaths();
    const target=state.panes[1-state.active].path;
    if(!confirm("将 "+paths.length+" 项压缩成 ZIP，保存到：\n"+target+" ?"))return;
    await mutate("zip",{paths,target},"ZIP 创建完成");
  }catch(e){toast(e.message,true)}
}

let currentEditor=null;
function closeEditor() {
  if(currentEditor && $("#editor-text").value!==currentEditor.text) {
    if(!confirm("编辑内容尚未保存，确定放弃修改吗？"))return;
  }
  currentEditor=null;
  $("#editor-overlay").classList.add("hidden");
  $("#html-preview").srcdoc="";
}
async function openItem(item) {
  if(item.isDir) return guarded(()=>navigate(state.active,item.path));
  if(isArchive(item.name)) {
    toast("已选中压缩包，使用顶部「解压」或右键菜单提取");
    return;
  }
  if(!isText(item.name)) {
    toast("这不是可直接编辑的纯文本文件；为安全起见 V0.1 不会执行文件。");
    return;
  }
  return openEditor(item.path);
}
async function openEditor(path) {
  await guarded(async()=>{
    const data=await call("read",{path});
    currentEditor=data;
    $("#editor-file").textContent=data.path;
    $("#editor-text").value=data.text;
    $("#editor-mode").textContent=data.bom?"UTF-8（带 BOM）":"UTF-8";
    $("#editor-overlay").classList.remove("hidden");
    $("#preview-button").classList.toggle("hidden",!data.html);
    state.previewVisible=false;
    $("#preview-pane").classList.add("hidden");
    $("#editor-text").focus();
  });
}
async function saveEditor() {
  if(!currentEditor)return;
  await guarded(async()=>{
    const text=$("#editor-text").value;
    await call("save",{path:currentEditor.path,text,bom:currentEditor.bom});
    currentEditor.text=text;
    toast("已保存，原文件备份为 .cfm.bak");
    if(state.previewVisible) updatePreview();
    await refreshBoth();
  });
}
function updatePreview() {
  if(!currentEditor?.html) return;
  // sandbox="" disables scripts, forms, top navigation and keeps an opaque origin.
  // Strict CSP blocks outgoing network requests from previewed HTML.
  const policy="<meta http-equiv=\"Content-Security-Policy\" "+
     "content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; "+
     "script-src 'none'; connect-src 'none'; font-src 'none'; "+
     "form-action 'none'; base-uri 'none'; frame-src 'none'\">";
  $("#html-preview").srcdoc=policy+$("#editor-text").value;
}
function togglePreview() {
  if(!currentEditor?.html) return;
  state.previewVisible=!state.previewVisible;
  $("#preview-pane").classList.toggle("hidden",!state.previewVisible);
  $("#preview-button").textContent=state.previewVisible?"关闭预览":"预览 HTML";
  if(state.previewVisible)updatePreview();
}

async function search() {
  const query=prompt("在当前栏文件夹及子文件夹中搜索文件名（至少 2 字）：","");
  if(!query) return;
  await guarded(async()=>{
    const result=await call("search",{path:activePane().path,query});
    const index=state.active;
    $("#search-overlay").classList.remove("hidden");
    $("#search-summary").textContent=
      "搜索「"+query+"」· 找到 "+result.results.length+" 项 · 已扫描 "+result.scanned+" 项"+
      (result.truncated?"（已达到搜索上限）":"");
    const list=$("#search-results");
    list.replaceChildren();
    for(const file of result.results) {
      const row=document.createElement("button");
      row.className="search-result";
      const name=document.createElement("span");
      name.textContent=(file.isDir?"▰ ":"▤ ")+file.name;
      const location=document.createElement("small");
      location.textContent=file.path;
      row.append(name,location);
      row.addEventListener("click",async()=>{
        $("#search-overlay").classList.add("hidden");
        switchActive(index);
        const parent=file.isDir?file.path:file.path.slice(0,Math.max(
          file.path.lastIndexOf("\\"),file.path.lastIndexOf("/")));
        try {
          await navigate(index,parent);
          if(!file.isDir){
            state.panes[index].selected.add(file.path);
            showSelection(index);
          }
        }catch(error){toast(error.message,true)}
      });
      list.append(row);
    }
    if(!result.results.length) {
      const empty=document.createElement("p");
      empty.className="empty";
      empty.textContent="没有找到匹配项";
      list.append(empty);
    }
  });
}

function contextMenu(x,y,item) {
  const menu=$("#context");
  menu.replaceChildren();
  const actions=[
    ["打开 / 编辑",()=>openItem(item)],
    ["复制到另一栏",()=>transfer(false)],
    ["移动到另一栏",()=>transfer(true)],
    ["重命名",rename],
    ["移入回收站",recycle],
    ["创建 ZIP",makeZip],
    ["刷新",refreshBoth]
  ];
  if(!item.isDir&&isArchive(item.name))actions.splice(5,0,["解压到另一栏",extract]);
  for (const [label,fn] of actions) {
    const b=document.createElement("button");
    b.textContent=label;
    b.addEventListener("click",()=>{menu.classList.add("hidden");fn();});
    menu.append(b);
  }
  menu.classList.remove("hidden");
  requestAnimationFrame(()=>{
    menu.style.left=Math.max(4,Math.min(window.innerWidth-menu.offsetWidth-5,x))+"px";
    menu.style.top=Math.max(4,Math.min(window.innerHeight-menu.offsetHeight-5,y))+"px";
  });
}

function wire() {
  $("#refresh").addEventListener("click",refreshBoth);
  $("#search").addEventListener("click",search);
  $("#up").addEventListener("click",()=>up());
  $("#copy").addEventListener("click",()=>transfer(false));
  $("#move").addEventListener("click",()=>transfer(true));
  $("#new").addEventListener("click",makeFolder);
  $("#rename").addEventListener("click",rename);
  $("#delete").addEventListener("click",recycle);
  $("#edit").addEventListener("click",()=>{try{const item=oneItem();openItem(item)}catch(e){toast(e.message,true)}});
  $("#extract").addEventListener("click",extract);
  $("#zip").addEventListener("click",makeZip);
  $("#close-editor").addEventListener("click",closeEditor);
  $("#save-editor").addEventListener("click",saveEditor);
  $("#preview-button").addEventListener("click",togglePreview);
  $("#close-search").addEventListener("click",()=>$("#search-overlay").classList.add("hidden"));
  $("#editor-text").addEventListener("input",()=>{if(state.previewVisible)updatePreview()});
  for(let n=0;n<2;n++) {
    $("#pane"+n).addEventListener("click",()=>switchActive(n));
    $("#path"+n).addEventListener("keydown",e=>{
      if(e.key==="Enter"){e.preventDefault();switchActive(n);guarded(()=>navigate(n,e.target.value))}
    });
    $(".goto[data-pane='"+n+"']").addEventListener("click",()=>{
      switchActive(n);guarded(()=>navigate(n,$("#path"+n).value))
    });
    $(".pane-up[data-pane='"+n+"']").addEventListener("click",()=>{switchActive(n);up(n)});
    $("#filter"+n).addEventListener("input",()=>render(n));
  }
  $$(".quick").forEach(b=>b.addEventListener("click",()=>{
    const location=state.locations?.[b.dataset.quick];
    if(location)guarded(()=>navigate(state.active,location));
  }));
  $("#drive").addEventListener("change",event=>{
    if(event.target.value)guarded(()=>navigate(state.active,event.target.value));
    event.target.value="";
  });
  document.addEventListener("click",event=>{
    if(!$("#context").contains(event.target))$("#context").classList.add("hidden");
  });
  document.addEventListener("keydown",e=>{
    if(e.key==="Escape"){
      $("#context").classList.add("hidden");
      if(!$("#search-overlay").classList.contains("hidden"))$("#search-overlay").classList.add("hidden");
      else if(!$("#editor-overlay").classList.contains("hidden"))closeEditor();
      return;
    }
    if(!$("#editor-overlay").classList.contains("hidden")){
      if(e.ctrlKey && e.key.toLowerCase()==="s"){e.preventDefault();saveEditor();}
      return;
    }
    if(["INPUT","TEXTAREA"].includes(document.activeElement?.tagName))return;
    if(e.key==="F5"){e.preventDefault();refreshBoth();}
    if(e.key==="Backspace"){e.preventDefault();up();}
    if(e.key==="F2"){e.preventDefault();rename();}
    if(e.key==="Delete"){e.preventDefault();recycle();}
  });
}

async function start() {
  wire();
  try {
    state.locations=await call("init",{});
    const drive=$("#drive");
    for(const d of state.locations.drives) {
      const option=document.createElement("option");
      option.value=d.path;option.textContent="▰ "+d.label;
      drive.append(option);
    }
    await Promise.all([
      navigate(0,state.locations.home),
      navigate(1,state.locations.downloads)
    ]);
    switchActive(0);
    toast("PC 版已就绪 · 双击打开 · 右键操作");
  }catch(error){
    toast("启动失败："+error.message,true);
  }
}
window.addEventListener("DOMContentLoaded",start);
