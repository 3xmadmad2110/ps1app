import { createJob, getJob, saveJob, putPart, readFileParts, deleteJob } from './store.js';
import { buildZip, Crc32 } from './zip.js';
import * as gh from './github.js';

const encoder = new TextEncoder();
const MAX_PART = 40 * 1024 * 1024; // 40 MB — safely below the Worker request limit.

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

function html(body, status = 200) {
  return new Response(body, {
    status,
    headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

function error(message, status = 400) {
  return json({ error: String(message) }, status);
}

function slugify(name, override) {
  const raw = (override || name || '').trim();
  const ascii = raw
    .replace(/[\u0600-\u06FF]/g, (ch) => {
      const map = {
        'ا': 'a', 'أ': 'a', 'إ': 'a', 'آ': 'a', 'ب': 'b', 'پ': 'p', 'ت': 't', 'ث': 's',
        'ج': 'j', 'چ': 'ch', 'ح': 'h', 'خ': 'kh', 'د': 'd', 'ذ': 'z', 'ر': 'r', 'ز': 'z',
        'ژ': 'zh', 'س': 's', 'ش': 'sh', 'ص': 's', 'ض': 'z', 'ط': 't', 'ظ': 'z', 'ع': 'a',
        'غ': 'gh', 'ف': 'f', 'ق': 'gh', 'ک': 'k', 'گ': 'g', 'ل': 'l', 'م': 'm', 'ن': 'n',
        'و': 'v', 'ه': 'h', 'ی': 'y', 'ي': 'y', 'ła': 'la',
      };
      return map[ch] || '';
    })
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 60);
  return ascii || 'game';
}

function publicJob(job) {
  const { id, name, slug, packageName, status, stage, error: err, logs, releaseUrl, releaseTag } = job;
  return { id, name, slug, packageName, status, stage, error: err, logs, releaseUrl, releaseTag };
}

function requireToken(request, env) {
  if (!env.PANEL_TOKEN) return true;
  const auth = request.headers.get('Authorization') || '';
  const query = new URL(request.url).searchParams.get('token') || '';
  return auth === `Bearer ${env.PANEL_TOKEN}` || query === env.PANEL_TOKEN;
}

async function readBytes(request, limit) {
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length > limit) throw new Error(`Part too large (max ${Math.round(limit / 1048576)} MB)`);
  return bytes;
}

export default {
  async fetch(request, env, ctx) {
    try {
      return await route(request, env, ctx);
    } catch (e) {
      return error(e?.message || e, 500);
    }
  },
};

async function route(request, env, ctx) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;

  if (path === '/' || path === '/index.html') return html(ui());
  if (path === '/health') return json({ ok: true, service: 'ps1app-panel' });
  if (!requireToken(request, env)) return error('Unauthorized', 401);

  if (path === '/api/jobs' && method === 'POST') {
    const body = await request.json();
    const name = String(body.name || '').trim();
    if (!name) return error('Game name is required');
    if (!env.GITHUB_TOKEN || !env.GITHUB_OWNER || !env.GITHUB_REPO) {
      return error('GitHub token/owner/repo are not configured', 500);
    }
    const slug = slugify(name, body.slug);
    const packageName = `${env.BASE_PACKAGE || 'com.farsipsx.ps1app'}.${slug}`;
    const versionCode = Number(env.VERSION_CODE || 1);
    const job = {
      id: crypto.randomUUID(),
      name,
      slug,
      packageName,
      versionCode,
      releaseTag: `ps1-${slug}-v${versionCode}`,
      status: 'uploading',
      stage: 'upload',
      logs: [`New job for ${name} (${packageName})`],
      files: {},
      createdAt: Date.now(),
    };
    await createJob(env.UPLOADS, job);
    return json(publicJob(job), 201);
  }

  const jobMatch = path.match(/^\/api\/jobs\/([a-zA-Z0-9-]+)$/);
  if (jobMatch && method === 'GET') {
    const job = await getJob(env.UPLOADS, jobMatch[1]);
    if (!job) return error('Job not found', 404);
    return json(publicJob(job));
  }

  // POST /api/jobs/:id/files/:kind?name=...&index=...&count=...&size=...&crc=...
  const uploadMatch = path.match(/^\/api\/jobs\/([a-zA-Z0-9-]+)\/files\/(bin|cue|cover)$/);
  if (uploadMatch && method === 'POST') {
    const job = await getJob(env.UPLOADS, uploadMatch[1]);
    if (!job) return error('Job not found', 404);
    if (job.status === 'published') return error('Job is already published');
    const kind = uploadMatch[2];
    const fileName = url.searchParams.get('name') || '';
    const index = Number(url.searchParams.get('index'));
    const count = Number(url.searchParams.get('count'));
    const size = Number(url.searchParams.get('size'));
    const crc = url.searchParams.get('crc') || '';
    if (!fileName || !Number.isInteger(index) || !Number.isInteger(count) || index < 0 || index >= count) {
      return error('Invalid upload part parameters');
    }
    const bytes = await readBytes(request, MAX_PART);
    await putPart(env.UPLOADS, job.id, `${kind}:${fileName}`, index, bytes);
    if (!job.files[kind]) job.files[kind] = { name: fileName, count, size, crc };
    job.files[kind].received = Math.max(job.files[kind].received || 0, index + 1);
    job.logs.push(`Uploaded ${fileName} part ${index + 1}/${count}`);
    await saveJob(env.UPLOADS, job);
    return json({ ok: true, received: job.files[kind].received, count });
  }

  const buildMatch = path.match(/^\/api\/jobs\/([a-zA-Z0-9-]+)\/build$/);
  if (buildMatch && method === 'POST') {
    const job = await getJob(env.UPLOADS, buildMatch[1]);
    if (!job) return error('Job not found', 404);
    if (job.status === 'building' || job.status === 'published') return error('Job cannot be rebuilt');
    job.status = 'building';
    job.stage = 'zip';
    job.logs.push('Building OBB from uploaded parts');
    await saveJob(env.UPLOADS, job);
    // waitUntil keeps the Worker alive until publishing finishes; the browser
    // receives the response immediately and polls the job status.
    ctx.waitUntil(processJob(env, job));
    return json(publicJob(job));
  }

  if (path.startsWith('/api/')) return error('Not found', 404);
  return html(ui());
}

