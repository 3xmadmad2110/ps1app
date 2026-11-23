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
            <input type="file" name="cue" accept=".cue" required>
          </label>
          <label class="file-field">کاور بازی (اختیاری)
            <input type="file" name="cover" accept="image/*">
          </label>
        </div>
        <div id="upload-status" class="status-line"></div>
        <button type="submit" id="submit-btn">ساخت و انتشار</button>
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

  return shell(`${job.name} — PS1App`, `
    <div class="breadcrumb"><a href="/">پنل</a><span>/</span><span>${esc(job.name)}</span></div>

    <section class="card detail-head">
      <div>
        <h1>${esc(job.name)} ${badge(job.status)}</h1>
        <p class="step">${esc(job.step || '')}</p>
        ${job.error ? `<div class="error-box">${esc(job.error)}</div>` : ''}
      </div>
      <div class="actions">
        <button class="ghost" data-action="refresh" data-slug="${esc(job.slug)}">بهروزرسانی وضعیت</button>
        <button class="ghost" data-action="retry" data-slug="${esc(job.slug)}">ساخت مجدد</button>
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
