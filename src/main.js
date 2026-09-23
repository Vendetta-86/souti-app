// 主逻辑：UI 绑定、题库加载、搜题流程

import { QuestionIndex } from './matcher.js';
import * as bank from './questionBank.js';
import { ocrImage } from './ocr.js';
import { captureScreen, pickImage } from './capture.js';
import { Cropper } from './crop.js';

const $ = (id) => document.getElementById(id);
const index = new QuestionIndex();
let bankLoaded = false;

async function showCapturedImage(imageBlob) {
  currentImageBlob = imageBlob;
  $('tab-search').classList.remove('hidden');
  $('tab-bank').classList.add('hidden');
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === 'search'));
  await cropper.loadImage(currentImageBlob);
  $('crop-wrap').classList.remove('hidden');
  $('results').classList.add('hidden');
}

// ---------- 题库 ----------

async function refreshBankStatus() {
  const n = await bank.count();
  $('bank-status').textContent = `题库：${n} 题`;
  return n;
}

async function loadBankIntoIndex(force = false) {
  if (bankLoaded && !force) return;
  const rows = await bank.getAll();
  index.clear();
  for (const r of rows) index.add(r);
  bankLoaded = true;
}

function renderBankPreview(rows) {
  const el = $('bank-preview');
  if (!rows.length) { el.textContent = ''; return; }
  const head = rows.slice(0, 5).map((r) => `· ${r.question.slice(0, 40)}${r.question.length > 40 ? '…' : ''}`).join('<br>');
  el.innerHTML = `已导入 <b>${rows.length}</b> 题。示例：<br>${head}`;
}

async function handleBankFile(file) {
  console.log('[import] file.name:', file.name);
  console.log('[import] file.type:', file.type);
  console.log('[import] file.size:', file.size);
  const isExcel = /\.xlsx?$/i.test(file.name);
  // Excel 是二进制文件，只读取 ArrayBuffer；文本格式才读取为 text，避免 Android WebView 把
  // 二进制内容错误解码。
  const text = isExcel ? '' : await file.text();
  console.log('[import] text length:', text.length);
  const blob = isExcel ? file : null;
  console.log('[import] blob used:', !!blob);
  let rows;
  try {
    // XLS/XLSX 解析要读取文件二进制数据，返回 Promise；必须等待其完成后再检查题目数量。
    rows = await bank.parseBankFile(file.name, text, blob);
    console.log('[import] parsed rows:', rows.length);
    if (rows.length > 0) console.log('[import] first row:', JSON.stringify(rows[0]).slice(0, 200));
  } catch (e) {
    console.error('[import] parse error:', e);
    alert(`解析失败：${e.message}`);
    return;
  }
  if (!rows.length) { 
    console.log('[import] WARNING: no rows parsed!');
    alert('没有解析到题目，请检查格式'); 
    return; 
  }
  await bank.addMany(rows);
  await loadBankIntoIndex(true);
  renderBankPreview(rows);
  await refreshBankStatus();
}

// ---------- 搜题流程 ----------

const cropper = new Cropper($('crop-canvas'), $('crop-overlay'));
let currentImageBlob = null;

function setStatus(msg, show = true) {
  const el = $('ocr-status');
  el.classList.toggle('hidden', !show || !msg);
  if (msg) el.textContent = msg;
}

function escapeHtml(s) {
  const div = document.createElement('div');
  div.textContent = s;
  return div.innerHTML;
}

function resolveAnswer(item) {
  const raw = String(item.answer || '').trim();
  const match = raw.match(/^\s*([A-Z])(?:[.．、:：\-\s]|$)/i);
  const letter = match ? match[1].toUpperCase() : '';
  if (!letter || !item.options?.length) return { letter, text: raw };
  const option = item.options[letter.charCodeAt(0) - 65];
  if (!option) return { letter, text: raw };
  // Excel 选项常写成“A-内容”或“A. 内容”；答案区域只展示内容，避免重复字母。
  const content = String(option).replace(/^[A-Z][.．、:：\-\s]*/i, '').trim();
  return { letter, text: content ? `${letter}. ${content}` : raw };
}

async function onCapture() {
  try {
    setStatus('正在读取屏幕…');
    await showCapturedImage(await captureScreen());
    setStatus('');
  } catch (e) {
    if (e.message !== '已取消') alert(`读取屏幕失败：${e.message}`);
    setStatus('');
  }
}

async function onRecognize() {
  const btn = $('btn-recognize');
  btn.disabled = true;
  try {
    const imgBlob = await cropper.toBlob();
    if (!imgBlob) throw new Error('没有图片');
    const text = await ocrImage(imgBlob, (s) => setStatus(s));
    if (!text) { alert('OCR 未识别到文字，试试框选更清晰的区域'); return; }
    $('ocr-text').textContent = text;

    await loadBankIntoIndex();
    if (!index.size) {
      renderResults([]);
      $('results').classList.remove('hidden');
      setStatus('题库为空，请先在「题库管理」导入题目');
      return;
    }
    const results = index.search(text, 5);
    renderResults(results);
    $('results').classList.remove('hidden');
    setStatus(results.length ? `完成：识别 ${text.length} 字，命中 ${results.length} 条` : '完成：没有足够接近的题目');
  } catch (e) {
    alert(`识别失败：${e.message}`);
    setStatus('');
  } finally {
    btn.disabled = false;
  }
}