// ---------- Background build & publish ----------
async function fileDescriptor(env, job, kind) {
  const info = job.files[kind];
  if (!info) throw new Error(`${kind} was not uploaded`);
  const expected = info.received || 0;
  if (expected < info.count) throw new Error(`${kind} upload incomplete (${expected}/${info.count})`);
  const crc = new Crc32();
  let total = 0;
  for await (const chunk of readFileParts(env.UPLOADS, job.id, `${kind}:${info.name}`, info.count)) {
    crc.update(chunk);
    total += chunk.byteLength;
  }
  if (total !== info.size) throw new Error(`${kind} size mismatch (${total} vs ${info.size})`);
  const prefix = `jobs/${job.id}/parts/${kind}:${info.name}/`;
  return {
    name: info.name,
    size: total,
    crc: crc.crc,
    kind,
    parts: Array.from({ length: info.count }, (_, i) => `${prefix}${String(i).padStart(6, '0')}.part`),
  };
}

function readPartAdapter(env) {
  return async function* (key) {
    const object = await env.UPLOADS.get(key);
    if (!object) throw new Error(`Missing part ${key}`);
    yield new Uint8Array(await object.arrayBuffer());
    object.body?.cancel?.();
  };
}

function metaJson(job) {
  return encoder.encode(JSON.stringify({
    name: job.name,
    packageName: job.packageName,
    versionCode: job.versionCode,
    releaseTag: job.releaseTag,
    emulator: 'SwanStation (DuckStation libretro core)',
  }, null, 2));
}

