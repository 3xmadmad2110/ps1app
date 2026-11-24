import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { config, publicConfig, githubReady } from './config.js';
import { MultipartParser } from './multipart.js';
import { allJobs, getJob, createJob, jobDir } from './jobs.js';
import { prepareJob, publishExisting, refreshJob, obbFileName } from './builder.js';
import { renderIndex, renderJob } from './views.js';

const MAX_FILE_BYTES = 2 * 1024 * 1024 * 1024; // GitHub release asset limit

function sendJson(res, status, data) {
  const body = JSON.stringify(data, null, 2);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function sendHtml(res, status, html) {
  res.writeHead(status, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Length': Buffer.byteLength(html),
    'Cache-Control': 'no-store',
  });
  res.end(html);
}

function sendStatic(res, filePath) {
  const ext = path.extname(filePath).toLowerCase();
  const types = {
    '.css': 'text/css; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
  };
  try {
    const body = fs.readFileSync(filePath);
    res.writeHead(200, { 'Content-Type': types[ext] || 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end('Not found');
  }
}

function safeName(name) {
  return path.basename(String(name || ''))
    .replace(/[^\w.\-\u0600-\u06FF ]+/g, '_')
    .replace(/\s+/g, '_')
    .slice(0, 180) || 'file';
}

const persianMap = {
  'ا': 'a', 'آ': 'a', 'أ': 'a', 'ب': 'b', 'پ': 'p', 'ت': 't', 'ث': 's',
  'ج': 'j', 'چ': 'ch', 'ح': 'h', 'خ': 'kh', 'د': 'd', 'ذ': 'z', 'ر': 'r',
  'ز': 'z', 'ژ': 'zh', 'س': 's', 'ش': 'sh', 'ص': 's', 'ض': 'z', 'ط': 't',
  'ظ': 'z', 'ع': 'a', 'غ': 'gh', 'ف': 'f', 'ق': 'g', 'ک': 'k', 'ك': 'k',
  'گ': 'g', 'ل': 'l', 'م': 'm', 'ن': 'n', 'و': 'v', 'ه': 'h', 'ی': 'y',
  'ي': 'y', 'ۀ': 'e', 'ة': 'e',
};

export function makeSlug(text) {
  let latin = '';
  for (const ch of String(text || '')) {
    if (persianMap[ch]) latin += persianMap[ch];
    else latin += ch;
  }
  let slug = latin
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40)
    .replace(/_+$/g, '');
  if (!slug) slug = 'game';
  if (/^[0-9]/.test(slug)) slug = 'g_' + slug;
  return slug;
}

async function handleUpload(req, res) {
  const contentType = req.headers['content-type'] || '';
  const match = contentType.match(/boundary=(?:"([^"]+)"|([^;]+))/i);
  if (!match) return sendJson(res, 400, { error: 'درخواست multipart معتبر نیست' });
  const boundary = (match[1] || match[2]).trim();

  const tmpDir = path.join(config.dataDir, 'tmp', crypto.randomUUID());
  fs.mkdirSync(tmpDir, { recursive: true });

  const fields = {};
  const files = {};
  let current = null;

  const parser = new MultipartParser(boundary, {
    onPart(part) {
      current = { ...part, chunks: [], size: 0, fd: null };
      if (part.filename) {
        const outPath = path.join(tmpDir, crypto.randomUUID() + '-' + safeName(part.filename));
        current.fd = fs.openSync(outPath, 'w');
        current.outPath = outPath;
      }
    },
    onData(buffer) {
      if (!current) return;
      current.size += buffer.length;
      if (current.size > MAX_FILE_BYTES) throw new Error('حجم فایل بیش از 2GB است');
      if (current.fd !== null) fs.writeSync(current.fd, buffer);
      else current.chunks.push(buffer);
    },
    onEnd() {
      if (!current) return;
      if (current.fd !== null) {
        fs.closeSync(current.fd);
        files[current.name] = {
          path: current.outPath,
          name: current.filename,
          size: current.size,
          contentType: current.contentType,
        };
      } else {
        fields[current.name] = Buffer.concat(current.chunks).toString('utf8').trim();
      }
      current = null;
    },
  });

  try {
    await pipeline(req, parser);
  } catch (error) {
    if (current && current.fd !== null) {
      try { fs.closeSync(current.fd); } catch { /* already closed */ }
    }
    fs.rmSync(tmpDir, { recursive: true, force: true });
    return sendJson(res, 400, { error: `خطا در دریافت فایل: ${error.message}` });
  }

  const name = (fields.name || '').trim();
  const bin = files.bin;
  const cue = files.cue;
  const cover = files.cover || files.image || files.cover_image;

  if (!name) {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    return sendJson(res, 400, { error: 'نام بازی الزامی است' });
  }
  if (!bin || !cue) {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    return sendJson(res, 400, { error: 'فایل bin و cue هر دو الزامی هستند' });
  }
  const binExt = path.extname(bin.name).toLowerCase();
  const cueExt = path.extname(cue.name).toLowerCase();
  if (binExt !== '.bin' && binExt !== '.img') {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    return sendJson(res, 400, { error: 'فایل دیسک باید postfix .bin یا .img داشته باشد' });
  }
  if (cueExt !== '.cue') {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    return sendJson(res, 400, { error: 'فایل مشخصات دیسک باید .cue باشد' });
  }

  const slug = makeSlug(fields.slug || name);
  const packageName = `${config.basePackage}.${slug}`;
  const versionCode = config.versionCode;
  const releaseTag = `ps1-${slug}-v${versionCode}`;

  const target = jobDir(slug);
  fs.mkdirSync(target, { recursive: true });
  const stored = {};
  for (const [key, file] of Object.entries({ bin, cue, ...(cover ? { cover } : {}) })) {
    const dest = path.join(target, safeName(file.name));
    fs.renameSync(file.path, dest);
    stored[key] = { path: dest, name: safeName(file.name), size: file.size, contentType: file.contentType };
  }
  fs.rmSync(tmpDir, { recursive: true, force: true });

  const job = createJob({
    slug, name, packageName, versionCode, releaseTag,
    files: stored,
    obbName: obbFileName({ versionCode, packageName }),
  });

  void prepareJob(slug);
  return sendJson(res, 202, job);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = decodeURIComponent(url.pathname);

  try {
    if (req.method === 'GET' && pathname === '/') {
      return sendHtml(res, 200, renderIndex(allJobs(), publicConfig()));
    }
    if (req.method === 'GET' && pathname.startsWith('/jobs/')) {
      const slug = pathname.slice('/jobs/'.length);
      const job = getJob(slug);
      if (!job) {
        res.writeHead(404);
        return res.end('Not found');
      }
      return sendHtml(res, 200, renderJob(job, publicConfig()));
    }
    if (req.method === 'GET' && pathname.startsWith('/static/')) {
      const rel = pathname.slice('/static/'.length).replace(/[^a-zA-Z0-9_/.-]/g, '');
      return sendStatic(res, path.join(import.meta.dirname, 'public', rel));
    }
    if (req.method === 'GET' && pathname === '/health') {
      return sendJson(res, 200, { ok: true, github: githubReady(), jobs: allJobs().length });
    }
    if (req.method === 'GET' && pathname === '/api/config') {
      return sendJson(res, 200, publicConfig());
    }
    if (req.method === 'GET' && pathname === '/api/jobs') {
      return sendJson(res, 200, allJobs());
    }
    if (req.method === 'POST' && pathname === '/api/jobs') {
      return await handleUpload(req, res);
    }
    if (req.method === 'GET' && pathname.startsWith('/api/jobs/')) {
      const parts = pathname.split('/').filter(Boolean);
      const slug = parts[2];
      const job = getJob(slug);
      if (!job) return sendJson(res, 404, { error: 'not found' });
      if (parts[3] === 'obb') {
        const file = path.join(jobDir(slug), obbFileName(job));
        if (!fs.existsSync(file)) return sendJson(res, 404, { error: 'OBB هنوز ساخته نشده' });
        res.writeHead(200, {
          'Content-Type': 'application/zip',
          'Content-Length': fs.statSync(file).size,
          'Content-Disposition': `attachment; filename="${obbFileName(job)}"`,
        });
        return fs.createReadStream(file).pipe(res);
      }
      return sendJson(res, 200, job);
    }
    if (req.method === 'POST' && pathname.startsWith('/api/jobs/')) {
      const parts = pathname.split('/').filter(Boolean);
      const slug = parts[2];
      const action = parts[3];
      if (!getJob(slug)) return sendJson(res, 404, { error: 'not found' });
      if (action === 'refresh') return sendJson(res, 200, await refreshJob(slug));
      if (action === 'retry') {
        void prepareJob(slug);
        return sendJson(res, 202, getJob(slug));
      }
      if (action === 'publish') {
        void publishExisting(slug);
        return sendJson(res, 202, getJob(slug));
      }
    }
    if (req.method === 'GET' && pathname === '/api/download/obb') {
      const slug = url.searchParams.get('slug');
      const job = slug && getJob(slug);
      if (!job) return sendJson(res, 404, { error: 'not found' });
      const file = path.join(jobDir(slug), obbFileName(job));
      if (!fs.existsSync(file)) return sendJson(res, 404, { error: 'OBB موجود نیست' });
      res.writeHead(200, {
        'Content-Type': 'application/zip',
        'Content-Length': fs.statSync(file).size,
        'Content-Disposition': `attachment; filename="${obbFileName(job)}"`,
      });
      return fs.createReadStream(file).pipe(res);
    }

    return sendJson(res, 404, { error: 'not found' });
  } catch (error) {
    console.error(error);
    if (!res.headersSent) return sendJson(res, 500, { error: error.message });
    res.end();
  }
});

server.listen(config.port, config.host, () => {
  console.log(`PS1 panel → http://localhost:${config.port}`);
  console.log(`GitHub: ${githubReady() ? 'configured' : 'not configured (OBB-only mode)'}`);
});