// 一键搜索（来自截图框的搜索按钮或 FAB）
async function onSearchFromCrop() {
  try {
    setStatus('正在搜索…');
    const imgBlob = await cropper.toBlob();
    if (!imgBlob) throw new Error('没有图片');
    const text = await ocrImage(imgBlob, (s) => setStatus(s));
    if (!text) { alert('OCR 未识别到文字，试试框选更清晰的区域'); setStatus(''); return; }
    $('ocr-text').textContent = text;

    await loadBankIntoIndex();
    if (!index.size) {
      renderResults([]);
      $('results').classList.remove('hidden');
      setStatus('题库为空，请先在「题库管理」导入题目');
      return;
    }
    const results = index.search(text, 5);
    renderResults(results);
    $('results').classList.remove('hidden');
    setStatus(results.length ? `完成：识别 ${text.length} 字，命中 ${results.length} 条` : '完成：没有足够接近的题目');
  } catch (e) {
    alert(`搜索失败：${e.message}`);
    setStatus('');
  }
}

function renderResults(results) {
  const list = $('result-list');
  list.innerHTML = '';
  if (!results.length) {
    list.innerHTML = '<li class="hint">无匹配结果（可尝试手动输入识别文本微调）</li>';
    return;
  }
  for (const { item, score } of results) {
    const li = document.createElement('li');
    li.className = 'result-item';
    const pct = Math.round(score * 100);

    // 如果有选项，构建选项行
    const resolvedAnswer = resolveAnswer(item);
    let optionsHtml = '';
    if (item.options && item.options.length) {
      const letters = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
      optionsHtml = '<div class="answer-options">';
      for (let i = 0; i < item.options.length; i++) {
        const letter = letters[i];
        const optionText = String(item.options[i]).replace(/^[A-Z][.．、:：\-\s]*/i, '').trim();
        const selected = letter === resolvedAnswer.letter ? ' opt-selected' : '';
        optionsHtml += `<span class="opt${selected}"><span class="opt-letter">${escapeHtml(letter + '. ')}</span><span class="opt-text">${escapeHtml(optionText || item.options[i])}</span></span>`;
      }
      optionsHtml += '</div>';
    }

    li.innerHTML = `
      <div class="result-q"></div>
      <div class="result-answer"><span class="result-ans-label">答案：</span><span class="result-ans-value"></span></div>
      ${optionsHtml ? `<div class="result-o"></div>` : ''}
      <div class="score-label">匹配度 ${pct}%</div>`;
    li.querySelector('.result-q').textContent = item.question;
    
    li.querySelector('.result-ans-value').textContent = resolvedAnswer.text || '题库未提供答案';

    list.appendChild(li);
  }
}

// ---------- UI 绑定 ----------

document.querySelectorAll('.tab').forEach((btn) => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    const which = btn.dataset.tab;
    $('tab-search').classList.toggle('hidden', which !== 'search');
    $('tab-bank').classList.toggle('hidden', which !== 'bank');
  });
});

$('btn-capture').addEventListener('click', onCapture);
$('btn-camera').addEventListener('click', async () => {
  try {
    const file = await pickImage();
    await showCapturedImage(new Blob([file], { type: file.type || 'image/jpeg' }));
  } catch (e) { /* 取消 */ }
});

$('btn-recognize').addEventListener('click', onRecognize);
$('btn-search-crop').addEventListener('click', onSearchFromCrop);
$('btn-cancel-crop').addEventListener('click', () => {
  $('crop-wrap').classList.add('hidden');
  currentImageBlob = null;
});

// 悬浮搜题按钮（FAB）
$('fab-search').addEventListener('click', async () => {
  $('tab-search').classList.remove('hidden');
  $('tab-bank').classList.add('hidden');
  document.querySelectorAll('.tab').forEach((b) => {
    b.classList.remove('active');
  });
  document.querySelector('[data-tab="search"]').classList.add('active');
  await onCapture();
});

// 暴露给 Android 悬浮窗 overlay：FloatingSearchView.evaluateJavascript 调用 window.__souti_floating_capture()
async function consumePendingNativeCapture() {
  const plugin = window.Capacitor?.Plugins?.ExternalScreenCapture;
  if (!plugin) return false;
  try {
    const { base64, mimeType } = await plugin.readLatest();
    if (!base64) return false;
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    await showCapturedImage(new Blob([bytes], { type: mimeType || 'image/jpeg' }));
    return true;
  } catch (e) {
    // 正常启动时没有待处理截图会被原生模块拒绝；无需把它当成用户可见错误。
    console.debug('[capture] no pending native image:', e.message);
    return false;
  }
}

