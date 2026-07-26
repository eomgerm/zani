const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(process.env.ZANI_ROOT || process.cwd());
const outputDir = path.resolve(process.env.ZANI_OUTPUT_DIR || path.join(root, "public"));

function walk(directory, extensions) {
  if (!fs.existsSync(directory)) return [];
  const result = [];
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (["node_modules", ".next", "build", "dist", "coverage"].includes(entry.name)) continue;
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) result.push(...walk(fullPath, extensions));
    else if (extensions.some((extension) => entry.name.endsWith(extension))) result.push(fullPath);
  }
  return result;
}

function relative(file) {
  return path.relative(root, file).replaceAll("\\", "/");
}

function escapeHtml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function shortName(file) {
  const parts = file.split("/");
  return parts.length > 4 ? `…/${parts.slice(-4).join("/")}` : file;
}

function frontendLayer(file) {
  if (file.includes("/app/")) return "App Router";
  if (file.includes("/presentation/")) return "Presentation";
  if (file.includes("/infrastructure/")) return "Infrastructure";
  if (file.includes("/features/")) return "Feature";
  if (file.includes("/shared/")) return "Shared";
  return "Other";
}

function frontendDomain(file) {
  if (file.includes("/domains/auth/")) return "auth";
  if (file.includes("/domains/lecture/")) return "lecture";
  if (file.includes("/domains/user/")) return "user";
  if (file.includes("/features/media/")) return "media";
  if (file.includes("/shared/")) return "shared";
  return "app";
}

function resolveFrontendImport(source, specifier, knownFiles) {
  if (!specifier.startsWith(".") && !specifier.startsWith("@/")) return null;
  const sourceDirectory = path.posix.dirname(source);
  const base = specifier.startsWith("@/")
    ? `fe/src/${specifier.slice(2)}`
    : path.posix.normalize(path.posix.join(sourceDirectory, specifier));
  const candidates = [
    base,
    `${base}.ts`,
    `${base}.tsx`,
    `${base}/index.ts`,
    `${base}/index.tsx`,
  ];
  return candidates.find((candidate) => knownFiles.has(candidate)) || null;
}

