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

// Highlight drop zones
document.querySelectorAll('.file-field').forEach((label) => {
  const input = label.querySelector('input[type=file]');
  ['dragenter', 'dragover'].forEach((event) => label.addEventListener(event, (e) => {
    e.preventDefault(); label.classList.add('dragging');
  }));
  ['dragleave', 'drop'].forEach((event) => label.addEventListener(event, (e) => {
    e.preventDefault(); label.classList.remove('dragging');
  }));
  label.addEventListener('drop', (e) => {
    if (e.dataTransfer.files.length) input.files = e.dataTransfer.files;
  });
});

// Upload progress via fetch so the UI can show status
const form = document.getElementById('upload-form');
form?.addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = document.getElementById('submit-btn');
  const status = document.getElementById('upload-status');
  button.disabled = true;
  status.textContent = 'در حال آپلود فایلها… لطفاً صبر کنید.';
  try {
    const response = await fetch('/api/jobs', { method: 'POST', body: new FormData(form) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'آپلود ناموفق بود');
    status.textContent = 'آپلود کامل شد. در حال هدایت به صفحه بازی…';
    location.href = '/jobs/' + encodeURIComponent(data.slug);
  } catch (error) {
    status.textContent = error.message;
    button.disabled = false;
  }
});

// Job page actions
document.querySelectorAll('[data-action]').forEach((button) => {
  button.addEventListener('click', async () => {
    const { action, slug } = button.dataset;
    button.disabled = true;
    try {
      await fetch(`/api/jobs/${encodeURIComponent(slug)}/${action}`, { method: 'POST' });
      location.reload();
    } catch {
      button.disabled = false;
    }
  });
});

// Auto-refresh while building
const jobData = document.getElementById('job-data');
if (jobData) {
  const job = JSON.parse(jobData.textContent);
  if (['uploaded', 'preparing', 'prepared', 'building'].includes(job.status)) {
    setTimeout(() => location.reload(), 8000);
  }
}
