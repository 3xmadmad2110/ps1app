import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { config, githubReady } from './config.js';
import { jobDir, getJob, updateJob, addLog } from './jobs.js';
import { createZip } from './zip.js';
import * as gh from './github.js';

export function obbFileName(job) {
  return `main.${job.versionCode}.${job.packageName}.obb`;
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

async function ensureRelease(job) {
  let release = await gh.getRelease(job.releaseTag);
  if (!release) {
    release = await gh.createRelease({
      tag: job.releaseTag,
      name: `${job.name} — PS1`,
      body: releaseBody(job),
    });
    addLog(job.slug, 'info', `ریلیز پیشنویس ${job.releaseTag} ساخته شد`);
  } else {
    addLog(job.slug, 'info', `ریلیز موجود ${job.releaseTag} مورد استفاده قرار گرفت`);
  }
  for (const asset of release.assets || []) {
    await gh.deleteAsset(asset.id);
  }
  return release;
}

async function uploadAssets(job, release, obbPath) {
  const dir = jobDir(job.slug);
  const uploads = [
    { file: obbPath, name: obbFileName(job), type: 'application/zip' },
    { file: path.join(dir, 'meta.json'), name: 'meta.json', type: 'application/json' },
  ];
  if (job.files.cover) {
    uploads.push({ file: job.files.cover.path, name: 'cover.jpg', type: job.files.cover.contentType });
  }
  const assets = [];
  for (const item of uploads) {
    const result = await gh.uploadAsset(release, item.file, item.name, item.type);
    assets.push({ name: item.name, url: result.browser_download_url });
    addLog(job.slug, 'info', `آپلود ${item.name} به ریلیز تمام شد`);
  }
  return assets;
}

async function dispatch(job) {
  await gh.dispatchWorkflow({
    slug: job.slug,
    game_name: job.name,
    package_suffix: job.slug,
    version_code: String(job.versionCode),
    release_tag: job.releaseTag,
  });
  addLog(job.slug, 'info', 'GitHub Actions برای ساخت APK ارسال شد');
}

function runCommand(command, args, cwd, onLine) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, shell: process.platform === 'win32' });
    let output = '';
    const consume = (chunk) => {
      const text = chunk.toString();
      output += text;
      for (const line of text.split(/\r?\n/)) {
        if (line.trim()) onLine(line.trim());
      }
    };
    child.stdout.on('data', consume);
    child.stderr.on('data', consume);
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) resolve(output);
      else reject(new Error(`دستور ساخت با کد ${code} متوقف شد: ${output.slice(-1500)}`));
    });
  });
}

async function localBuild(job, release, dir) {
  const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  const gradlewPath = path.join(config.androidDir, gradlew);
  const command = fs.existsSync(gradlewPath) ? gradlewPath : 'gradle';
  await runCommand(command, [
    ':app:assembleRelease',
    `-PgameSlug=${job.slug}`,
    `-PgameName=${job.name}`,
    `-PpackageSuffix=${job.slug}`,
    `-PversionCode=${job.versionCode}`,
  ], config.androidDir, (line) => addLog(job.slug, 'build', line));

  const apkDir = path.join(config.androidDir, 'app', 'build', 'outputs', 'apk', 'release');
  const files = fs.readdirSync(apkDir).filter((f) => f.endsWith('.apk'));
  if (!files.length) throw new Error('فایل APK ساخته نشد');
  const source = path.join(apkDir, files[0]);
  const apkName = `${job.name.replace(/[^\w\u0600-\u06FF-]+/g, '_')}.apk`;
  const target = path.join(dir, apkName);
  fs.copyFileSync(source, target);

  const result = await gh.uploadAsset(release, target, apkName, 'application/vnd.android.package-archive');
  await gh.publishRelease(release.id);
  return {
    apkUrl: result.browser_download_url,
    releaseUrl: release.html_url,
    assets: [{ name: apkName, url: result.browser_download_url }],
  };
}

