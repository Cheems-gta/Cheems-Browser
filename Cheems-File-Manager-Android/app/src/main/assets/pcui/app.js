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
function isArchive(name) {
  return /\.(zip|zipx|7z|rar|rar5|ace|lha|lzh|zpaq|arc|arj|tar|tgz|gz|bz2|xz|zst|jar|apk|xapk|apks|apkm|lz|z|001|r\d\d|z\d\d)$/i.test(name);
 }

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

// Android preview touch input: emulate a desktop right click after a hold.
// A normal tap still selects a file and scrolling remains unchanged.
let suppressTouchClickUntil = 0;
function showFileActionsAt(index, item, x, y) {
  const pane = state.panes[index];
  switchActive(index);
  if (!pane.selected.has(item.path)) {
    pane.selected.clear();
    pane.selected.add(item.path);
  }
  showSelection(index);
  contextMenu(x, y, item);
}
function mobileActionsForSelection() {
  const pane = activePane();
  const selected = [...pane.selected];
  if (!selected.length) {
    toast("先点一下选中文件，再点「⋮ 文件操作」；也可以直接长按文件。");
    return;
  }
  if (selected.length !== 1) {
    toast("文件操作菜单一次对应一个文件；批量复制、移动可使用顶部按钮。");
    return;
  }
  const item = pane.items.find(v => v.path === selected[0]);
  if (!item) { toast("选中的文件已变化，请刷新目录"); return; }
  const anchor = document.getElementById("mobile-file-actions");
  const rect = anchor ? anchor.getBoundingClientRect() : null;
  const x = rect ? rect.left : window.innerWidth / 2;
  const y = rect ? rect.bottom + 3 : window.innerHeight / 3;
  contextMenu(x, y, item);
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
      if (Date.now() < suppressTouchClickUntil) {
        e.preventDefault();
        e.stopPropagation();
        return;
      }
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
      // The Android WebView may emit its own contextmenu after our hold timer.
      // Don't show the same menu twice.
      if (Date.now() < suppressTouchClickUntil) return;
      showFileActionsAt(index,item,e.clientX,e.clientY);
    });
    if (isAndroidBuild()) {
      let holdTimer = null;
      let touchX=0, touchY=0, holdTriggered=false;
      const cancelHold = () => {
        if (holdTimer !== null) clearTimeout(holdTimer);
        holdTimer = null;
      };
      row.addEventListener("touchstart", e => {
        cancelHold();
        holdTriggered=false;
        if (e.touches.length !== 1) return;
        touchX=e.touches[0].clientX;
        touchY=e.touches[0].clientY;
        const x=touchX,y=touchY;
        holdTimer=setTimeout(() => {
          holdTimer=null;
          holdTriggered=true;
          suppressTouchClickUntil=Date.now()+900;
          showFileActionsAt(index,item,x,y);
        }, 480);
      }, {passive:true});
      row.addEventListener("touchmove",e => {
        if (e.touches.length!==1 ||
            Math.abs(e.touches[0].clientX-touchX)>12 ||
            Math.abs(e.touches[0].clientY-touchY)>12) {
          cancelHold();
        }
      }, {passive:true});
      row.addEventListener("touchend",e => {
        cancelHold();
        if (holdTriggered) {
          e.preventDefault();
          holdTriggered=false;
        }
      }, {passive:false});
      row.addEventListener("touchcancel",cancelHold,{passive:true});
      row.addEventListener("dragstart",e=>e.preventDefault());
    }
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
async function extract(location="other") {
  try {
    const item=oneItem();
    if(item.isDir)throw new Error("请选择一个压缩文件");
    if(!isArchive(item.name))throw new Error("请选 ZIP / 7z / RAR / TAR 等支持的压缩文件");
    const target=location==="current"?activePane().path:state.panes[1-state.active].path;
    if(!confirm("解压："+item.name+"\n\n目标："+target+"\n\n将创建独立解压文件夹。"+
      "\n分卷请放在一起，选第一卷或任一可识别分卷。ZPAQ/LZH 需要 PC 已安装相应工具。"))return;
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

// Touch-friendly search: use an in-app dialog instead of JavaScript prompt(),
// because Android WebView does not necessarily surface JS prompt dialogs.
let searchPaneIndex = 0;
function search() {
  searchPaneIndex=state.active;
  const pane=state.panes[searchPaneIndex];
  if (!pane.path) {toast("当前目录尚未打开");return;}
  $("#search-overlay").classList.remove("hidden");
  $("#search-summary").textContent="将在「"+pane.path+"」及子文件夹中查找文件名";
  $("#search-results").replaceChildren();
  $("#search-query").value="";
  $("#search-query").focus();
}
async function executeSearch() {
  const query=$("#search-query").value.trim();
  if(query.length<2) {
    $("#search-summary").textContent="请输入至少两个字符再搜索。";
    $("#search-query").focus();
    return;
  }
  const index=searchPaneIndex;
  const folder=state.panes[index].path;
  const button=$("#search-submit");
  if(button.disabled)return;
  button.disabled=true;
  button.textContent="搜索中…";
  $("#search-summary").textContent="正在搜索「"+query+"」…";
  $("#search-results").replaceChildren();
  try {
    const result=await call("search",{path:folder,query});
    $("#search-summary").textContent=
      "搜索「"+query+"」· 找到 "+result.results.length+" 项 · 已扫描 "+result.scanned+" 项"+
      (result.truncated?"（已达到搜索上限）":"");
    const list=$("#search-results");
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
          toast("已定位："+file.name);
        }catch(error){toast(error.message,true)}
      });
      list.append(row);
    }
    if(!result.results.length) {
      const empty=document.createElement("p");
      empty.className="empty";
      empty.textContent="没有找到匹配项；可以尝试缩短搜索词。";
      list.append(empty);
    }
  }catch(error){
    $("#search-summary").textContent="搜索失败："+error.message;
    toast(error.message,true);
  }finally{
    button.disabled=false;
    button.textContent="搜索";
  }
}


