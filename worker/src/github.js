// Minimal GitHub REST client used by the Worker.
// The PAT is read from the Worker environment, never from the browser.

const API = 'https://api.github.com';

function headers(env) {
  return {
    'Authorization': `Bearer ${env.GITHUB_TOKEN}`,
    'Accept': 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'ps1app-panel',
  };
}

export function repoUrl(env, suffix = '') {
  return `${API}/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}${suffix}`;
}

async function request(env, url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: { ...headers(env), ...(options.headers || {}) },
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  if (!response.ok) {
    const message = body?.message || text || `HTTP ${response.status}`;
    throw new Error(`GitHub ${response.status}: ${message}`);
  }
  return body;
}

export async function getRelease(env, tag) {
  try {
    return await request(env, repoUrl(env, `/releases/tags/${encodeURIComponent(tag)}`));
  } catch (error) {
    if (String(error.message).includes('404')) return null;
    throw error;
  }
}

export async function createRelease(env, { tag, name, body, draft = false }) {
  return request(env, repoUrl(env, '/releases'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tag_name: tag, name, body, draft, prerelease: false }),
  });
}

export async function updateRelease(env, releaseId, { body }) {
  return request(env, repoUrl(env, `/releases/${releaseId}`), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body }),
  });
}

export async function uploadAsset(env, uploadUrl, name, content, contentType) {
  // uploadUrl may contain a query string (e.g. ?name=...); strip it and set explicitly.
  const clean = String(uploadUrl).split('?')[0];
  return request(env, `${clean}?name=${encodeURIComponent(name)}`, {
    method: 'POST',
    headers: { 'Content-Type': contentType },
    body: content,
  });
}

export async function deleteAsset(env, assetId) {
  await request(env, repoUrl(env, `/releases/assets/${assetId}`), { method: 'DELETE' });
}

export async function dispatchWorkflow(env, workflow, ref, clientPayload) {
  const response = await fetch(repoUrl(env, `/actions/workflows/${workflow}/dispatches`), {
    method: 'POST',
    headers: {
      ...headers(env),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ ref, inputs: clientPayload }),
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`GitHub dispatch ${response.status}: ${text}`);
  }
  return true;
}
