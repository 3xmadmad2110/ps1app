// Dependency-free streaming ZIP writer (STORE method) for Cloudflare Workers.
// Game images are already compressed, so STORE keeps CPU and memory low.

const encoder = new TextEncoder();

const table = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();

export class Crc32 {
  constructor() { this.value = 0xFFFFFFFF; }
  update(bytes) {
    let c = this.value;
    for (let i = 0; i < bytes.length; i++) c = table[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
    this.value = c >>> 0;
    return this;
  }
  get crc() { return (this.value ^ 0xFFFFFFFF) >>> 0; }
}

export function dosDateTime(date = new Date()) {
  return {
    time: (date.getUTCHours() << 11) | (date.getUTCMinutes() << 5) | (date.getUTCSeconds() >> 1),
    date: ((Math.max(date.getUTCFullYear(), 1980) - 1980) << 9) |
      ((date.getUTCMonth() + 1) << 5) | date.getUTCDate(),
  };
}

function u16(view, offset, value) { view.setUint16(offset, value, true); }
function u32(view, offset, value) { view.setUint32(offset, value >>> 0, true); }

function localHeader(name, size, crc, time, date) {
  const out = new Uint8Array(30);
  const v = new DataView(out.buffer);
  u32(v, 0, 0x04034B50); u16(v, 4, 20); u16(v, 6, 0x0800);
  u16(v, 8, 0); u16(v, 10, time); u16(v, 12, date);
  u32(v, 14, crc); u32(v, 18, size); u32(v, 22, size);
  u16(v, 26, name.length); u16(v, 28, 0);
  return out;
}

function centralHeader(name, size, crc, time, date, offset) {
  const out = new Uint8Array(46);
  const v = new DataView(out.buffer);
  u32(v, 0, 0x02014B50); u16(v, 4, 20); u16(v, 6, 20); u16(v, 8, 0x0800);
  u16(v, 10, 0); u16(v, 12, time); u16(v, 14, date);
  u32(v, 16, crc); u32(v, 20, size); u32(v, 24, size);
  u16(v, 28, name.length); u16(v, 30, 0); u16(v, 32, 0); u16(v, 34, 0);
  u16(v, 36, 0); u32(v, 38, 0); u32(v, 42, offset);
  return out;
}

function endRecord(count, centralSize, centralOffset) {
  const out = new Uint8Array(22);
  const v = new DataView(out.buffer);
  u32(v, 0, 0x06054B50); u16(v, 4, 0); u16(v, 6, 0);
  u16(v, 8, count); u16(v, 10, count);
  u32(v, 12, centralSize); u32(v, 16, centralOffset); u16(v, 20, 0);
  return out;
}

/**
 * Entries: [{ name, size, crc, parts: string[] } | { name, data: Uint8Array }]
 * readPart(key) is an async generator yielding Uint8Array chunks.
 */
export async function* buildZip(entries, readPart) {
  const { time, date } = dosDateTime();
  const central = [];
  let offset = 0;

  for (const entry of entries) {
    const name = encoder.encode(entry.name);
    if (entry.size > 0xFFFFFFFF) throw new Error(`${entry.name} larger than 4GB (ZIP limit)`);
    const crc = entry.crc ?? new Crc32().update(entry.data).crc;
    const local = localHeader(name, entry.size, crc, time, date);

    yield local;
    yield name;
    if (entry.data) {
      yield entry.data;
    } else {
      for (const key of entry.parts) {
        for await (const chunk of readPart(key)) yield chunk;
      }
    }

    central.push({ name, header: centralHeader(name, entry.size, crc, time, date, offset) });
    offset += local.length + name.length + entry.size;
  }

  let centralSize = 0;
  for (const item of central) {
    yield item.header;
    yield item.name;
    centralSize += item.header.length + item.name.length;
  }
  yield endRecord(central.length, centralSize, offset);
}