/* V0.2: desktop context utilities and Android system sharing.
 * Browser messaging is already handled by the existing native bridge.
 */
function isAndroidBuild() {
  return !!window.AndroidFileApi;
}

async function shareSelected() {
  try {
    const paths=selectedPaths();
    const out=await guarded(()=>call("share",{paths}));
    if(out) toast(out.message ||
      (isAndroidBuild() ? "已打开系统分享菜单" : "已打开文件位置"));
  }catch(e){toast(e.message,true)}
}

async function fileProperties() {
  try {
    const item=oneItem();
    const out=await guarded(()=>call("properties",{path:item.path}));
    if(!out) return;
    alert("文件属性\n\n"+
      "名称："+out.name+"\n"+
      "位置："+out.path+"\n"+
      "类型："+out.kind+"\n"+
      "大小："+(out.isDir?"文件夹":formatBytes(out.size))+"\n"+
      "创建时间："+(out.created||"—")+"\n"+
      "修改时间："+(out.modified||"—")+"\n"+
      "只读："+(out.readonly?"是":"否"));
  }catch(e){toast(e.message,true)}
}

async function hashSelected() {
  try {
    const item=oneItem();
    if(item.isDir) throw new Error("SHA-256 目前只支持单个文件");
    const out=await guarded(()=>call("hash",{path:item.path}));
    if(!out)return;
    alert("工具 · SHA-256\n\n文件："+item.name+"\n\n"+out.sha256+
      "\n\n注意：哈希值用于对比文件是否相同，不代表文件安全。");
  }catch(e){toast(e.message,true)}
}

async function signatureInfo() {
  try {
    const item=oneItem();
    if(!/\.(exe|dll|msi)$/i.test(item.name))throw new Error("仅查看 EXE / DLL / MSI 签名信息");
    const out=await guarded(()=>call("signature",{path:item.path}));
    if(out)alert("数字签名信息\n\n文件："+item.name+"\n\n"+
      (out.status||"未检测到签名")+
      (out.subject?"\n\n证书使用者："+out.subject:"")+
      (out.issuer?"\n证书颁发者："+out.issuer:"")+
      (out.validUntil?"\n证书有效期至："+out.validUntil:"")+
      "\n\n证书信息不能单独证明程序安全。");
  }catch(e){toast(e.message,true)}
}

