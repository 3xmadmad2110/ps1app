const basePackage = document.querySelector('.brand small')?.textContent?.trim() || 'com.farsipsx.ps1app';
const nameInput = document.getElementById('game-name');
const slugInput = document.getElementById('game-slug');
const preview = document.getElementById('package-preview');

const faMap = {
  'ا':'a','آ':'a','أ':'a','ب':'b','پ':'p','ت':'t','ث':'s','ج':'j','چ':'ch','ح':'h','خ':'kh',
  'د':'d','ذ':'z','ر':'r','ز':'z','ژ':'zh','س':'s','ش':'sh','ص':'s','ض':'z','ط':'t','ظ':'z',
  'ع':'a','غ':'gh','ف':'f','ق':'g','ک':'k','ك':'k','گ':'g','ل':'l','م':'m','ن':'n','و':'v',
  'ه':'h','ی':'y','ي':'y','ۀ':'e','ة':'e',
};

function slugify(text) {
  let latin = '';
  for (const ch of text || '') latin += faMap[ch] || ch;
  let slug = latin.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 40).replace(/_+$/g, '');
  if (!slug) slug = 'game';
  if (/^[0-9]/.test(slug)) slug = 'g_' + slug;
  return slug;
}

function refreshPreview() {
  const slug = slugInput?.value?.trim() || slugify(nameInput?.value || '');
  if (preview) preview.textContent = basePackage + '.' + (slug || '…');
}
nameInput?.addEventListener('input', () => { if (!slugInput?.value) refreshPreview(); });
slugInput?.addEventListener('input', refreshPreview);
refreshPreview();

const sizeText = (n) => n > 1073741824 ? `${(n / 1073741824).toFixed(2)} GB`
  : n > 1048576 ? `${(n / 1048576).toFixed(1)} MB`
  : n > 1024 ? `${Math.round(n / 1024)} KB` : `${n} B`;

// ---------- Selected-file chips ----------
const fileFields = [
  ['bin-file', 'دیسک'],
  ['cue-file', 'CUE'],
  ['cover-file', 'کاور'],
];
const summary = document.getElementById('file-summary');

function refreshFileSummary() {
  if (!summary) return;
  const items = fileFields.map(([id, label]) => {
    const input = document.getElementById(id) || document.querySelector(`input[name="${id.replace('-file','')}"]`);
    const file = input?.files?.[0];
    if (!file) return '';
    return `<span class="file-chip"><b>${label}</b> ${file.name} <em>${sizeText(file.size)}</em></span>`;
  }).filter(Boolean);
  summary.innerHTML = items.length
    ? `<div class="file-chips">${items.join('')}</div>`
    : '<span class="summary-empty">هیچ فایلی انتخاب نشده است</span>';
}

// Highlight drop zones + refresh chips on change/drop
document.querySelectorAll('.file-field').forEach((label) => {
  const input = label.querySelector('input[type=file]');
  ['dragenter', 'dragover'].forEach((event) => label.addEventListener(event, (e) => {
    e.preventDefault(); label.classList.add('dragging');
  }));
  ['dragleave', 'drop'].forEach((event) => label.addEventListener(event, (e) => {
    e.preventDefault(); label.classList.remove('dragging');
  }));
  label.addEventListener('drop', (e) => {
    if (e.dataTransfer.files.length) {
      input.files = e.dataTransfer.files;
      refreshFileSummary();
    }
  });
  input?.addEventListener('change', refreshFileSummary);
});
refreshFileSummary();

// ---------- Upload with progress + retry ----------
const form = document.getElementById('upload-form');
const button = document.getElementById('submit-btn');
const retryButton = document.getElementById('retry-btn');
const status = document.getElementById('upload-status');
const errorBox = document.getElementById('upload-error');
const progressWrap = document.getElementById('progress-wrap');
const progressBar = document.getElementById('progress-bar');
const progressPercent = document.getElementById('progress-percent');
const progressLabel = document.getElementById('progress-label');
const progressDetail = document.getElementById('progress-detail');

