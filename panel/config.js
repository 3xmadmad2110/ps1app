import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function loadEnvFile(file) {
  try {
    const text = fs.readFileSync(file, 'utf8');
    for (const line of text.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)\s*$/);
      if (!m || process.env[m[1]] !== undefined) continue;
      let value = m[2].trim();
      if ((value.startsWith('"') && value.endsWith('"')) ||
          (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      process.env[m[1]] = value;
    }
  } catch {
    // .env is optional
  }
}

loadEnvFile(path.join(root, '.env'));

const intEnv = (name, fallback) => {
  const n = Number.parseInt(process.env[name] ?? '', 10);
  return Number.isFinite(n) ? n : fallback;
};

export const config = {
  root,
  dataDir: process.env.PANEL_DATA_DIR || path.join(root, 'panel-data'),
  host: process.env.HOST || '0.0.0.0',
  port: intEnv('PORT', 7788),
  buildMode: (process.env.BUILD_MODE || 'actions').toLowerCase(),
  basePackage: process.env.BASE_PACKAGE || 'com.farsipsx.ps1app',
  versionCode: intEnv('VERSION_CODE', 1),
  versionName: process.env.VERSION_NAME || '1.0',
  androidDir: path.join(root, 'android'),
  scriptsDir: path.join(root, 'scripts'),
  github: {
    token: process.env.GITHUB_TOKEN || '',
    owner: process.env.GITHUB_OWNER || '',
    repo: process.env.GITHUB_REPO || '',
    workflow: process.env.GITHUB_WORKFLOW || 'build-game.yml',
    ref: process.env.GITHUB_REF || 'main',
  },
};

export function githubReady() {
  const g = config.github;
  return Boolean(g.token && g.owner && g.repo);
}

export function publicConfig() {
  return {
    basePackage: config.basePackage,
    buildMode: config.buildMode,
    versionCode: config.versionCode,
    github: {
      owner: config.github.owner,
      repo: config.github.repo,
      configured: githubReady(),
    },
  };
}
