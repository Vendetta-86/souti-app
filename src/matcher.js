// 文本归一化 + 模糊匹配（本地题库检索核心）

/** 归一化：小写、全角转半角、去空白和标点，只保留有效字符 */
export function normalize(s) {
  return (s || '')
    .toLowerCase()
    // 全角 ASCII 区 -> 半角
    .replace(/[\uFF01-\uFF5E]/g, (ch) => String.fromCharCode(ch.charCodeAt(0) - 0xfee0))
    .replace(/\u3000/g, ' ')
    // 去空白
    .replace(/\s+/g, '')
    // 去常见中英文标点（保留字母数字、CJK、数学符号）
    .replace(/[，。！？；：、""''“”‘’（）《》【】·,.!?;:'"()\[\]{}<>~@#$%^&*+=/\\|_\-—…]/g, '');
}

function bigrams(s) {
  const set = new Set();
  for (let i = 0; i < s.length - 1; i++) set.add(s.slice(i, i + 2));
  return set;
}

/** Dice 系数（字符二元组） */
function dice(a, b) {
  const A = bigrams(a), B = bigrams(b);
  if (!A.size || !B.size) return 0;
  let inter = 0;
  for (const g of A) if (B.has(g)) inter++;
  return (2 * inter) / (A.size + B.size);
}

/** 截断编辑距离相似度（只对短文本，控制开销） */
function editSim(a, b) {
  const maxLen = 48;
  const x = a.slice(0, maxLen), y = b.slice(0, maxLen);
  if (!x.length || !y.length) return 0;
  const m = x.length, n = y.length;
  let prev = new Array(n + 1).fill(0);
  for (let j = 0; j <= n; j++) prev[j] = j;
  for (let i = 1; i <= m; i++) {
    const cur = [i];
    for (let j = 1; j <= n; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x[i - 1] === y[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return 1 - prev[n] / Math.max(m, n);
}

/** 查询 q 与题目 t（均已归一化）的匹配分，0~1 */
export function scorePair(q, t) {
  if (!q || !t) return 0;
  if (q === t) return 1;
  let s = 0;
  // 包含关系：题库题目完整包含查询（或反之），给强信号
  if (t.includes(q)) s += 0.55 * Math.min(1, q.length / Math.max(8, t.length * 0.6));
  if (q.includes(t) && t.length >= 4) s += 0.45;
  // 二元组相似度
  s += 0.45 * dice(q, t);
  // 短查询再加编辑距离信号（OCR 错字容错）
  if (q.length <= 48 && t.length <= 48) s += 0.2 * editSim(q, t);
  // 长度差异惩罚
  const lr = Math.min(q.length, t.length) / Math.max(q.length, t.length);
  return s * (0.5 + 0.5 * lr);
}

export class QuestionIndex {
  constructor() {
    this.items = [];          // {id, question, answer, norm, bigramSet}
    this.posting = new Map(); // bigram -> Set<index>
  }

  add(q) {
    const norm = normalize(q.question);
    if (!norm) return false;
    const idx = this.items.length;
    // 搜索结果需要保留 Excel 导入的选项，才能把“D”还原成“D. 选项内容”。
    const item = {
      id: q.id ?? idx,
      question: q.question,
      answer: q.answer || '',
      options: Array.isArray(q.options) ? q.options : [],
      norm,
    };
    this.items.push(item);
    for (const g of bigrams(norm)) {
      let set = this.posting.get(g);
      if (!set) { set = new Set(); this.posting.set(g, set); }
      set.add(idx);
    }
    return true;
  }

  clear() {
    this.items = [];
    this.posting.clear();
  }

  get size() { return this.items.length; }

  /** 返回 [{item, score}]，按分数降序 */
  search(query, topK = 5, minScore = 0.18) {
    const q = normalize(query);
    if (!q) return [];
    // 倒排预筛：共享至少一个二元组的题目才参与精排
    const cand = new Set();
    for (const g of bigrams(q)) {
      const set = this.posting.get(g);
      if (set) for (const i of set) cand.add(i);
    }
    // 题库很小或无命中时退回全量
    const pool = cand.size ? [...cand] : this.items.map((_, i) => i);
    const scored = [];
    for (const i of pool) {
      const s = scorePair(q, this.items[i].norm);
      if (s >= minScore) scored.push({ item: this.items[i], score: s });
    }
    scored.sort((a, b) => b.score - a.score);
    return scored.slice(0, topK);
  }
}