export async function prepareJob(slug) {
  let job = getJob(slug);
  if (!job) throw new Error('چنین کاری وجود ندارد');
  const dir = jobDir(slug);
  fs.mkdirSync(dir, { recursive: true });
  const obbPath = path.join(dir, obbFileName(job));

  try {
    updateJob(slug, { status: 'preparing', step: 'ساخت فایل OBB', error: null });
    addLog(slug, 'info', 'شروع ساخت OBB');

    const meta = {
      gameName: job.name,
      slug: job.slug,
      packageName: job.packageName,
      versionCode: job.versionCode,
      bin: job.files.bin.name,
      cue: job.files.cue.name,
      cover: job.files.cover ? job.files.cover.name : null,
      obb: obbFileName(job),
    };
    const metaPath = path.join(dir, 'meta.json');
    fs.writeFileSync(metaPath, JSON.stringify(meta, null, 2), 'utf8');

    const entries = [
      { path: job.files.bin.path, name: job.files.bin.name },
      { path: job.files.cue.path, name: job.files.cue.name },
      { path: metaPath, name: 'meta.json' },
    ];
    if (job.files.cover) entries.push({ path: job.files.cover.path, name: job.files.cover.name });
    await createZip(obbPath, entries);
    addLog(slug, 'info', `OBB ساخته شد (${(fs.statSync(obbPath).size / 1048576).toFixed(1)} MB)`);

    updateJob(slug, {
      obbUrl: `/api/jobs/${slug}/obb`,
      assets: [{ name: obbFileName(job), url: `/api/jobs/${slug}/obb` }],
    });

    if (!githubReady()) {
      updateJob(slug, {
        status: 'prepared',
        step: 'OBB آماده است — GitHub تنظیم نشده',
      }, { level: 'warn', message: 'GITHUB_TOKEN/OWNER/REPO تنظیم نشده؛ فقط فایل OBB محلی ساخته شد' });
      return getJob(slug);
    }

    const release = await ensureRelease(job);
    const assets = await uploadAssets(job, release, obbPath);

    if (config.buildMode === 'local') {
      const built = await localBuild(job, release, dir);
      updateJob(slug, {
        status: 'released',
        step: 'APK و OBB در ریلیز قرار گرفتند',
        releaseUrl: built.releaseUrl,
        apkUrl: built.apkUrl,
        assets: [...assets, ...built.assets],
      }, { level: 'info', message: 'ساخت محلی و انتشار ریلیز کامل شد' });
    } else {
      await dispatch(job);
      updateJob(slug, {
        status: 'building',
        step: 'در انتظار ساخت توسط GitHub Actions',
        releaseUrl: release.html_url,
        assets,
      });
    }
  } catch (error) {
    updateJob(slug, {
      status: 'failed',
      step: 'خطا در آمادهسازی',
      error: String(error && error.message ? error.message : error),
    }, { level: 'error', message: String(error && error.message ? error.message : error) });
  }
  return getJob(slug);
}

export async function refreshJob(slug) {
  const job = getJob(slug);
  if (!job || !githubReady() || job.status === 'released') return job;

  try {
    const release = await gh.getRelease(job.releaseTag);
    if (!release) return job;
    if (!release.draft) {
      const apk = (release.assets || []).find((a) => a.name.toLowerCase().endsWith('.apk'));
      const obb = (release.assets || []).find((a) => a.name.toLowerCase().endsWith('.obb'));
      if (apk) {
        return updateJob(slug, {
          status: 'released',
          step: 'ریلیز منتشر شد',
          apkUrl: apk.browser_download_url,
          obbUrl: obb ? obb.browser_download_url : job.obbUrl,
          releaseUrl: release.html_url,
          assets: (release.assets || []).map((a) => ({ name: a.name, url: a.browser_download_url })),
        }, { level: 'info', message: 'ریلیز منتشر شد و APK در دسترس است' });
      }
    }
    const runs = await gh.listWorkflowRuns();
    if (runs.length && runs[0].conclusion === 'failure') {
      return updateJob(slug, {
        status: 'failed',
        step: 'ساخت در GitHub Actions شکست خورد',
        error: ` workflow run: ${runs[0].html_url}`,
      }, { level: 'error', message: `ساخت شکست خورد: ${runs[0].html_url}` });
    }
  } catch (error) {
    addLog(slug, 'warn', `بهروزرسانی وضعیت ناموفق: ${error.message}`);
  }
  return getJob(slug);
}