async function openWithSelected() {
  try {
    const item=oneItem();
    if(item.isDir)throw new Error("请选择一个文件");
    const out=await guarded(()=>call("openWith",{path:item.path}));
    if(out?.message)toast(out.message);
  }catch(e){toast(e.message,true)}
}

function getBookmarks() {
  try {
    const data=JSON.parse(localStorage.getItem("cfm.bookmarks.v02")||"[]");
    return Array.isArray(data)?data.filter(v=>typeof v==="string").slice(0,40):[];
  }catch(_){return[]}
}
function putBookmarks(items) {
  try { localStorage.setItem("cfm.bookmarks.v02",JSON.stringify(items.slice(0,40))); }
  catch(e){toast("无法保存书签："+e.message,true)}
}
function bookmarkCurrent() {
  let folder=activePane().path;
  if(!folder)return;
  try {
    if(activePane().selected.size===1){
      const item=oneItem();
      if(item.isDir)folder=item.path;
    }
  }catch(_){}
  const old=getBookmarks();
  if(old.includes(folder)){toast("该目录已经收藏");return}
  putBookmarks([...old,folder]);
  toast("已收藏："+folder);
}
function showBookmarks() {
  const folders=getBookmarks();
  if(!folders.length){toast("还没有书签，请先在右键菜单选择“添加书签”");return}
  const text=folders.map((v,i)=>(i+1)+". "+v).join("\n");
  const chosen=prompt("我的文件夹书签\n\n"+text+
    "\n\n输入数字打开，或输入“删除 数字”移除书签：","");
  if(!chosen)return;
  const trimmed=chosen.trim();
  const del=/^删除\s*(\d+)$/.exec(trimmed);
  if(del) {
    const n=Number(del[1])-1;
    if(n<0||n>=folders.length){toast("没有这个书签",true);return}
    const gone=folders.splice(n,1)[0];
    putBookmarks(folders);toast("已删除书签："+gone);
    return;
  }
  const n=Number(trimmed)-1;
  if(!Number.isInteger(n)||n<0||n>=folders.length){
    toast("请输入有效的书签序号",true);return;
  }
  guarded(()=>navigate(state.active,folders[n]));
}

async function htmlPreviewSelected() {
  try {
    const item=oneItem();
    if(!/\.html?$/i.test(item.name))throw new Error("请选择 HTML 或 HTM 文件");
    await openEditor(item.path);
    if(currentEditor && !state.previewVisible) togglePreview();
  }catch(e){toast(e.message,true)}
}

