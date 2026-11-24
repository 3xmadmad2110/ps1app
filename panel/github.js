import fs from 'node:fs';
import { Transform } from 'node:stream';
import { config } from './config.js';

const API = 'https://api.github.com';
const repoPath = () => `/repos/${config.github.owner}/${config.github.repo}`;

async function request(urlPath, options = {}) {
  const res = await fetch(urlPath.startsWith('http') ? urlPath : API + urlPath, {
    method: options.method || 'GET',
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${config.github.token}`,
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'ps1app-panel',
      ...(options.headers || {}),
    },
    body: options.body,
    duplex: options.body && typeof options.body.pipe === 'function' ? 'half' : undefined,
  });
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!res.ok) {
    const message = body && body.message ? body.message : String(text).slice(0, 400);
    const error = new Error(`GitHub ${res.status}: ${message}`);
    error.status = res.status;
    error.body = body;
    throw error;
  }
  return body;
}

export async function listReleases() {
  const releases = [];
  for (let page = 1; ; page += 1) {
    const batch = await request(`${repoPath()}/releases?per_page=100&page=${page}`);
    releases.push(...batch);
    if (!Array.isArray(batch) || batch.length < 100) break;
  }
  return releases;
}

export async function getRelease(tag) {
  try {
    return await request(`${repoPath()}/releases/tags/${encodeURIComponent(tag)}`);
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  // Draft releases are not returned by the /releases/tags endpoint.
  const matches = (await listReleases()).filter((release) => release.tag_name === tag);
  if (!matches.length) return null;
  matches.sort((a, b) => {
    const assetDiff = (b.assets?.length || 0) - (a.assets?.length || 0);
    if (assetDiff) return assetDiff;
    return new Date(b.created_at || 0) - new Date(a.created_at || 0);
  });
  return matches[0];
}

export async function createRelease({ tag, name, body }) {
  return request(`${repoPath()}/releases`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tag_name: tag, name, body, draft: true, prerelease: false }),
  });
}

export async function publishRelease(releaseId) {
  return request(`${repoPath()}/releases/${releaseId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ draft: false }),
  });
}

export async function deleteRelease(releaseId) {
  await request(`${repoPath()}/releases/${releaseId}`, { method: 'DELETE' });
}

export async function deleteAsset(assetId) {
  await request(`${repoPath()}/releases/assets/${assetId}`, { method: 'DELETE' });
}

export async function uploadAsset(release, filePath, name, contentType, onProgress) {
  const stat = await fs.promises.stat(filePath);
  const url = release.upload_url.replace(/\{\?[^}]*\}/, `?name=${encodeURIComponent(name)}`);
  let body = fs.createReadStream(filePath);
  if (onProgress) {
    let sent = 0;
    let lastPercent = -1;
    body = body.pipe(new Transform({
      transform(chunk, _encoding, callback) {
        sent += chunk.length;
        const percent = stat.size ? Math.min(100, Math.round((sent / stat.size) * 100)) : 100;
        if (percent !== lastPercent) {
          lastPercent = percent;
          onProgress(percent, sent, stat.size);
        }
        callback(null, chunk);
      },
    }));
  }
  return request(url, {
    method: 'POST',
    headers: {
      'Content-Type': contentType || 'application/octet-stream',
      'Content-Length': String(stat.size),
    },
    body,
  });
}

export async function dispatchWorkflow(inputs) {
  await request(`${repoPath()}/actions/workflows/${encodeURIComponent(config.github.workflow)}/dispatches`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ref: config.github.ref, inputs }),
  });
}

export async function listWorkflowRuns() {
  const data = await request(`${repoPath()}/actions/workflows/${encodeURIComponent(config.github.workflow)}/runs?per_page=10`);
  return data.workflow_runs || [];
}
