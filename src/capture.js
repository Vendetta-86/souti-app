// 屏幕读取：Capacitor 原生（Android MediaProjection）优先，浏览器/降级用相册文件选择

let fileInputEl = null;

/** 通过隐藏的 <input type=file> 让用户从相册选图或拍照 */
export function pickImage() {
  return new Promise((resolve, reject) => {
    if (!fileInputEl) {
      fileInputEl = document.getElementById('image-file');
    }
    const input = fileInputEl;
    if (!input) return reject(new Error('找不到文件选择控件'));
    input.value = '';
    const onChange = () => {
      cleanup();
      const f = input.files && input.files[0];
      f ? resolve(f) : reject(new Error('未选择图片'));
    };
    const onReject = () => { cleanup(); reject(new Error('已取消')); };
    function cleanup() {
      input.removeEventListener('change', onChange);
      input.removeEventListener('cancel', onReject);
    }
    input.addEventListener('change', onChange);
    input.addEventListener('cancel', onReject);
    input.click();
  });
}

/**
 * 读取当前屏幕。返回 Blob。
 * Android：使用原生 MediaProjection 截取真实设备屏幕，降级用相册。
 * 其它环境：降级为相册/拍照选择。
 */
export async function captureScreen() {
  if (window.Capacitor?.isNativePlatform?.()) {
    try {
      const plugin = window.Capacitor.Plugins.ExternalScreenCapture;
      if (!plugin) throw new Error('原生截屏模块未加载');
      await plugin.capture();
      // 系统授权页会覆盖当前页面；截图完成后 MainActivity 会重新前台并触发下方事件。
      return await waitForNativeCapture(plugin);
    } catch (e) {
      console.warn('屏幕读取失败，降级为相册选择：', e);
    }
  }
  // 浏览器/桌面预览模式：直接选图（开发时方便测试）
  const file = await pickImage();
  return new Blob([file], { type: file.type || 'image/jpeg' });
}

function waitForNativeCapture(plugin) {
  return new Promise((resolve, reject) => {
    window.__souti_waiting_native_capture = true;
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error('未完成截屏或授权已取消'));
    }, 30000);
    const cleanup = () => {
      clearTimeout(timer);
      window.__souti_waiting_native_capture = false;
      window.removeEventListener('souti-native-capture-ready', onReady);
    };
    const onReady = async () => {
      try {
        const { base64, mimeType } = await plugin.readLatest();
        if (!base64) throw new Error('截图数据为空');
        const bin = atob(base64);
        const bytes = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        cleanup();
        resolve(new Blob([bytes], { type: mimeType || 'image/jpeg' }));
      } catch (error) {
        cleanup();
        reject(error);
      }
    };
    window.addEventListener('souti-native-capture-ready', onReady, { once: true });
  });
}

/**
 * 降级：将 content:// URI 作为文件路径读入 base64
 * 适用于 Filesystem 无法访问 content URI 的情况
 */
