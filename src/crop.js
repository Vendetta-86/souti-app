// 截图裁剪：在 canvas 上拖出矩形区域，输出裁剪后的 Blob

export class Cropper {
  constructor(baseCanvas, overlayCanvas) {
    this.base = baseCanvas;
    this.overlay = overlayCanvas;
    this.rect = null; // {x,y,w,h} 显示坐标
    this.start = null;
    this._bind();
  }

  /** 载入图片；返回 Promise，resolve 后 canvas 就绪 */
  loadImage(blob) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(blob);
      const img = new Image();
      img.onload = () => {
        // 限制显示宽度，避免内存爆炸；保留原始比例
        const maxW = Math.min(window.innerWidth - 32, 900);
        const scale = Math.min(1, maxW / img.width);
        this.base.width = Math.round(img.width * scale);
        this.base.height = Math.round(img.height * scale);
        this.overlay.width = this.base.width;
        this.overlay.height = this.base.height;
        this.scaleX = img.width / this.base.width;
        this.scaleY = img.height / this.base.height;
        this.fullImg = img;
        this.base.getContext('2d').drawImage(img, 0, 0, this.base.width, this.base.height);
        this.rect = null;
        this._draw();
        URL.revokeObjectURL(url);
        resolve();
      };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片加载失败')); };
      img.src = url;
    });
  }

  _pos(e) {
    const r = this.overlay.getBoundingClientRect();
    const p = e.touches ? e.touches[0] : e;
    return {
      x: Math.max(0, Math.min(this.base.width, (p.clientX - r.left) * (this.base.width / r.width))),
      y: Math.max(0, Math.min(this.base.height, (p.clientY - r.top) * (this.base.height / r.height))),
    };
  }

  _bind() {
    const down = (e) => { e.preventDefault(); this.start = this._pos(e); this.rect = null; this._draw(); };
    const move = (e) => {
      if (!this.start) return;
      e.preventDefault();
      const p = this._pos(e);
      this.rect = {
        x: Math.min(this.start.x, p.x),
        y: Math.min(this.start.y, p.y),
        w: Math.abs(p.x - this.start.x),
        h: Math.abs(p.y - this.start.y),
      };
      this._draw();
    };
    const up = () => { this.start = null; if (this.rect && (this.rect.w < 8 || this.rect.h < 8)) { this.rect = null; this._draw(); } };

    for (const el of [this.overlay]) {
      el.addEventListener('mousedown', down);
      el.addEventListener('mousemove', move);
      el.addEventListener('mouseup', up);
      el.addEventListener('mouseleave', up);
      el.addEventListener('touchstart', down, { passive: false });
      el.addEventListener('touchmove', move, { passive: false });
      el.addEventListener('touchend', up);
    }
  }

  _draw() {
    const ctx = this.overlay.getContext('2d');
    ctx.clearRect(0, 0, this.overlay.width, this.overlay.height);
    if (!this.rect) return;
    const { x, y, w, h } = this.rect;
    ctx.fillStyle = 'rgba(0,0,0,0.45)';
    ctx.fillRect(0, 0, this.overlay.width, y);
    ctx.fillRect(0, y + h, this.overlay.width, this.overlay.height - y - h);
    ctx.fillRect(0, y, x, h);
    ctx.fillRect(x + w, y, this.overlay.width - x - w, h);
    ctx.strokeStyle = '#2f6fed';
    ctx.lineWidth = 2;
    ctx.strokeRect(x, y, w, h);
  }

  /** 导出裁剪结果；未框选则返回整图。resolve Blob */
  toBlob(type = 'image/jpeg', quality = 0.92) {
    return new Promise((resolve) => {
      const src = this.fullImg;
      if (!src) return resolve(null);
      let sx = 0, sy = 0, sw = src.width, sh = src.height;
      if (this.rect && this.rect.w > 8 && this.rect.h > 8) {
        sx = Math.round(this.rect.x * this.scaleX);
        sy = Math.round(this.rect.y * this.scaleY);
        sw = Math.max(1, Math.round(this.rect.w * this.scaleX));
        sh = Math.max(1, Math.round(this.rect.h * this.scaleY));
      }
      const c = document.createElement('canvas');
      c.width = sw; c.height = sh;
      c.getContext('2d').drawImage(src, sx, sy, sw, sh, 0, 0, sw, sh);
      c.toBlob((b) => resolve(b), type, quality);
    });
  }
}
