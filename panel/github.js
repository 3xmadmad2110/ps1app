import fs from 'node:fs';
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

export async function getRelease(tag) {
  try {
    return await request(`${repoPath()}/releases/tags/${encodeURIComponent(tag)}`);
  } catch (error) {
    if (error.status === 404) return null;
    throw error;
  }
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

export async function deleteAsset(assetId) {
  await request(`${repoPath()}/releases/assets/${assetId}`, { method: 'DELETE' });
}

export async function uploadAsset(release, filePath, name, contentType) {
  const stat = await fs.promises.stat(filePath);
  const url = release.upload_url.replace(/\{\?[^}]*\}/, `?name=${encodeURIComponent(name)}`);
  return request(url, {
    method: 'POST',
    headers: {
      'Content-Type': contentType || 'application/octet-stream',
      'Content-Length': String(stat.size),
    },
    body: fs.createReadStream(filePath),
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