let lastFormData = null;
let busy = false;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function setProgress(percent, label, detail) {
  if (!progressWrap) return;
  progressWrap.hidden = false;
  const value = Math.max(0, Math.min(100, Math.round(percent)));
  progressBar.style.width = value + '%';
  progressPercent.textContent = value + '%';
  progressBar.classList.toggle('done', value === 100);
  if (label) progressLabel.textContent = label;
  if (detail !== undefined) progressDetail.textContent = detail;
}

function showError(message, canRetry) {
  if (errorBox) {
    errorBox.hidden = false;
    errorBox.textContent = message;
  }
  if (status) status.textContent = 'آپلود با خطا مواجه شد.';
  if (retryButton) retryButton.hidden = !canRetry;
  if (button) button.disabled = false;
  busy = false;
}

function clearError() {
  if (errorBox) { errorBox.hidden = true; errorBox.textContent = ''; }
  if (retryButton) retryButton.hidden = true;
}

function uploadOnce(formData) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/jobs', true);
    xhr.timeout = 30 * 60 * 1000; // large disc images may take a while

    xhr.upload.addEventListener('progress', (event) => {
      if (!event.lengthComputable) {
        setProgress(0, 'در حال آپلود فایلها…', 'در حال محاسبه حجم…');
        return;
      }
      const percent = (event.loaded / event.total) * 100;
      setProgress(percent, 'در حال آپلود فایلها…', `${sizeText(event.loaded)} از ${sizeText(event.total)}`);
    });

    xhr.addEventListener('load', () => {
      let data = {};
      try { data = JSON.parse(xhr.responseText); } catch { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) return resolve(data);
      const err = new Error(data.error || `خطای سرور (${xhr.status})`);
      err.http = xhr.status;
      reject(err);
    });
    xhr.addEventListener('error', () => reject(new Error('اتصال به سرور پنل قطع شد.')));
    xhr.addEventListener('timeout', () => reject(new Error('زمان آپلود به پایان رسید.')));
    xhr.addEventListener('abort', () => reject(new Error('آپلود لغو شد.')));
    xhr.send(formData);
  });
}

async function startUpload(formData) {
  if (busy) return;
  busy = true;
  lastFormData = formData;
  clearError();
  if (button) button.disabled = true;
  if (retryButton) retryButton.hidden = true;
  if (status) status.textContent = '';
  setProgress(0, 'در حال آپلود فایلها…', 'آمادهایی…');

  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      if (attempt > 1) {
        setProgress(0, `تلاش مجدد (${attempt} از 3)…`, 'اتصال دوباره برقرار میشود…');
        if (status) status.textContent = `تلاش مجدد ${attempt} از 3…`;
        await sleep(800 * (attempt - 1));
      }
      const data = await uploadOnce(formData);
      setProgress(100, 'آپلود به پنل کامل شد', 'در حال ساخت OBB و انتشار ریلیز GitHub…');
      if (status) status.textContent = 'آپلود به پنل کامل شد · در حال ساخت OBB و ریلیز GitHub…';
      await sleep(600);
      location.href = '/jobs/' + encodeURIComponent(data.slug);
      return;
    } catch (error) {
      const retryableNetwork = !error.http;
      if (retryableNetwork && attempt < 3) continue; // auto-retry
      showError(error.message || 'آپلود ناموفق بود.', true);
      return;
    }
  }
}

form?.addEventListener('submit', (event) => {
  event.preventDefault();
  startUpload(new FormData(form));
});

retryButton?.addEventListener('click', () => {
  if (lastFormData) startUpload(lastFormData);
});

// ---------- Job page actions ----------
document.querySelectorAll('[data-action]').forEach((buttonEl) => {
  buttonEl.addEventListener('click', async () => {
    const { action, slug } = buttonEl.dataset;
    buttonEl.disabled = true;
    try {
      await fetch(`/api/jobs/${encodeURIComponent(slug)}/${action}`, { method: 'POST' });
      location.reload();
    } catch {
      buttonEl.disabled = false;
    }
  });
});

// ---------- Auto-refresh while building ----------
const jobData = document.getElementById('job-data');
if (jobData) {
  const job = JSON.parse(jobData.textContent);
  if (['uploaded', 'preparing', 'prepared', 'building'].includes(job.status)) {
    const delay = job.publishProgress ? 1500 : 8000;
    setTimeout(() => location.reload(), delay);
  }
}