async function newHtml() {
  const parent=activePane().path;
  let name=prompt("新建 HTML 文件名：","index.html");
  if(name===null)return;
  if(!/^[^\\/:*?"<>|]+\.html?$/i.test(name)){
    toast("文件名必须以 .html 或 .htm 结尾",true);return;
  }
  await mutate("newHtml",{path:parent,name},"已创建 HTML 文件");
}

let terminalPath="";
function openTerminal(path) {
  terminalPath=path||activePane().path;
  if(!terminalPath)return;
  $("#terminal-overlay").classList.remove("hidden");
  $("#terminal-path").textContent=terminalPath;
  $("#terminal-output").textContent=(isAndroidBuild()?"Android 应用身份终端":"Windows CMD")+
    " · "+terminalPath+"\n"+
    "命令由你输入，以普通用户权限运行，不能撤销执行的修改。\n"+
    "请勿在终端输入来源不明的命令。\n\n";
  $("#terminal-input").value="";
  $("#terminal-input").focus();
}
function closeTerminal() {
  $("#terminal-overlay").classList.add("hidden");
}
async function runTerminal() {
  const input=$("#terminal-input");
  const command=input.value.trim();
  if(!command)return;
  input.value="";
  const output=$("#terminal-output");
  output.textContent+="> "+command+"\n";
  if(command==="clear"||command==="cls"){
    output.textContent="";
    return;
  }
  try {
    const out=await guarded(()=>call("shell",{cwd:terminalPath,command}));
    if(out){
      output.textContent+=(out.output||"(无输出)")+"\n"+(out.exitCode?"[退出码 "+out.exitCode+"]\n":"")+"\n";
      if(output.textContent.length>65000)
        output.textContent=output.textContent.slice(-50000);
      output.scrollTop=output.scrollHeight;
    }
  }catch(e){output.textContent+="错误："+e.message+"\n"}
}
async function externalTerminal() {
  try {
    const out=await guarded(()=>call("terminalWindow",{cwd:terminalPath}));
    if(out)toast(out.message||"终端已打开");
  }catch(e){toast(e.message,true)}
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
    ["创建 ZIP",makeZip]
  ];
  if (!item.isDir && isArchive(item.name)) {
    actions.push(["📦 直接解压 · 当前目录",()=>extract("current")]);
    actions.push(["📦 直接解压 · 另一栏",()=>extract("other")]);
  }
  if(!item.isDir && /\.html?$/i.test(item.name)){
    actions.push(["〈/〉 HTML 源码 / 安全预览",htmlPreviewSelected]);
  }
  actions.push([isAndroidBuild()?"分享给微信 / QQ":"分享 / 定位文件",shareSelected]);
  actions.push(["⌘ 在当前文件夹打开终端",()=>openTerminal(item.isDir?item.path:activePane().path)]);
  actions.push(["＋ 创建 HTML 文件",newHtml]);
  if(!isAndroidBuild()) {
    actions.push(["属性",fileProperties]);
    if(!item.isDir)actions.push(["工具 · SHA-256",hashSelected]);
    if(!item.isDir && /\.(exe|dll|msi)$/i.test(item.name))
      actions.push(["EXE 数字签名信息",signatureInfo]);
    if(!item.isDir)actions.push(["打开方式…",openWithSelected]);
    actions.push(["添加书签",bookmarkCurrent]);
  }
  actions.push(["刷新",refreshBoth]);
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
  const touchActions = $("#mobile-file-actions");
  if (touchActions) touchActions.addEventListener("click",e => {e.stopPropagation();mobileActionsForSelection();});
  $("#refresh").addEventListener("click",refreshBoth);
  const terminalButton=$("#terminal-button");
  if(terminalButton)terminalButton.addEventListener("click",()=>openTerminal(activePane().path));
  const htmlButton=$("#html-button");
  if(htmlButton)htmlButton.addEventListener("click",htmlPreviewSelected);
  const closeTerminalButton=$("#close-terminal");
  if(closeTerminalButton)closeTerminalButton.addEventListener("click",closeTerminal);
  const runTerminalButton=$("#run-terminal");
  if(runTerminalButton)runTerminalButton.addEventListener("click",runTerminal);
  const termInput=$("#terminal-input");
  if(termInput)termInput.addEventListener("keydown",e=>{
    if(e.key==="Enter"){e.preventDefault();runTerminal();}
  });
  const externalTerminalButton=$("#external-terminal");
  if(externalTerminalButton) {
    externalTerminalButton.classList.toggle("hidden",isAndroidBuild());
    externalTerminalButton.addEventListener("click",externalTerminal);
  }
  const shareButton=$("#share");
  if(shareButton) shareButton.addEventListener("click",shareSelected);
  const bookmarkButton=$("#bookmarks");
  if(bookmarkButton) bookmarkButton.addEventListener("click",showBookmarks);
  $("#search").addEventListener("click",search);
  $("#search-submit").addEventListener("click",executeSearch);
  $("#search-query").addEventListener("keydown",e=>{if(e.key==="Enter"){e.preventDefault();executeSearch();}});
  $("#up").addEventListener("click",()=>up());
  $("#copy").addEventListener("click",()=>transfer(false));
  $("#move").addEventListener("click",()=>transfer(true));
  $("#new").addEventListener("click",makeFolder);
  $("#rename").addEventListener("click",rename);
  $("#delete").addEventListener("click",recycle);
  $("#edit").addEventListener("click",()=>{try{const item=oneItem();openItem(item)}catch(e){toast(e.message,true)}});
  $("#extract").addEventListener("click",()=>extract("other"));
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
      if(!$("#terminal-overlay").classList.contains("hidden")){closeTerminal();return;}
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
