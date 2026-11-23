import { Transform } from 'node:stream';

/**
 * Minimal streaming multipart/form-data parser (zero dependencies).
 * Emits onPart(headers), onData(buffer), onEnd() for each part.
 */
export class MultipartParser extends Transform {
  constructor(boundary, handlers) {
    super();
    this.openBoundary = Buffer.from('--' + boundary);
    this.delimiter = Buffer.from('\r\n--' + boundary);
    this.handlers = handlers;
    this.state = 'preamble';
    this.buf = Buffer.alloc(0);
    this.done = false;
  }

  _transform(chunk, _enc, cb) {
    if (this.done) return cb();
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk;
    try {
      this.drain(false);
      cb();
    } catch (err) {
      cb(err);
    }
  }

  _flush(cb) {
    try {
      this.drain(true);
      cb();
    } catch (err) {
      cb(err);
    }
  }

  drain(final) {
    if (this.done) return;
    for (;;) {
      if (this.state === 'preamble') {
        const i = this.buf.indexOf(this.openBoundary);
        if (i === -1) {
          if (final) throw new Error('multipart: boundary not found');
          const keep = this.openBoundary.length - 1;
          if (this.buf.length > keep) this.buf = this.buf.subarray(this.buf.length - keep);
          return;
        }
        this.buf = this.buf.subarray(i + this.openBoundary.length);
        this.state = 'afterBoundary';
        continue;
      }

      if (this.state === 'afterBoundary') {
        if (this.buf.length < 2) {
          if (final) throw new Error('multipart: truncated boundary');
          return;
        }
        const marker = this.buf.subarray(0, 2).toString('latin1');
        if (marker === '--') {
          this.done = true;
          this.buf = Buffer.alloc(0);
          return;
        }
        if (marker !== '\r\n') throw new Error('multipart: invalid boundary ending');
        this.buf = this.buf.subarray(2);
        this.state = 'headers';
        continue;
      }

      if (this.state === 'headers') {
        const i = this.buf.indexOf('\r\n\r\n');
        if (i === -1) {
          if (this.buf.length > 64 * 1024) throw new Error('multipart: headers too large');
          if (final) throw new Error('multipart: truncated headers');
          return;
        }
        const head = this.buf.subarray(0, i).toString('utf8');
        this.buf = this.buf.subarray(i + 4);
        this.handlers.onPart(parseHeaders(head));
        this.state = 'body';
        continue;
      }

      if (this.state === 'body') {
        const i = this.buf.indexOf(this.delimiter);
        if (i === -1) {
          const keep = this.delimiter.length - 1;
          if (this.buf.length > keep) {
            this.handlers.onData(this.buf.subarray(0, this.buf.length - keep));
            this.buf = this.buf.subarray(this.buf.length - keep);
          }
          if (final) throw new Error('multipart: truncated body');
          return;
        }
        if (i > 0) this.handlers.onData(this.buf.subarray(0, i));
        this.handlers.onEnd();
        this.buf = this.buf.subarray(i + this.delimiter.length);
        this.state = 'afterBoundary';
        continue;
      }
    }
  }
}

function parseHeaders(text) {
  const out = { name: '', filename: '', contentType: 'application/octet-stream' };
  for (const line of text.split('\r\n')) {
    const idx = line.indexOf(':');
    if (idx < 0) continue;
    const key = line.slice(0, idx).trim().toLowerCase();
    const value = line.slice(idx + 1).trim();
    if (key === 'content-disposition') {
      const n = value.match(/name="([^"]*)"/i);
      if (n) out.name = n[1];
      const f = value.match(/filename="([^"]*)"/i);
      if (f) out.filename = f[1];
      const star = value.match(/filename\*=UTF-8''([^;]+)/i);
      if (star) {
        try { out.filename = decodeURIComponent(star[1]); } catch { /* ignore */ }
      }
    } else if (key === 'content-type') {
      out.contentType = value;
    }
  }
  return out;
}