function analyzeFrontend() {
  const files = walk(path.join(root, "fe", "src"), [".ts", ".tsx"])
    .map(relative)
    .filter((file) => !file.includes(".test."));
  const knownFiles = new Set(files);
  const nodes = files.map((file) => ({
    id: file,
    name: shortName(file),
    full: file,
    layer: frontendLayer(file),
    domain: frontendDomain(file),
  }));
  const edges = [];
  for (const file of files) {
    const source = fs.readFileSync(path.join(root, file), "utf8");
    const importPattern = /(?:import|export)\s+(?:[\s\S]*?\s+from\s+)?["']([^"']+)["']/g;
    for (const match of source.matchAll(importPattern)) {
      const target = resolveFrontendImport(file, match[1], knownFiles);
      if (target && target !== file) {
        const importedName = path.posix.basename(target).replace(/\.(tsx?|jsx?)$/, "");
        edges.push({ from: file, to: target, type: "import", label: `import ${importedName}` });
      }
    }
    for (const match of source.matchAll(/fetch\s*\(\s*`([^`]+)`/g)) {
      edges.push({ from: file, to: `API ${match[1]}`, type: "api", label: "fetch()", external: true });
    }
  }
  const externalNodes = [...new Set(edges.filter((edge) => edge.external).map((edge) => edge.to))]
    .map((id) => ({ id, name: id.replace(/\$\{[^}]+\}/g, "{value}"), full: id, layer: "External", domain: "api" }));
  return { title: "ZANI Frontend", nodes: [...nodes, ...externalNodes], edges };
}

function backendLayer(file) {
  if (file.includes("/presentation/")) return "Presentation";
  if (file.includes("/application/")) return "Application";
  if (file.includes("/domain/")) return "Domain";
  if (file.includes("/infrastructure/")) return "Infrastructure";
  if (file.includes("/common/")) return "Common";
  return "Bootstrap";
}

function backendDomain(file) {
  const marker = "backend/src/main/java/com/a105/zani/";
  const remainder = file.split(marker)[1] || "";
  return remainder.split("/")[0] || "bootstrap";
}

function analyzeBackend() {
  const files = walk(path.join(root, "backend", "src", "main", "java"), [".java"])
    .map(relative);
  const byClass = new Map(files.map((file) => [path.posix.basename(file, ".java"), file]));
  const nodes = files.map((file) => ({
    id: file,
    name: path.posix.basename(file),
    full: file,
    layer: backendLayer(file),
    domain: backendDomain(file),
  }));
  const edges = [];
  for (const file of files) {
    const source = fs.readFileSync(path.join(root, file), "utf8");
    for (const match of source.matchAll(/^import\s+com\.a105\.zani\.[\w.]+\.([A-Z]\w+);/gm)) {
      const target = byClass.get(match[1]);
      if (target && target !== file) {
        const className = match[1];
        let label = `import ${className}`;
        if (new RegExp(`implements\\s+[^\\n{]*\\b${className}\\b`).test(source)) {
          label = `implements ${className}`;
        } else if (new RegExp(`extends\\s+${className}\\b`).test(source)) {
          label = `extends ${className}`;
        } else if (new RegExp(`private\\s+final\\s+${className}\\s+`).test(source)) {
          label = `DI ${className}`;
        }
        edges.push({ from: file, to: target, type: "import", label });
      }
    }
    const requestBase = source.match(/@RequestMapping\("([^"]+)"\)/)?.[1] || "";
    for (const match of source.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping(?:\("([^"]*)"\))?/g)) {
      const endpoint = `${match[1].toUpperCase()} ${requestBase}${match[2] || ""}`;
      edges.push({ from: `API ${endpoint}`, to: file, type: "api", label: endpoint, external: true });
    }
  }
  const externalNodes = [...new Set(edges.filter((edge) => edge.external).map((edge) => edge.from))]
    .map((id) => ({ id, name: id.slice(4), full: id, layer: "HTTP API", domain: "api" }));
  return { title: "ZANI Backend", nodes: [...externalNodes, ...nodes], edges };
}

function renderMap(data, fileName) {
  const payload = JSON.stringify(data).replaceAll("<", "\\u003c");
  const layers = [...new Set(data.nodes.map((node) => node.layer))];
  const domains = [...new Set(data.nodes.map((node) => node.domain))].sort();
  const html = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(data.title)} 파일 관계도</title>
<style>
:root{color-scheme:light dark;--bg:#f7f8fb;--panel:#fff;--text:#172033;--muted:#667085;--border:#d7dce5;--accent:#4f46e5;--soft:#eef2ff}
@media(prefers-color-scheme:dark){:root{--bg:#10131a;--panel:#181d27;--text:#eef2f8;--muted:#9aa4b2;--border:#303846;--accent:#8b83ff;--soft:#242640}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,"Segoe UI",sans-serif}
header{position:sticky;top:0;z-index:5;background:color-mix(in srgb,var(--bg) 92%,transparent);backdrop-filter:blur(10px);border-bottom:1px solid var(--border);padding:18px 24px}
h1{font-size:20px;margin:0 0 12px}.controls{display:flex;gap:8px;flex-wrap:wrap}.controls input,.controls select{background:var(--panel);color:var(--text);border:1px solid var(--border);border-radius:8px;padding:9px 11px}
.controls input{min-width:260px;flex:1}.stats{color:var(--muted);font-size:13px;margin-top:9px}
main{display:grid;grid-template-columns:minmax(0,1fr) 330px;gap:18px;padding:18px;align-items:start}.canvas{position:relative;min-height:720px;background:var(--panel);border:1px solid var(--border);border-radius:14px;overflow:auto}
svg{display:block;min-width:980px;width:100%;min-height:720px}.edge{stroke:var(--border);stroke-width:1;opacity:.42}.edge.api{stroke:var(--accent);stroke-width:2}.edge.active{stroke:var(--accent);stroke-width:3;opacity:1}.edge.dim{opacity:.035}
.node rect{fill:var(--soft);stroke:var(--border);rx:8}.node.api rect{fill:color-mix(in srgb,var(--accent) 15%,var(--panel))}.node text{fill:var(--text);font-size:11px;text-anchor:middle;pointer-events:none}.node{cursor:pointer}.node.active rect{stroke:var(--accent);stroke-width:3}.node.dim{opacity:.12}
.edge-label{fill:var(--muted);font-size:9px;text-anchor:middle;paint-order:stroke;stroke:var(--panel);stroke-width:4px;stroke-linejoin:round;opacity:0;pointer-events:none}.edge-label.visible{opacity:1;fill:var(--text)}.edge-label.dim{opacity:0}
aside{position:sticky;top:126px;background:var(--panel);border:1px solid var(--border);border-radius:14px;padding:16px}aside h2{font-size:16px;margin:0 0 8px}aside p{color:var(--muted);font-size:13px;overflow-wrap:anywhere;margin:6px 0}
.legend{display:flex;gap:8px;flex-wrap:wrap;margin-top:12px}.tag{font-size:12px;padding:4px 7px;border:1px solid var(--border);border-radius:999px;color:var(--muted)}
@media(max-width:850px){main{grid-template-columns:1fr}aside{position:static}.canvas{min-height:620px}}
</style>
</head>
<body>
<header><h1>${escapeHtml(data.title)} 파일 관계도</h1>
<div class="controls"><input id="search" type="search" placeholder="파일명 또는 경로 검색" aria-label="파일 검색">
<select id="layer" aria-label="레이어 선택"><option value="">모든 레이어</option>${layers.map((layer) => `<option>${escapeHtml(layer)}</option>`).join("")}</select>
<select id="domain" aria-label="도메인 선택"><option value="">모든 도메인</option>${domains.map((domain) => `<option>${escapeHtml(domain)}</option>`).join("")}</select>
<label><input id="labels" type="checkbox"> 모든 관계명 표시</label></div>
<div class="stats" id="stats"></div></header>
<main><section class="canvas"><svg id="graph" role="img" aria-label="${escapeHtml(data.title)} 파일 연결 그래프"></svg></section>
<aside><h2 id="detail-name">파일을 선택하세요</h2><p id="detail-path">직접 연결된 파일만 강조됩니다.</p><p id="detail-meta"></p><div class="legend">${layers.map((layer) => `<span class="tag">${escapeHtml(layer)}</span>`).join("")}</div></aside></main>
<script>
const data=${payload};const svg=document.getElementById("graph"),NS="http://www.w3.org/2000/svg";let selected=null;
const search=document.getElementById("search"),layer=document.getElementById("layer"),domain=document.getElementById("domain"),labels=document.getElementById("labels");
function matches(n){const q=search.value.trim().toLowerCase();return(!q||n.full.toLowerCase().includes(q)||n.name.toLowerCase().includes(q))&&(!layer.value||n.layer===layer.value)&&(!domain.value||n.domain===domain.value)}
function render(){svg.replaceChildren();const visible=data.nodes.filter(matches),ids=new Set(visible.map(n=>n.id)),edges=data.edges.filter(e=>ids.has(e.from)&&ids.has(e.to));const groups=[...new Set(visible.map(n=>n.layer))];const width=1180,columns=Math.max(1,Math.floor((width-40)/170));let cursorY=15;const layouts=groups.map(g=>{const items=visible.filter(n=>n.layer===g),rows=Math.max(1,Math.ceil(items.length/columns)),layout={g,items,y:cursorY,h:48+rows*54};cursorY+=layout.h;return layout}),height=Math.max(720,cursorY+20);svg.setAttribute("viewBox",\`0 0 \${width} \${height}\`);
const pos=new Map();layouts.forEach(({g,items,y})=>{const title=document.createElementNS(NS,"text");title.setAttribute("x",20);title.setAttribute("y",y+20);title.setAttribute("fill","var(--muted)");title.textContent=g;svg.append(title);items.forEach((n,i)=>{const col=i%columns,row=Math.floor(i/columns);pos.set(n.id,{x:25+col*170,y:y+34+row*54,w:150,h:40})})});
edges.forEach((e,i)=>{const a=pos.get(e.from),b=pos.get(e.to);if(!a||!b)return;const x1=a.x+a.w/2,y1=a.y+a.h/2,x2=b.x+b.w/2,y2=b.y+b.h/2;const line=document.createElementNS(NS,"line");line.setAttribute("x1",x1);line.setAttribute("y1",y1);line.setAttribute("x2",x2);line.setAttribute("y2",y2);line.setAttribute("class","edge "+e.type);line.dataset.a=e.from;line.dataset.b=e.to;line.dataset.i=i;svg.append(line);const text=document.createElementNS(NS,"text");text.setAttribute("x",(x1+x2)/2);text.setAttribute("y",(y1+y2)/2-5);text.setAttribute("class","edge-label");text.dataset.a=e.from;text.dataset.b=e.to;text.dataset.i=i;text.textContent=e.label||e.type;svg.append(text)});
visible.forEach(n=>{const p=pos.get(n.id),g=document.createElementNS(NS,"g");g.setAttribute("class","node "+(n.layer.includes("API")||n.layer==="External"?"api":""));g.dataset.id=n.id;const rect=document.createElementNS(NS,"rect");rect.setAttribute("x",p.x);rect.setAttribute("y",p.y);rect.setAttribute("width",p.w);rect.setAttribute("height",p.h);const text=document.createElementNS(NS,"text");text.setAttribute("x",p.x+p.w/2);text.setAttribute("y",p.y+25);text.textContent=n.name.length>24?n.name.slice(0,23)+"…":n.name;g.append(rect,text);g.onclick=()=>{selected=selected===n.id?null:n.id;highlight();if(selected){document.getElementById("detail-name").textContent=n.name;document.getElementById("detail-path").textContent=n.full;document.getElementById("detail-meta").textContent=n.layer+" · "+n.domain}};svg.append(g)});
document.getElementById("stats").textContent=\`파일 \${visible.length}개 · 연결 \${edges.length}개\`;highlight()}
function highlight(){const connected=new Set(selected?[selected]:[]);svg.querySelectorAll(".edge").forEach(e=>{const active=selected&&(e.dataset.a===selected||e.dataset.b===selected);if(active){connected.add(e.dataset.a);connected.add(e.dataset.b)}e.classList.toggle("active",!!active);e.classList.toggle("dim",!!selected&&!active)});svg.querySelectorAll(".edge-label").forEach(e=>{const active=selected&&(e.dataset.a===selected||e.dataset.b===selected);e.classList.toggle("visible",labels.checked||!!active);e.classList.toggle("dim",!!selected&&!active)});svg.querySelectorAll(".node").forEach(n=>{n.classList.toggle("active",n.dataset.id===selected);n.classList.toggle("dim",!!selected&&!connected.has(n.dataset.id))})}
[search,layer,domain].forEach(el=>el.addEventListener("input",()=>{selected=null;render()}));labels.addEventListener("change",highlight);render();
</script></body></html>`;
  fs.mkdirSync(outputDir, { recursive: true });
  fs.writeFileSync(path.join(outputDir, fileName), html);
}

renderMap(analyzeFrontend(), "frontend-map.html");
renderMap(analyzeBackend(), "backend-map.html");
fs.writeFileSync(
  path.join(outputDir, "index.html"),
  `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ZANI Architecture</title><style>body{font-family:system-ui;margin:40px;background:#f7f8fb;color:#172033}main{max-width:720px;margin:auto}a{display:block;padding:18px;margin:12px 0;background:white;border:1px solid #d7dce5;border-radius:12px;color:#3730a3;text-decoration:none}</style><main><h1>ZANI Architecture Maps</h1><a href="frontend-map.html">Frontend 파일 관계도</a><a href="backend-map.html">Backend 파일 관계도</a></main></html>`,
);
console.log(`Generated architecture maps in ${relative(outputDir)}`);
