// 本地 OCR：Tesseract.js（chi_sim + eng），模型优先取本机 tessdata，离线可用

import Tesseract from 'tesseract.js';

const LANGS = ['chi_sim', 'eng'];
let queue = Promise.resolve();
let workerPromise = null;
let completedJobs = 0;

/**
 * 创建/复用 OCR worker。
 * 路径策略：先试本地 ./tessdata（由 scripts/fetch-tessdata.mjs 下载到 public/），
 * 失败则回退到 CDN（首次需联网）。
 */
async function createWorker(onStatus) {
  if (workerPromise) return workerPromise;
  workerPromise = (async () => {
    const localBase = './tessdata';
    let langPath, statusBase = '本地模型';
    try {
      // Tesseract 5 模型仓库用无压缩 .traineddata（不是 .gz）
      const probe = await fetch(localBase + '/chi_sim.traineddata', { method: 'HEAD' });
      if (!probe.ok) throw new Error('no local tessdata');
      langPath = localBase;
    } catch {
      langPath = undefined; // 用 Tesseract.js 默认 CDN
      statusBase = 'CDN 模型（首次需联网）';
    }
    onStatus?.(`加载 OCR 引擎（${statusBase}）…`);
    const worker = await Tesseract.createWorker(LANGS, 1, {
      langPath,
      // public/tessdata 内打包的是未压缩文件。Tesseract 默认会请求 .traineddata.gz，
      // 在 Android WebView 中会造成 404，继而让识别流程失败。
      gzip: false,
      workerOptions: {
        // Android WebView: Web Workers 有时加载失败，forceWorker:true 确保创建
        worker: true,
      },
      logger: (m) => {
        if (m.status === 'recognizing text') onStatus?.(`识别中 ${Math.round(m.progress * 100)}%`);
      },
    });
    return worker;
  })();
  workerPromise.catch(() => { workerPromise = null; });
  return workerPromise;
}

async function resetWorker() {
  const pending = workerPromise;
  workerPromise = null;
  completedJobs = 0;
  if (!pending) return;
  try {
    const worker = await pending;
    // 终止必须等完成，不能和下一题的模型加载重叠。
    await within(4000, worker.terminate());
  } catch { /* 已损坏或已被系统终止的 Worker 无需继续清理 */ }
}

function within(ms, work) {
  let timeout;
  const expired = new Promise((_, reject) => {
    timeout = setTimeout(() => reject(new Error('识别超时，请重新框选后再试')), ms);
  });
  return Promise.race([work, expired]).finally(() => clearTimeout(timeout));
}

/**
 * 识别图片（Blob / dataURL / URL），返回纯文本。
 */
export function ocrImage(image, onStatus) {
  // 正常情况复用模型保证速度；每三题在空闲时完整重建，避免长时运行的 WASM Worker 挂死。
  const run = queue.then(async () => {
    try {
      if (completedJobs >= 3) await resetWorker();
      const worker = await within(12000, createWorker(onStatus));
      const { data } = await within(18000, worker.recognize(image));
      completedJobs++;
      return (data.text || '').trim();
    } catch (error) {
      // 超时/异常后废弃本实例，下次搜索会从干净 Worker 恢复，不让错误持续扩散。
      await resetWorker();
      throw error;
    }
  });
  // 单题失败不能让后续题目永久卡在 rejected queue 后面。
  queue = run.catch(() => {});
  return run;
}