async function processJob(env, job) {
  const bucket = env.UPLOADS;
  try {
    job.stage = 'crc';
    await saveJob(bucket, job);
    const bin = await fileDescriptor(env, job, 'bin');
    const cue = await fileDescriptor(env, job, 'cue');
    const coverInfo = job.files.cover;
    let cover = null;
    if (coverInfo) {
      const chunks = [];
      for await (const chunk of readFileParts(bucket, job.id, `cover:${coverInfo.name}`, coverInfo.count)) {
        chunks.push(chunk);
      }
      const size = chunks.reduce((n, c) => n + c.byteLength, 0);
      const merged = new Uint8Array(size);
      let off = 0;
      for (const c of chunks) { merged.set(c, off); off += c.byteLength; }
      cover = { name: coverInfo.name, data: merged };
    }

    job.logs.push('Creating OBB archive');
    job.stage = 'zip';
    await saveJob(bucket, job);
    const entries = [
      { name: bin.name, size: bin.size, crc: bin.crc, parts: bin.parts },
      { name: cue.name, size: cue.size, crc: cue.crc, parts: cue.parts },
    ];
    if (cover) entries.push({ name: cover.name, size: cover.data.byteLength, data: cover.data });
    const meta = metaJson(job);
    entries.push({ name: 'meta.json', size: meta.byteLength, data: meta });
    const zipStream = new ReadableStream({
      async start(controller) {
        try {
          for await (const chunk of buildZip(entries, readPartAdapter(env))) controller.enqueue(chunk);
          controller.close();
        } catch (e) { controller.error(e); }
      },
    });

    job.stage = 'release';
    job.logs.push('Creating GitHub release');
    await saveJob(bucket, job);
    let release = await gh.getRelease(env, job.releaseTag);
    if (!release) {
      release = await gh.createRelease(env, {
        tag: job.releaseTag,
        name: `${job.name} — PS1`,
        body: releaseBody(job),
        draft: true,
      });
    }
    job.releaseTag = release.tag_name;
    await saveJob(bucket, job);

    const obbName = `main.${job.versionCode}.${job.packageName}.obb`;
    job.logs.push(`Uploading ${obbName}`);
    await gh.uploadAsset(env, release.upload_url, obbName, zipStream, 'application/octet-stream');

    // Upload the original files and cover as separate release assets too.
    async function uploadPartsAsAsset(info, name) {
      const pieces = [];
      for await (const chunk of readFileParts(bucket, job.id, `${info.kind}:${info.name}`, info.count)) {
        pieces.push(chunk);
      }
      const size = pieces.reduce((n, c) => n + c.byteLength, 0);
      const merged = new Uint8Array(size);
      let off = 0;
      for (const c of pieces) { merged.set(c, off); off += c.byteLength; }
      await gh.uploadAsset(env, release.upload_url, name, merged, 'application/octet-stream');
    }
    await uploadPartsAsAsset(bin, bin.name);
    await uploadPartsAsAsset(cue, cue.name);
    if (cover) await gh.uploadAsset(env, release.upload_url, cover.name, cover.data, 'image/jpeg');
    await gh.uploadAsset(env, release.upload_url, 'meta.json', meta, 'application/json');

    job.logs.push('Dispatching APK build workflow');
    await gh.dispatchWorkflow(env, env.GITHUB_WORKFLOW || 'build-game.yml', env.GITHUB_REF || 'main', {
      package_suffix: job.slug,
      game_name: job.name,
      version_code: String(job.versionCode),
      release_tag: job.releaseTag,
      obb_name: obbName,
    });

    job.status = 'published';
    job.stage = 'done';
    job.releaseUrl = release.html_url;
    job.logs.push('Published. APK will appear in the release after the workflow finishes.');
    await saveJob(bucket, job);
    // R2 was only a temporary staging area.
    await deleteJob(bucket, job.id);
  } catch (e) {
    job.status = 'error';
    job.error = e?.message || String(e);
    job.logs.push(`ERROR: ${job.error}`);
    await saveJob(bucket, job);
  }
}

function releaseBody(job) {
  return [
    `# ${job.name}`,
    '',
    `- پکیج: \`${job.packageName}\``,
    `- نسخه: ${job.versionCode}`,
    '',
    '## نصب',
    '1. فایل APK را نصب کنید.',
    `2. فایل OBB را در مسیر \`Android/obb/${job.packageName}/\` با همان اسم قرار دهید.`,
    '3. بازی را اجرا کنید؛ دادهها خودکار استخراج میشوند.',
    '',
    '> فقط از ایمجهایی استفاده کنید که حقوق آن را دارید.',
  ].join('\n');
}

