// 下载 Tesseract 5 语言模型到 public/tessdata，实现离线 OCR
// 用法：node scripts/fetch-tessdata.mjs [fast|best]   （默认 best）
// fast 更小（~4MB/语言），best 精度更高（~15-20MB/语言）
// Tesseract 5 模型仓库：https://github.com/tesseract-ocr/tessdata_fast / tessdata_best

import { mkdir, access, writeFile } from 'node:fs/promises';
import path from 'node:path';

const mode = process.argv[2] === 'fast' ? 'fast' : 'best';
const langs = ['chi_sim', 'eng'];
const outDir = path.resolve('public/tessdata');
const base = `https://github.com/tesseract-ocr/tessdata_${mode}/raw/main/`;

await mkdir(outDir, { recursive: true });

for (const lang of langs) {
  const file = `${lang}.traineddata`;
  const dest = path.join(outDir, file);
  try {
    await access(dest);
    console.log(`✓ 已存在：${file}`);
    continue;
  } catch { /* 下载 */ }

  const url = base + file;
  console.log(`↓ 下载 ${url} …`);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  const buf = Buffer.from(await res.arrayBuffer());
  await writeFile(dest, buf);
  console.log(`✓ ${file}（${(buf.length / 1024 / 1024).toFixed(1)} MB）`);
}

console.log('\n完成。模型已放入 public/tessdata，vite build 后会自动打进 dist/，离线可用。');
