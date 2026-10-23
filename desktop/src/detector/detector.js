'use strict';
// Looks at small pictures of the page for nudity with the NudeNet detector, as the phone app does
// (app/.../detect/NudityDetector.kt and core/Detection.kt): the picture is fitted into the top-left of a 320-pixel
// square, black elsewhere, and the strongest kind of nudity found is reported as a level (3 explicit, 2 bare breasts
// and buttocks, 1 swimwear and the like, 0 nothing).
(function () {
  const SIZE = 320;
  const NAMES = ['FEMALE_GENITALIA_COVERED', 'FACE_FEMALE', 'BUTTOCKS_EXPOSED', 'FEMALE_BREAST_EXPOSED', 'FEMALE_GENITALIA_EXPOSED',
    'MALE_BREAST_EXPOSED', 'ANUS_EXPOSED', 'FEET_EXPOSED', 'BELLY_COVERED', 'FEET_COVERED', 'ARMPITS_COVERED', 'ARMPITS_EXPOSED',
    'FACE_MALE', 'BELLY_EXPOSED', 'MALE_GENITALIA_EXPOSED', 'ANUS_COVERED', 'FEMALE_BREAST_COVERED', 'BUTTOCKS_COVERED'];
  const LEVELS = { FEMALE_GENITALIA_EXPOSED: 3, MALE_GENITALIA_EXPOSED: 3, ANUS_EXPOSED: 3, FEMALE_BREAST_EXPOSED: 2, BUTTOCKS_EXPOSED: 2,
    FEMALE_BREAST_COVERED: 1, FEMALE_GENITALIA_COVERED: 1, BUTTOCKS_COVERED: 1, MALE_BREAST_EXPOSED: 1, BELLY_EXPOSED: 1 };

  const canvas = new OffscreenCanvas(SIZE, SIZE);
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const input = new Float32Array(3 * SIZE * SIZE);
  let session = null;
  let busy = Promise.resolve();

  function iou(a, b) {
    const w = Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1), h = Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1);
    if (w <= 0 || h <= 0) return 0;
    const inter = w * h, union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter;
    return union <= 0 ? 0 : inter / union;
  }

  function decode(out, classes, anchors, threshold) {
    const found = [];
    for (let i = 0; i < anchors; i++) {
      let best = -1, score = threshold;
      for (let c = 0; c < classes; c++) { const s = out[(4 + c) * anchors + i]; if (s >= score) { score = s; best = c; } }
      if (best < 0) continue;
      const cx = out[i], cy = out[anchors + i], w = out[2 * anchors + i], h = out[3 * anchors + i];
      found.push({ c: best, s: score, x1: cx - w / 2, y1: cy - h / 2, x2: cx + w / 2, y2: cy + h / 2 });
    }
    found.sort((a, b) => b.s - a.s);
    const kept = [];
    for (const d of found) if (!kept.some((k) => k.c === d.c && iou(k, d) > 0.45)) kept.push(d);
    return kept;
  }

  async function level(jpeg, faces) {
    const bitmap = await createImageBitmap(new Blob([jpeg], { type: 'image/jpeg' }));
    const scale = SIZE / Math.max(bitmap.width, bitmap.height);
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, SIZE, SIZE);
    ctx.drawImage(bitmap, 0, 0, Math.max(1, Math.floor(bitmap.width * scale)), Math.max(1, Math.floor(bitmap.height * scale)));
    bitmap.close();
    const px = ctx.getImageData(0, 0, SIZE, SIZE).data;
    const plane = SIZE * SIZE;
    for (let i = 0; i < plane; i++) {
      input[i] = px[i * 4] / 255;
      input[plane + i] = px[i * 4 + 1] / 255;
      input[2 * plane + i] = px[i * 4 + 2] / 255;
    }
    const tensor = new ort.Tensor('float32', input, [1, 3, SIZE, SIZE]);
    const result = await session.run({ [session.inputNames[0]]: tensor });
    const out = result[session.outputNames[0]];
    const detections = decode(out.data, out.dims[1] - 4, out.dims[2], 0.35);
    let top = 0;
    for (const d of detections) {
      const name = NAMES[d.c] || '';
      const l = faces && name.startsWith('FACE_') ? 3 : (LEVELS[name] || 0);
      if (l > top) top = l;
    }
    return top;
  }

  async function start() {
    try {
      ort.env.wasm.wasmPaths = 'eden://app/src/detector/ort/';
      ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.max(1, Math.min(4, Math.floor((navigator.hardwareConcurrency || 2) / 2))) : 1;
      const bytes = new Uint8Array(await (await fetch('eden://model/nudenet.onnx')).arrayBuffer());
      session = await ort.InferenceSession.create(bytes, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' });
      // A first, empty look, so the first real one is quick.
      await session.run({ [session.inputNames[0]]: new ort.Tensor('float32', input, [1, 3, SIZE, SIZE]) });
      detectorLink.ready(true, ort.env.wasm.numThreads + (ort.env.wasm.numThreads === 1 ? ' core' : ' cores'));
    } catch (e) {
      detectorLink.ready(false, String(e && e.message || e));
      return;
    }
    detectorLink.onFrame((id, jpeg, faces) => {
      busy = busy.then(() => level(jpeg, faces)).then((l) => detectorLink.level(id, l), () => detectorLink.level(id, 0));
    });
  }
  start();
})();
