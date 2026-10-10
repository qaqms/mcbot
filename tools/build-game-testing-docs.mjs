import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = fs.readFileSync(path.join(root, "docs/REAL-GAME-TESTING.md"), "utf8");
const revision = "2026-10-11-main-60e523f-essential-v1";
const cases = [];
const escape = (text) => text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
const inline = (text) => escape(text).replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
let html = "", title = "", code = null, list = null, paragraph = [], current = null;
function flush() {
  if (paragraph.length) { html += `<p>${inline(paragraph.join(" "))}</p>\n`; paragraph = []; }
  if (list) { html += `</${list}>\n`; list = null; }
}
function feedback(id) {
  return `<fieldset class="feedback" data-id="${id}">
<legend>${id} 反馈</legend>
<div class="fields">
<label>结果<select data-field="result"><option>未测</option><option>通过</option><option>失败</option><option>阻塞</option></select></label>
<label>指定工具是否执行<select data-field="execution"><option>看不清</option><option>执行了</option><option>没执行</option><option>不适用</option></select></label>
<label>本地时间和时区<input data-field="time" placeholder="2026-10-11 20:15 +08:00"></label>
<label>问题编号<input data-field="issue" placeholder="问题01"></label>
</div>
<label>实际现象和前后数量<textarea data-field="actual" rows="3" placeholder="填写你实际看到的；多个子场景分别写结果"></textarea></label>
<label>原样指令 错误提示 附件名<textarea data-field="evidence" rows="3" placeholder="日志/截图/录像文件名；这里不会上传附件"></textarea></label>
<label>维护者回复 处理版本和复测<textarea data-field="retest" rows="2" placeholder="保留原失败，追加新包哈希、复测时间和结果"></textarea></label>
</fieldset>`;
}
function closeCase() {
  if (current) { flush(); html += feedback(current.id) + "</div></details>\n"; current = null; }
}
for (const line of source.split(/\r?\n/)) {
  if (line.startsWith("```")) {
    if (code !== null) {
      html += `<pre tabindex="0"><code>${escape(code.join("\n"))}</code></pre>\n`; code = null;
    } else { flush(); code = []; }
    continue;
  }
  if (code !== null) { code.push(line); continue; }
  if (!line.trim()) { flush(); continue; }
  const heading = /^(#{1,3}) (.+)$/.exec(line);
  if (heading) {
    flush();
    if (heading[1].length === 1) { title = heading[2]; continue; }
    const item = /^([TXB]\d{2}) (.+)$/.exec(heading[2]);
    if (item) {
      closeCase();
      current = { id: item[1], title: item[2] };
      cases.push(current);
      html += `<details class="test" id="${current.id}"><summary><span class="case-id">${current.id}</span> ${inline(current.title)} <span class="badge" data-badge="${current.id}">未测</span></summary><div class="case-body">\n`;
    } else {
      closeCase();
      html += `<h${heading[1].length}>${inline(heading[2])}</h${heading[1].length}>\n`;
    }
    continue;
  }
  const item = /^(?:- |(\d+)\. )(.+)$/.exec(line);
  if (item) {
    if (paragraph.length) { html += `<p>${inline(paragraph.join(" "))}</p>\n`; paragraph = []; }
    const type = item[1] ? "ol" : "ul";
    if (list && list !== type) { html += `</${list}>\n`; list = null; }
    if (!list) { html += `<${type}>\n`; list = type; }
    html += `<li>${inline(item[2])}</li>\n`;
  } else {
    if (list) { html += `</${list}>\n`; list = null; }
    paragraph.push(line);
  }
}
flush(); closeCase();
const expectedIds = [...Array.from({ length: 18 }, (_, i) => `T${String(i + 1).padStart(2, "0")}`), "B01"];
if (code !== null || cases.map(c => c.id).join(",") !== expectedIds.join(",")) throw new Error("Manual structure invalid");

const script = String.raw`
const cases = CASES;
const revision = REVISION;
const storageKey = "mcbot-testing-" + revision;
const envNames = ["tester","date","timezone","jar","minecraft","loader","api","java","model","mode","mods","connector"];
const fieldNames = ["result","execution","time","issue","actual","evidence","retest"];
const message = document.getElementById("message");
let saveTimer;
function report() {
  const env = Object.fromEntries(envNames.map(name => [name, document.getElementById("env-" + name).value]));
  const rows = cases.map(c => {
    const fieldset = document.querySelector('[data-id="' + c.id + '"]');
    return { ...c, ...Object.fromEntries(fieldNames.map(name => [name, fieldset.querySelector('[data-field="' + name + '"]').value])) };
  });
  return { schema: 1, revision, savedAt: new Date().toISOString(), env, cases: rows };
}
function update() {
  const data = report();
  const count = {"未测":0,"通过":0,"失败":0,"阻塞":0};
  data.cases.forEach(c => {
    count[c.result]++;
    const badge = document.querySelector('[data-badge="' + c.id + '"]');
    badge.textContent = c.result;
    badge.dataset.result = c.result;
  });
  document.getElementById("counts").textContent = Object.entries(count).map(([k,v]) => k + " " + v).join(" · ");
}
function restore(data) {
  if (data.schema !== 1 || data.revision !== revision || !Array.isArray(data.cases)) throw new Error("反馈格式或手册版本不同，请使用对应手册打开。");
  envNames.forEach(name => {
    if (typeof data.env?.[name] === "string") document.getElementById("env-" + name).value = data.env[name];
  });
  data.cases.forEach(c => {
    if (!cases.some(known => known.id === c.id)) return;
    const fieldset = document.querySelector('[data-id="' + c.id + '"]');
    fieldNames.forEach(name => {
      if (typeof c[name] !== "string") return;
      const field = fieldset.querySelector('[data-field="' + name + '"]');
      if (field.tagName === "SELECT" && !Array.from(field.options).some(o => o.value === c[name])) return;
      field.value = c[name];
    });
  });
  update();
}
function save() {
  try { localStorage.setItem(storageKey, JSON.stringify(report())); message.textContent = "已在本机浏览器保存。结束时请导出反馈文件。"; }
  catch { message.textContent = "浏览器没有允许本机保存，请用导出反馈保留填写内容。"; }
  update();
}
function download(text, filename, type) {
  const url = URL.createObjectURL(new Blob([text], {type}));
  const anchor = document.createElement("a");
  anchor.href = url; anchor.download = filename; anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
document.getElementById("json").addEventListener("click", () => {
  save(); download(JSON.stringify(report(), null, 2), "mcbot-feedback-" + new Date().toISOString().slice(0,10) + ".json", "application/json");
});
document.getElementById("markdown").addEventListener("click", () => {
  const data = report();
  const text = ["# mcbot 实机反馈", "", "手册版本：" + revision, "导出时间：" + data.savedAt, "",
    ...envNames.map(n => n + "：" + data.env[n]), "", ...data.cases.flatMap(c => [
      "## " + c.id + " " + c.title,
      "结果：" + c.result + "；指定工具：" + c.execution, "时间：" + c.time + "；问题编号：" + c.issue,
      "实际现象和数量：\n" + c.actual, "指令错误及附件：\n" + c.evidence, "回复与复测：\n" + c.retest, ""
    ])].join("\n");
  download("\uFEFF" + text, "mcbot-feedback.md", "text/markdown;charset=utf-8"); save();
});
document.getElementById("import").addEventListener("change", async event => {
  const file = event.target.files[0]; if (!file) return;
  if (file.size > 2 * 1024 * 1024) { message.textContent = "文件超过2MB，请选择导出的反馈JSON。"; return; }
  try {
    const data = JSON.parse(await file.text());
    if (!confirm("导入会替换对应项的当前填写内容，是否继续？")) return;
    restore(data); save(); message.textContent = "已导入反馈。";
  } catch (error) { message.textContent = "导入失败：" + error.message; }
  event.target.value = "";
});
document.getElementById("expand").addEventListener("click", () => document.querySelectorAll("details.test").forEach(d => d.open = true));
document.getElementById("collapse").addEventListener("click", () => document.querySelectorAll("details.test").forEach(d => d.open = false));
document.getElementById("print").addEventListener("click", () => { document.querySelectorAll("details").forEach(d => d.open = true); window.print(); });
document.querySelectorAll("nav a").forEach(link => link.addEventListener("click", () => {
  const item = document.getElementById(link.hash.slice(1)); if (item) item.open = true;
}));
document.querySelectorAll("input:not([type=file]),textarea,select").forEach(field => field.addEventListener("input", () => {
  clearTimeout(saveTimer); update(); saveTimer = setTimeout(save, 250);
}));
try { const saved = localStorage.getItem(storageKey); if (saved) restore(JSON.parse(saved)); }
catch { message.textContent = "本机记录无法恢复。可以导入之前保存的反馈JSON。"; }
update();
`;
const envFields = [
  ["tester","测试者",""], ["date","本轮日期",""], ["timezone","时区","+08:00"],
  ["jar","测试包 SHA256","9d1260f975bc7f4005f63561c598fa5cd9f52c562fe869d2bbf0f8d916935b38"],
  ["minecraft","Minecraft","1.21.11"], ["loader","Fabric Loader","0.19.5"],
  ["api","Fabric API","0.141.6+1.21.11"], ["java","实际 Java 版本",""],
  ["model","模型名 不填密钥",""], ["mode","单人或联机",""],
  ["mods","其他模组 数据包",""], ["connector","连接器版本 未装填未装",""]
];
const page = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>mcbot 首轮实机必测与反馈</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#fff;color:#202124;font:16px/1.7 "Microsoft YaHei","Noto Sans CJK SC",sans-serif;letter-spacing:0}
main{max-width:1060px;margin:auto;padding:28px 30px 72px}h1{font-size:28px;line-height:1.35;margin:20px 0}h2{font-size:22px;margin:38px 0 16px}
p{margin:12px 0}li{margin:8px 0}a{color:#176451}button,.import-label{font:inherit;cursor:pointer;padding:7px 12px;border:1px solid #b8c2bd;border-radius:4px;background:#f5f8f6;color:#163f33}
button:focus-visible,summary:focus-visible,a:focus-visible{outline:3px solid #176451;outline-offset:3px}
.toolbar{display:flex;gap:8px;flex-wrap:wrap;border-bottom:1px solid #ddd;padding-bottom:16px}.toolbar .import-label{margin:0;display:flex;align-items:center}
.toolbar button.primary{background:#176451;color:#fff}#message{color:#515b56;font-size:14px;min-height:24px}
.environment{padding:12px 0 22px;border-bottom:1px solid #ddd}.environment>summary{cursor:pointer;font-size:18px;font-weight:600}.fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px 20px}
label{display:block;font-size:14px;margin:10px 0}input,select,textarea{display:block;width:100%;font:inherit;line-height:1.5;padding:9px;margin-top:5px;border:1px solid #aeb9b2;border-radius:4px;background:#fff;color:#202124}
textarea{resize:vertical}input{min-width:0}code{overflow-wrap:anywhere;font:14px/1.6 Consolas,"Microsoft YaHei",monospace}
pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f4f5f5;border-left:3px solid #c6ceca;padding:14px 16px;margin:14px 0}
.test{border-bottom:1px solid #d9d9d9}.test summary{cursor:pointer;padding:16px 0;line-height:1.55;font-weight:600}
.case-id{color:#176451}.badge{font-size:13px;font-weight:400;float:right;margin:3px 0 3px 16px;color:#606963}
.badge[data-result="失败"]{color:#ac2727}.badge[data-result="通过"]{color:#176451}.badge[data-result="阻塞"]{color:#755100}
.case-body{padding:0 4px 22px}.feedback{border:0;border-top:1px dashed #aeb9b2;padding:14px 0 0;margin:20px 0 0}legend{font-weight:600;padding:0 8px 0 0}
nav{display:flex;flex-wrap:wrap;gap:8px 14px;margin:18px 0}#counts{font-weight:600;margin:10px 0}.print-only{display:none}
@media(max-width:640px){main{padding:18px 16px 48px}h1{font-size:24px}h2{font-size:20px}.fields{grid-template-columns:minmax(0,1fr)}.badge{float:none;display:inline-block;margin-left:8px}summary{overflow-wrap:anywhere}}
@media print{body{font-size:11pt}main{max-width:none;padding:0}.toolbar,nav,#message{display:none}pre{background:none}details.test{break-inside:auto}summary{break-after:avoid}.feedback{break-inside:avoid}textarea{min-height:55px}h2{break-after:avoid}input,textarea{border-color:#aaa}.print-only{display:block}}
</style></head><body><main>
<h1>${inline(title)}</h1>
<div class="toolbar" aria-label="反馈操作">
<button id="json" class="primary">导出反馈 JSON</button><button id="markdown">导出文字报告</button>
<label class="import-label">导入反馈<input id="import" type="file" accept=".json,application/json" style="display:none"></label>
<button id="expand">展开全部</button><button id="collapse">收起全部</button><button id="print">打印手册</button>
</div>
<p id="message" role="status">填写保存在本机浏览器。每次结束请导出反馈文件交回维护者。</p>
<details class="environment"><summary>本轮环境填写</summary><p>预填版本是要求值，请改为你实际安装的版本；不要填写密钥或令牌。</p>
<div class="fields">${envFields.map(([id,label,value]) => `<label>${label}<input id="env-${id}" value="${escape(value)}"></label>`).join("\n")}</div></details>
<div id="counts" role="status"></div>
<nav aria-label="测试项目">${cases.map(c => `<a href="#${c.id}">${c.id}</a>`).join(" ")}</nav>
${html}
</main><script>${script.replace("const cases = CASES;", `const cases = ${JSON.stringify(cases)};`).replace("const revision = REVISION;", `const revision = ${JSON.stringify(revision)};`)}</script></body></html>`;
fs.writeFileSync(path.join(root, "dist/game-testing.html"), page, "utf8");
const template = `# mcbot 实机测试反馈模板

本表初始全部未测。操作步骤见 docs/REAL-GAME-TESTING.md；方便填写的浏览器版为 dist/game-testing.html。
每轮复制本模板作为自己的记录，原失败不删，修复后追加版本和复测结果。

## 本轮环境

- 测试者：
- 日期和时区：
- 单人/联机：
- 测试包SHA256：
- Minecraft / Loader / Fabric API / Java实际版本：
- 模型名（不填密钥）：
- 其他模组或数据包：
- 连接器版本（没有就填未装）：
- 当前阻塞事项：

## 逐项记录

结果选 未测 / 通过 / 失败 / 阻塞；指定工具选 执行了 / 没执行 / 看不清 / 不适用。
多子场景各写结果，不用一个通过覆盖没做的子项。

${cases.map(c => `### ${c.id} ${c.title}

- 结果：未测
- 指定工具是否执行：看不清
- 本地时间和时区：
- 问题编号：
- 实际现象及前后数量：
- 原样指令和错误提示：
- 截图/录像/日志附件名：
- 维护者回复、处理版本与复测：
`).join("\n")}
## 问题详情

按手册末尾的问题模板另写；同一问题沿用问题编号，附对应时间段日志。
不要提交密钥、令牌、配置文件、日志或测试世界到源码仓库。
`;
fs.mkdirSync(path.join(root, "docs/templates"), { recursive: true });
fs.writeFileSync(path.join(root, "docs/templates/GAME-TEST-FEEDBACK.md"), template, "utf8");
console.log(`Generated handbook and feedback template for ${cases.length} test cases.`);