window.__souti_native_capture_ready = async () => {
  // 由 App 内“读取屏幕”发起时，captureScreen 正在等待该事件。
  if (window.__souti_waiting_native_capture) {
    window.dispatchEvent(new Event('souti-native-capture-ready'));
    return;
  }
  // 由其他 App 上的悬浮图标发起时，没有等待中的 Web 调用；直接取回原生截图并打开裁剪页。
  const consumed = await consumePendingNativeCapture();
  if (!consumed) alert('系统截图未保存，请重新点击悬浮搜索图标');
};

// 原生悬浮裁剪层调用：不打开本 App 页面，直接在后台完成 OCR/匹配，再由原生显示结果卡片。
window.__souti_overlay_search = async (base64) => {
  const plugin = window.Capacitor?.Plugins?.ExternalScreenCapture;
  try {
    console.info('[overlay-search] started');
    if (!plugin) throw new Error('原生搜题悬浮层不可用');
    const bin = atob(base64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const text = await ocrImage(new Blob([bytes], { type: 'image/jpeg' }));
    console.info('[overlay-search] OCR complete', text.length);
    if (!text) throw new Error('未识别到文字，请重新框选题干');
    await loadBankIntoIndex();
    if (!index.size) throw new Error('题库为空，请先导入题库');
    const matches = index.search(text, 5).map(({ item, score }) => {
      const answer = resolveAnswer(item);
      return { question: item.question, answer: answer.text || '题库未提供答案', options: item.options || [], score };
    });
    console.info('[overlay-search] match complete', matches.length);
    await plugin.showOverlayResults({ payload: JSON.stringify({ text, matches }) });
  } catch (e) {
    console.error('[overlay-search]', e);
    if (plugin) await plugin.showOverlayError({ message: e.message || '识别或搜题失败' });
  }
};

// 原生 ML Kit 已在设备侧完成 OCR；这里仅访问本地 IndexedDB 题库并匹配，运行很轻量。
window.__souti_overlay_match = async (text) => {
  const plugin = window.Capacitor?.Plugins?.ExternalScreenCapture;
  try {
    console.info('[overlay-match] started', text.length);
    if (!plugin) throw new Error('原生搜题悬浮层不可用');
    await loadBankIntoIndex();
    if (!index.size) throw new Error('题库为空，请先导入题库');
    const matches = index.search(text, 5).map(({ item, score }) => {
      const answer = resolveAnswer(item);
      return { question: item.question, answer: answer.text || '题库未提供答案', options: item.options || [], score };
    });
    console.info('[overlay-match] complete', matches.length);
    await plugin.showOverlayResults({ payload: JSON.stringify({ text, matches }) });
  } catch (e) {
    console.error('[overlay-match]', e);
    if (plugin) await plugin.showOverlayError({ message: e.message || '本地题库匹配失败' });
  }
};

$('bank-file').addEventListener('change', (e) => {
  const f = e.target.files && e.target.files[0];
  if (f) handleBankFile(f);
  e.target.value = '';
});

$('btn-clear-bank').addEventListener('click', async () => {
  if (!confirm('确定清空题库？')) return;
  await bank.clearAll();
  await loadBankIntoIndex(true);
  renderBankPreview([]);
  await refreshBankStatus();
});

// ---------- 启动 ----------

// 可见错误面板：无 adb 日志也能看到真实报错，便于定位
(function () {
  let count = 0;
  const errLog = [];
  function paint() {
    const el = document.getElementById('dbg-panel');
    if (!el) return;
    el.style.display = count > 0 ? 'block' : 'none';
    el.textContent = count + ' 个错误：\n' + errLog.slice(-6).join('\n\n');
  }
  window.addEventListener('error', (e) => {
    errLog.push((e.error && e.error.stack) || e.message || String(e));
    count++;
    paint();
  });
  window.addEventListener('unhandledrejection', (e) => {
    errLog.push('rejection: ' + (e.reason && e.reason.stack ? e.reason.stack : e.reason));
    count++;
    paint();
  });
  const boot = setInterval(() => {
    const root = document.body;
    if (!root) return;
    clearInterval(boot);
    const el = document.createElement('div');
    el.id = 'dbg-panel';
    el.style.cssText = 'position:fixed;right:8px;top:8px;z-index:99999;background:rgba(0,0,0,.92);color:#7CFC00;font:11px/1.4 monospace;padding:6px 8px;max-width:60%;word-break:break-all;display:none;border:1px solid #444;border-radius:4px';
    root.appendChild(el);
    el.addEventListener('click', () => {
      el.style.display = el.style.display === 'none' ? 'block' : 'none';
    });
  }, 1200);
})();

(async () => {
  await refreshBankStatus();
  await loadBankIntoIndex();
  // 截屏完成时 Android 可能重建 WebView，导致原生回调早于本脚本。启动后主动取回
  // 尚未消费的截图，保证会进入裁剪页而不是停在演示首页。
  await consumePendingNativeCapture();
})();
