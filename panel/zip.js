import fs from 'node:fs';
import { once } from 'node:events';

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
  for (let i = 0; i < buffer.length; i++) {
    c = crcTable[(c ^ buffer[i]) & 0xFF] ^ (c >>> 8);
  }
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function dosDateTime(date = new Date()) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1);
  const datePart = ((Math.max(date.getFullYear(), 1980) - 1980) << 9) |
    ((date.getMonth() + 1) << 5) | date.getDate();
  return { time, date: datePart };
}

/**
 * Creates a ZIP archive using the STORE method (no compression).
 * PS1 disc images are already compressed and can be several hundred MB,
 * so storing them keeps the OBB build fast and memory-friendly.
 *
 * @param {string} outputPath
 * @param {Array<{path: string, name: string}>} entries
 */
export async function createZip(outputPath, entries) {
  const out = fs.createWriteStream(outputPath);
  const write = async (buffer) => {
    if (!out.write(buffer)) await once(out, 'drain');
  };

  const central = [];
  let offset = 0;
  const { time, date } = dosDateTime();

  for (const entry of entries) {
    const nameBuffer = Buffer.from(entry.name, 'utf8');
    const stat = await fs.promises.stat(entry.path);
    if (stat.size > 0xFFFFFFFF) throw new Error(`فایل ${entry.name} بزرگتر از 4GB است (محدودیت ZIP)`);

    let crc = 0;
    const input = fs.createReadStream(entry.path);
    for await (const chunk of input) crc = crc32(chunk);

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034B50, 0);
    local.writeUInt16LE(20, 4);      // version needed
    local.writeUInt16LE(0x0800, 6);  // UTF-8 filenames
    local.writeUInt16LE(0, 8);       // method: STORE
    local.writeUInt16LE(time, 10);
    local.writeUInt16LE(date, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(stat.size, 18);
    local.writeUInt32LE(stat.size, 22);
    local.writeUInt16LE(nameBuffer.length, 26);
    local.writeUInt16LE(0, 28);
    await write(local);
    await write(nameBuffer);
    await write(fs.readFileSync(entry.path));

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014B50, 0);
    centralHeader.writeUInt16LE(20, 4);   // made by
    centralHeader.writeUInt16LE(20, 6);   // needed
    centralHeader.writeUInt16LE(0x0800, 8);
    centralHeader.writeUInt16LE(0, 10);
    centralHeader.writeUInt16LE(time, 12);
    centralHeader.writeUInt16LE(date, 14);
    centralHeader.writeUInt32LE(crc, 16);
    centralHeader.writeUInt32LE(stat.size, 20);
    centralHeader.writeUInt32LE(stat.size, 24);
    centralHeader.writeUInt16LE(nameBuffer.length, 28);
    centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32);
    centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36);
    centralHeader.writeUInt32LE(0, 38);
    centralHeader.writeUInt32LE(offset, 42);
    central.push(centralHeader, nameBuffer);

    offset += local.length + nameBuffer.length + stat.size;
  }

  const centralSize = central.reduce((n, b) => n + b.length, 0);
  for (const buffer of central) await write(buffer);

  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054B50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralSize, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  await write(end);

  await new Promise((resolve, reject) => {
    out.once('error', reject);
    out.end(resolve);
  });
}
