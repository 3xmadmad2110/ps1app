import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';

const storePath = () => path.join(config.dataDir, 'jobs.json');
let cache = null;

function ensure() {
  fs.mkdirSync(config.dataDir, { recursive: true });
  if (!cache) {
    try {
      cache = JSON.parse(fs.readFileSync(storePath(), 'utf8'));
      if (!Array.isArray(cache)) cache = [];
    } catch {
      cache = [];
    }
  }
}

function persist() {
  ensure();
  const tmp = storePath() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(cache, null, 2), 'utf8');
  fs.renameSync(tmp, storePath());
}

export function allJobs() {
  ensure();
  return cache.slice().sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

export function getJob(slug) {
  ensure();
  return cache.find((j) => j.slug === slug) || null;
}

export function jobDir(slug) {
  return path.join(config.dataDir, 'jobs', slug);
}

export function createJob(data) {
  ensure();
  const now = new Date().toISOString();
  const job = {
    slug: data.slug,
    name: data.name,
    packageName: data.packageName,
    versionCode: data.versionCode,
    status: 'uploaded',
    step: 'فایلها دریافت شد',
    error: null,
    files: data.files,
    obbName: data.obbName,
    releaseTag: data.releaseTag,
    releaseUrl: null,
    apkUrl: null,
    obbUrl: null,
    assets: [],
    createdAt: now,
    updatedAt: now,
    log: [{ ts: now, level: 'info', message: 'آپلود کامل شد' }],
  };
  const i = cache.findIndex((j) => j.slug === slug);
  if (i >= 0) cache[i] = job;
  else cache.push(job);
  persist();
  return job;
}

export function updateJob(slug, patch, logLine) {
  ensure();
  const job = getJob(slug);
  if (!job) return null;
  Object.assign(job, patch, { updatedAt: new Date().toISOString() });
  if (logLine) addLog(slug, logLine.level || 'info', logLine.message);
  persist();
  return job;
}

export function addLog(slug, level, message) {
  ensure();
  const job = getJob(slug);
  if (!job) return;
  job.log.push({ ts: new Date().toISOString(), level, message });
  if (job.log.length > 300) job.log = job.log.slice(-300);
  persist();
}
