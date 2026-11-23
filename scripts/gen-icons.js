// Generates the placeholder launcher icons (no external packages needed).
// Run: node scripts/gen-icons.js
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const crcTable = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buffer) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buffer.length; i++) c = crcTable[(c ^ buffer[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const result = Buffer.alloc(data.length + 12);
  result.writeUInt32BE(data.length, 0);
  result.write(type, 4, 'ascii');
  data.copy(result, 8);
  result.writeUInt32BE(crc32(result.subarray(4, 8 + data.length)), 8 + data.length);
  return result;
}

function makePng(size, round) {
  const stride = size * 4 + 1;
  const raw = Buffer.alloc(stride * size);
  const fontSize = Math.max(2, Math.floor(size / 11));
  const glyphWidth = fontSize * 5;
  const gap = fontSize * 2;
  const markWidth = glyphWidth * 2 + gap;
  const markLeft = Math.floor((size - markWidth) / 2);
  const markTop = Math.floor((size - fontSize * 7) / 2);

  const glyphs = {
    P: ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
    S: ['01111', '10000', '10000', '01110', '00001', '00001', '11110'],
  };

  for (let y = 0; y < size; y++) {
    const row = y * stride;
    raw[row] = 0;
    for (let x = 0; x < size; x++) {
      const i = row + 1 + x * 4;
      const t = (x + y) / (2 * size);
      let r = Math.round(0x6d + (0x22 - 0x6d) * t);
      let g = Math.round(0x5e + (0xd3 - 0x5e) * t);
      let b = Math.round(0xfc + (0xee - 0xfc) * t);
      let alpha = 255;

      if (round) {
        const dx = x + 0.5 - size / 2;
        const dy = y + 0.5 - size / 2;
        if (dx * dx + dy * dy > (size / 2 - 1) ** 2) alpha = 0;
      } else {
        const radius = size * 0.22;
        const cx = Math.min(Math.max(x, radius), size - radius);
        const cy = Math.min(Math.max(y, radius), size - radius);
        if ((x - cx) ** 2 + (y - cy) ** 2 > radius ** 2) alpha = 0;
      }

      // Draw a small "PS" mark in the middle.
      const localY = y - markTop;
      if (localY >= 0 && localY < fontSize * 7) {
        let letter = null;
        let localX = -1;
        if (x >= markLeft && x < markLeft + glyphWidth) {
          letter = glyphs.P;
          localX = x - markLeft;
        } else if (x >= markLeft + glyphWidth + gap && x < markLeft + markWidth) {
          letter = glyphs.S;
          localX = x - (markLeft + glyphWidth + gap);
        }
        if (letter) {
          const gx = Math.floor(localX / fontSize);
          const gy = Math.floor(localY / fontSize);
          if (gx >= 0 && gx < 5 && gy >= 0 && gy < 7 && letter[gy][gx] === '1') {
            r = 255; g = 255; b = 255;
          }
        }
      }

      raw[i] = r; raw[i + 1] = g; raw[i + 2] = b; raw[i + 3] = alpha;
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const densities = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
for (const [name, size] of Object.entries(densities)) {
  const dir = path.join(root, 'android', 'app', 'src', 'main', 'res', `mipmap-${name}`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'ic_launcher.png'), makePng(size, false));
  fs.writeFileSync(path.join(dir, 'ic_launcher_round.png'), makePng(size, true));
}
console.log('Placeholder icons generated.');