// ---------- UI ----------
function ui() {
  return `<!doctype html>
<html lang="fa" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>پنل PS1App</title>
<style>
body{font-family:tahoma,arial,sans-serif;background:#12141a;color:#eee;margin:0;padding:2rem;direction:rtl}
h1{color:#7dd3fc} .card{background:#1e222d;border:1px solid #333a4a;border-radius:12px;padding:1.5rem;max-width:640px;margin:auto}
label{display:block;margin:.8rem 0 .3rem} input{width:100%;padding:.6rem;box-sizing:border-box;background:#0d1017;color:#fff;border:1px solid #445066;border-radius:6px}
button{margin-top:1rem;padding:.7rem 1.2rem;background:#0284c7;color:#fff;border:0;border-radius:6px;cursor:pointer}
pre{background:#0d1017;padding:1rem;border-radius:6px;overflow:auto;white-space:pre-wrap;font-size:.85rem}
a{color:#7dd3fc}
</style></head><body><div class="card">
<h1>پنل ساخت اپ PS1</h1>
<p>فایل bin/cue و کاور را آپلود کنید تا APK و OBB در ریلیز GitHub منتشر شود.</p>
<label>نام بازی</label><input id="name" placeholder="مثلاً Crash Bandicoot">
<label>اسلاگ (اختیاری)</label><input id="slug" placeholder="crash_bandicoot">
<label>فایل .bin</label><input id="bin" type="file" accept=".bin">
<label>فایل .cue</label><input id="cue" type="file" accept=".cue">
<label>کاور</label><input id="cover" type="file" accept="image/*">
<button id="go">شروع ساخت</button>
<pre id="log">آماده.</pre>
</div>
<script>
const $=id=>document.getElementById(id);
const CHUNK=40*1024*1024;
async function crc32(buf){ // simple, adequate for cover/meta; game parts verified by size
 const t=new Uint32Array(256);for(let n=0;n<256;n++){let c=n;for(let k=0;k<8;k++)c=(c&1)?(0xEDB88320^(c>>>1)):(c>>>1);t[n]=c>>>0;}
 let c=0xFFFFFFFF;for(let i=0;i<buf.length;i++)c=t[(c^buf[i])&0xFF]^(c>>>8);return ((c^0xFFFFFFFF)>>>0).toString(16);
}
async function upload(jobId,kind,file){
 const count=Math.max(1,Math.ceil(file.size/CHUNK));
 for(let i=0;i<count;i++){
  const part=file.slice(i*CHUNK,Math.min(file.size,(i+1)*CHUNK));
  const buf=new Uint8Array(await part.arrayBuffer());
  const crc=await crc32(buf);
  const q=new URLSearchParams({name:file.name,index:i,count,size:file.size,crc});
  const r=await fetch('/api/jobs/'+jobId+'/files/'+kind+'?'+q,{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:buf});
  if(!r.ok)throw new Error((await r.json()).error||'upload failed');
  $('log').textContent+='\\n'+kind+' '+(i+1)+'/'+count;
 }
 return {name:file.name,count,size:file.size,crc:await crc32(new Uint8Array(await file.slice(0,Math.min(file.size,1048576)).arrayBuffer()))};
}
$('go').onclick=async()=>{
 try{
  $('log').textContent='ساخت کار...';
  const r=await fetch('/api/jobs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:$('name').value,slug:$('slug').value})});
  const job=await r.json();if(!r.ok)throw new Error(job.error);
  $('log').textContent='کار: '+job.id;
  for(const [kind,id] of [['bin','bin'],['cue','cue'],['cover','cover']]){
   const f=$(id).files[0];if(f)await upload(job.id,kind,f);
  }
  const b=await fetch('/api/jobs/'+job.id+'/build',{method:'POST'});
  const j=await b.json();if(!b.ok)throw new Error(j.error);
  const poll=setInterval(async()=>{
   const s=await (await fetch('/api/jobs/'+job.id)).json();
   $('log').textContent=s.logs.join('\\n');
   if(s.status==='published'||s.status==='error'){clearInterval(poll);if(s.releaseUrl)$('log').innerHTML+='\\n<a href="'+s.releaseUrl+'" target="_blank">ریلیز</a>';}
  },2000);
 }catch(e){$('log').textContent+='\\nخطا: '+e.message;}
};
</script></body></html>`;
}
