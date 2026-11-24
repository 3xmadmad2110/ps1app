const esc = (value) => String(value ?? '')
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

const badges = {
  uploaded: ['آپلود', 'badge-blue'],
  preparing: ['ساخت OBB', 'badge-yellow'],
  prepared: ['آماده (محلی)', 'badge-purple'],
  building: ['در حال ساخت APK', 'badge-yellow'],
  released: ['منتشر شد', 'badge-green'],
  failed: ['خطا', 'badge-red'],
};

function badge(status) {
  const [label, cls] = badges[status] || [status, 'badge-blue'];
  return `<span class="badge ${cls}">${esc(label)}</span>`;
}

const size = (n) => n > 1073741824 ? `${(n / 1073741824).toFixed(2)} GB`
  : n > 1048576 ? `${(n / 1048576).toFixed(1)} MB`
  : `${Math.ceil((n || 0) / 1024)} KB`;

function shell(title, body, config) {
  return `<!doctype html>
<html lang="fa" dir="rtl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${esc(title)}</title>
  <link rel="stylesheet" href="/static/styles.css">
</head>
<body>
  <header class="topbar">
    <a class="brand" href="/">
      <span class="logo">PS</span>
      <span><strong>پنل PS1App</strong><small>${esc(config.basePackage)}</small></span>
    </a>
    <nav>
      <span class="dot ${config.github.configured ? 'ok' : 'warn'}"></span>
      <span class="nav-text">${config.github.configured ? 'GitHub متصل' : 'حالت محلی'}</span>
      <a href="/health">وضعیت</a>
    </nav>
  </header>
  <main>${body}</main>
  <footer>اپ مادر PS1 · بستهها با پسوند اسم بازی ساخته میشوند</footer>
  <script src="/static/app.js"></script>
</body>
</html>`;
}

export function renderIndex(jobs, config) {
  const rows = jobs.length ? jobs.map((job) => `
    <tr>
      <td><a href="/jobs/${encodeURIComponent(job.slug)}"><strong>${esc(job.name)}</strong></a><div class="sub">${esc(job.packageName)}</div></td>
      <td>${badge(job.status)}</td>
      <td class="num">${size(job.files?.bin?.size)}</td>
      <td class="num">${job.versionCode}</td>
      <td class="date">${new Date(job.createdAt).toLocaleString('fa-IR')}</td>
      <td class="links">
        ${job.releaseUrl ? `<a href="${esc(job.releaseUrl)}" target="_blank">ریلیز</a>` : ''}
        ${job.obbUrl ? `<a href="${esc(job.obbUrl)}">OBB</a>` : ''}
      </td>
    </tr>`).join('') : `<tr><td colspan="6" class="empty">هنوز بازیای آپلود نشده است</td></tr>`;

  return shell('پنل مدیریت PS1App', `
    <section class="hero">
      <div>
        <h1>سازنده اپ مادر PS1</h1>
        <p>فایل <code>bin/cue</code> و کاور بازی را آپلود کنید. پنل یک APK با نام بازی، یک فایل OBB حاوی دیتا و یک ریلیز GitHub آماده میکند.</p>
      </div>
      <div class="flow">
        <span>آپلود</span><b>→</b><span>OBB</span><b>→</b><span>GitHub Actions</span><b>→</b><span>ریلیز</span>
      </div>
    </section>

    <section class="card">
      <h2>بازی جدید</h2>
      <form id="upload-form" action="/api/jobs" method="post" enctype="multipart/form-data">
        <div class="grid">
          <label>نام بازی
            <input name="name" id="game-name" required placeholder="مثال: God of War">
          </label>
          <label>پسوند پکیج (اختیاری)
            <input name="slug" id="game-slug" placeholder="god_of_war">
            <small>پکیج نهایی: <span id="package-preview">${esc(config.basePackage)}.</span></small>
          </label>
          <label class="file-field">فایل دیسک (.bin / .img)
            <input type="file" name="bin" id="bin-file" accept=".bin,.img" required>
          </label>
          <label class="file-field">فایل (.cue)
            <input type="file" name="cue" id="cue-file" accept=".cue" required>
          </label>
          <label class="file-field">کاور بازی (اختیاری)
            <input type="file" name="cover" id="cover-file" accept="image/*">
          </label>
        </div>
        <div class="upload-panel">
          <div id="file-summary" class="file-summary"></div>
          <div id="progress-wrap" class="progress-wrap" hidden>
            <div class="progress-head">
              <span id="progress-label">در حال آپلود فایلها…</span>
              <strong id="progress-percent" dir="ltr">0%</strong>
            </div>
            <div class="progress-track"><div id="progress-bar" class="progress-bar"></div></div>
            <div id="progress-detail" class="progress-detail"></div>
          </div>
          <div id="upload-status" class="status-line">فایلها را انتخاب کنید تا آپلود آغاز شود.</div>
          <div id="upload-error" class="error-box" hidden></div>
          <div class="form-actions">
            <button type="submit" id="submit-btn">ساخت و انتشار</button>
            <button type="button" id="retry-btn" class="ghost" hidden>تلاش مجدد آپلود</button>
          </div>
        </div>
      </form>
    </section>

    <section class="card">
      <div class="card-head"><h2>بازیها</h2><span class="count">${jobs.length}</span></div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>بازی</th><th>وضعیت</th><th>حجم BIN</th><th>نسخه</th><th>تاریخ</th><th>لینکها</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    </section>
  `, config);
}

