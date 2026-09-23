// api.ts — 统一 API 端点，供前端 UI 调用

import { QuestionIndex } from './matcher.js'
import * as questionBank from './questionBank.js'
import { ocrImage } from './ocr.js'
import { captureScreen, pickImage } from './capture.js'

const index = new QuestionIndex()

// ==================== 题库 API ====================

export async function getBankCount(): Promise<number> {
  return await questionBank.count()
}

export async function getAllQuestions(): Promise<QuestionBankRow[]> {
  return await questionBank.getAll()
}

export async function importBank(
  filename: string,
  text: string
): Promise<number> {
  const rows = questionBank.parseBankFile(filename, text)
  await questionBank.addMany(rows)
  await index.clear()
  await loadBankIntoIndex()
  return rows.length
}

export async function clearBank(): Promise<void> {
  await questionBank.clearAll()
  await index.clear()
}

// ==================== 搜题 API ====================

export async function recognizeImage(
  image: Blob | string,
  onStatus?: (msg: string) => void
): Promise<string> {
  return await ocrImage(image, onStatus)
}

export async function searchQuestions(
  text: string,
  limit: number = 5
): Promise<SearchResult[]> {
  if (!index.size) {
    await loadBankIntoIndex()
  }
  const results = index.search(text, limit)
  return results.map(r => ({
    question: r.item.question,
    answer: r.item.answer || '',
    score: Math.round(r.score * 100) / 100
  }))
}

export async function captureScreenApi(): Promise<Blob> {
  return await captureScreen()
}

// ==================== 内部辅助 ====================

async function loadBankIntoIndex(): Promise<void> {
  const rows = await questionBank.getAll()
  index.clear()
  for (const r of rows) {
    index.add(r)
  }
}

// ==================== 类型 ====================

export interface QuestionBankRow {
  question: string
  answer: string
}

export interface SearchResult {
  question: string
  answer: string
  score: number
}
