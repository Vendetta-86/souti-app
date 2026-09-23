// 题库存储：IndexedDB（手机本地持久化）+ 三种格式解析

// 显式导入而非依赖浏览器全局变量。Vite 会将它打入 Android WebView 使用的资源包。
import * as XLSX from 'xlsx';

const DB_NAME = 'souti';
const STORE = 'questions';
let dbPromise = null;

function openDB() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'id', autoIncrement: true });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

async function tx(mode, fn) {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode);
    const store = t.objectStore(STORE);
    const out = fn(store);
    t.oncomplete = () => resolve(out?.result !== undefined ? out.result : undefined);
    t.onerror = () => reject(t.error);
  });
}

export async function count() {
  return tx('readonly', (s) => s.count());
}

export async function clearAll() {
  await tx('readwrite', (s) => s.clear());
}

export async function addMany(rows) {
  const db = await openDB();
  return new Promise((resolve, reject) => {
    const t = db.transaction(STORE, 'readwrite');
    const store = t.objectStore(STORE);
    for (const r of rows) store.add(r);
    t.oncomplete = () => resolve(rows.length);
    t.onerror = () => reject(t.error);
  });
}

export async function getAll() {
  return tx('readonly', (s) => s.getAll());
}

// ---------- 格式解析 ----------

/** JSON: [{"question","answer"}]，兼容 q/a 字段 */
function parseJSON(text) {
  const data = JSON.parse(text);
  if (!Array.isArray(data)) throw new Error('JSON 必须是数组');
  return data
    .map((d, i) => ({
      question: String(d.question ?? d.q ?? '').trim(),
      answer: String(d.answer ?? d.a ?? '').trim(),
      source: `json#${i}`,
    }))
    .filter((r) => r.question);
}

/** CSV：第一列题目、第二列答案；简单处理引号包裹 */
function parseCSV(text) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim());
  const rows = [];
  for (const line of lines) {
    const cells = splitCSVLine(line);
    if (cells.length < 2) continue;
    // 跳过表头
    if (/^(question|题目)\s*,\s*(answer|答案)$/i.test(line)) continue;
    rows.push({ question: cells[0].trim(), answer: cells.slice(1).join(', ').trim(), source: 'csv' });
  }
  return rows.filter((r) => r.question);
}

function splitCSVLine(line) {
  const out = [];
  let cur = '', inQ = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (inQ) {
      if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
      else if (ch === '"') inQ = false;
      else cur += ch;
    } else if (ch === '"') inQ = true;
    else if (ch === ',') { out.push(cur); cur = ''; }
    else cur += ch;
  }
  out.push(cur);
  return out;
}

/** TXT：奇数行题目、偶数行答案，成对 */
function parseTXT(text) {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const rows = [];
  for (let i = 0; i + 1 < lines.length; i += 2) {
    rows.push({ question: lines[i], answer: lines[i + 1] || '', source: `txt#${i / 2}` });
  }
  return rows.filter((r) => r.question);
}

/** 安全把任意 cell 值转成字符串：Date/number/null 都不泄漏 */
function cellToStr(v) {
  if (v == null) return '';
  if (v instanceof Date) return String(v.getTime());
  return String(v);
}

/** XLSX/Excel 解析 */
async function parseXLSX(blob) {
  if (!blob) throw new Error('未读取到 Excel 文件数据');
  let sheet;
  const useBinary = blob instanceof Blob;
  try {
    // type:'array' 比 'binary' 在安卓端更稳；读为 ArrayBuffer
    const buf = useBinary ? await _blobToArrayBuffer(blob) : blob;
    const workbook = XLSX.read(buf, { type: 'array', cellDates: false });
    sheet = workbook.Sheets[workbook.SheetNames[0]];
    if (!sheet) { console.log('[xlsx] no sheet found'); return []; }
  } catch (readErr) {
    console.error('[xlsx] read failed, try raw string path:', readErr);
    throw readErr;
  }

  // 主路径：sheet_to_json(raw:true)。数字保持数字、Date 不转对象。
  let rows;
  try {
    rows = XLSX.utils.sheet_to_json(sheet, { header: 1, defval: '', raw: true, cellDates: false });
  } catch (sheetErr) {
    // SheetJS 在某些单元格（公式/富文本/格式化）内部会抛 charCodeAt is not a function
    // 此时退回到 sheet_to_csv（格式化无关、从不抛），手动按行列解析
    console.warn('[xlsx] sheet_to_json failed, fall back to CSV:', sheetErr.message);
    const csv = XLSX.utils.sheet_to_csv(sheet);
    rows = _splitCsvRows(csv);
  }

  const parsed = [];
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    if (!row || !row.length) continue;
    // cellToStr 二次保险：任何值都能安全转字符串（对象 -> "[object Object]"，绝不抛）
    const first = cellToStr(row[0]).trim();
    if (/^(question|题目|题干|题号|序号)/i.test(first)) continue;
    const question = cellToStr(row[0]).trim();
    if (!question) continue;
    const answerLetter = cellToStr(row[1]).trim().toUpperCase();
    const options = [];
    for (let c = 2; c < row.length; c++) {
      const opt = cellToStr(row[c]).trim();
      if (opt) options.push(opt);
    }
    parsed.push({ question, answer: answerLetter, options, source: `xlsx#${i}` });
  }
  console.log('[xlsx] parsed count:', parsed.length);
  return parsed;
}

/** Blob -> ArrayBuffer（安卓端读文件稳妥方式） */
async function _blobToArrayBuffer(blob) {
  if (blob.arrayBuffer) return await blob.arrayBuffer();
  const bytes = new Uint8Array(await blob.arrayBuffer ? await blob.arrayBuffer() : await new Promise((res) => { const fr = new FileReader(); fr.onload = () => res(fr.result); fr.readAsArrayBuffer(blob); }));
  return bytes.buffer;
}

/** 把 CSV 文本拆成行数组（跳过表头由调用方判断） */
function _splitCsvRows(csv) {
  const out = [];
  const lines = csv.split(/\r?\n/);
  for (const line of lines) {
    if (!line.trim()) continue;
    const cells = [];
    let cur = '', inQ = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (inQ) {
        if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
        else if (ch === '"') inQ = false;
        else cur += ch;
      } else if (ch === '"') inQ = true;
      else if (ch === ',') { cells.push(cur); cur = ''; }
      else cur += ch;
    }
    cells.push(cur);
    out.push(cells);
  }
  return out;
}

/** 按扩展名/内容自动解析，返回 [{question, answer, source}] */
export function parseBankFile(name, text, blob) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  const trimmed = text.trim();
  if (ext === 'json' || trimmed.startsWith('[') || trimmed.startsWith('{')) return parseJSON(text);
  if (ext === 'csv') return parseCSV(text);
  if (ext === 'txt') return parseTXT(text);
  if (ext === 'xlsx' || ext === 'xls') return parseXLSX(blob);
  // 兜底：先试 JSON，再 CSV，最后 TXT
  try { return parseJSON(trimmed); } catch { /* next */ }
  if (trimmed.includes(',')) return parseCSV(text);
  return parseTXT(text);
}