export function renderJob(job, config) {
  const links = (job.assets || []).map((asset) => `
    <a class="asset" href="${esc(asset.url)}" target="_blank" download>
      <span class="asset-icon">↓</span>
      <span><strong>${esc(asset.name)}</strong><small>${asset.url.startsWith('http') ? 'GitHub Release' : 'روی سرور پنل'}</small></span>
    </a>`).join('');

  const logs = (job.log || []).slice().reverse().map((entry) => `
    <li class="log-${esc(entry.level)}">
      <time>${new Date(entry.ts).toLocaleTimeString('fa-IR')}</time>
      <span>${esc(entry.message)}</span>
    </li>`).join('');

  const stages = [
    ['آپلود', ['uploaded', 'preparing', 'prepared', 'building', 'released']],
    ['ساخت OBB', ['preparing', 'prepared', 'building', 'released']],
    ['ریلیز GitHub', ['prepared', 'building', 'released']],
    ['APK (Actions)', ['building', 'released']],
  ];
  const stagePercent = { uploaded: 25, preparing: 50, prepared: 65, building: 85, released: 100, failed: 50 };
  const progress = job.publishProgress;
  const percent = progress ? progress.percent : (stagePercent[job.status] ?? 25);
  const progressLabel = progress
    ? `آپلود ${progress.file} به ریلیز GitHub…`
    : (job.status === 'building' ? 'در انتظار GitHub Actions…' : 'مراحل انتشار');
  const progressNote = progress && progress.total
    ? `${size(progress.sent)} از ${size(progress.total)}`
    : '';
  const stepsHtml = stages.map(([label, doneStatuses], index) => {
    const done = job.status === 'released'
      ? true
      : job.status === 'failed'
        ? index === 0 || (index === 1 && Boolean(job.obbName || job.obbUrl))
        : doneStatuses.includes(job.status);
    const cls = done ? 'done' : (job.status === 'failed' && index === 2 ? 'error' : 'todo');
    return `<span class="step-chip ${cls}">${esc(label)}</span>`;
  }).join('<b class="step-arrow">→</b>');

  return shell(`${job.name} — PS1App`, `
    <div class="breadcrumb"><a href="/">پنل</a><span>/</span><span>${esc(job.name)}</span></div>

    <section class="card pipeline">
      <div class="pipeline-head">
        <h2>${esc(progressLabel)}</h2>
        <strong id="pipeline-percent" dir="ltr">${percent}%</strong>
      </div>
      <div class="steps">${stepsHtml}</div>
      <div class="progress-track"><div class="progress-bar ${job.status === 'released' ? 'done' : ''}" style="width:${percent}%"></div></div>
      ${progressNote ? `<p class="progress-detail" dir="ltr">${esc(progressNote)}</p>` : ''}
      <p class="pipeline-note">${job.status === 'released'
        ? 'همهچیز در ریلیز GitHub آماده است.'
        : job.status === 'failed'
          ? 'انتشار با خطا مواجه شد. جزئیات را در گزارش ساخت ببینید.'
          : 'پس از اتمام، APK بهطور خودکار به همان ریلیز اضافه میشود.'}</p>
    </section>

    <section class="card detail-head">
      <div>
        <h1>${esc(job.name)} ${badge(job.status)}</h1>
        <p class="step">${esc(job.step || '')}</p>
        ${job.error ? `<div class="error-box">${esc(job.error)}</div>` : ''}
      </div>
      <div class="actions">
        <a class="ghost" href="/">آپلود بازیnew</a>
        ${job.obbUrl ? `<button class="ghost" data-action="publish" data-slug="${esc(job.slug)}">آپلود OBB به ریلیز</button>` : ''}
        <button class="ghost" data-action="refresh" data-slug="${esc(job.slug)}">بهروزرسانی وضعیت</button>
        <button class="ghost" data-action="retry" data-slug="${esc(job.slug)}">ساخت مجدد OBB و ریلیز</button>
      </div>
    </section>

    <section class="card">
      <h2>اطلاعات بسته</h2>
      <div class="info-grid">
        <div><label>پکیج نهایی</label><strong dir="ltr">${esc(job.packageName)}</strong></div>
        <div><label>پسوند اضافه شده</label><strong dir="ltr">.${esc(job.slug)}</strong></div>
        <div><label>نسخه</label><strong>${job.versionCode}</strong></div>
        <div><label>تگ ریلیز</label><strong dir="ltr">${esc(job.releaseTag)}</strong></div>
        <div><label>فایل دیسک</label><strong dir="ltr">${esc(job.files?.bin?.name)}</strong></div>
        <div><label>فایل CUE</label><strong dir="ltr">${esc(job.files?.cue?.name)}</strong></div>
        <div><label>کاور</label><strong dir="ltr">${esc(job.files?.cover?.name || '—')}</strong></div>
        <div><label>OBB</label><strong dir="ltr">${esc(job.obbName || '')}</strong></div>
      </div>
    </section>

    <section class="card">
      <h2>فایلهای قابل دریافت</h2>
      <div class="assets">${links || '<p class="sub">هنوز فایلی آماده نشده است</p>'}</div>
      ${job.releaseUrl ? `<p><a href="${esc(job.releaseUrl)}" target="_blank">مشاهده ریلیز در GitHub ↗</a></p>` : ''}
    </section>

    <section class="card">
      <h2>گزارش ساخت</h2>
      <ul class="logs">${logs || '<li class="sub">گزارشیت وجود ندارد</li>'}</ul>
    </section>

    <script id="job-data" type="application/json">${JSON.stringify(job).replace(/</g, '\\u003c')}</script>
  `, config);
}
