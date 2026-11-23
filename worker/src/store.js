// Temporary R2 storage for uploaded parts.
// Keys are scoped per job so cleanup is a single prefix listing.

function partKey(jobId, name, index) {
  return `jobs/${jobId}/parts/${name}/${String(index).padStart(6, '0')}.part`;
}

function metaKey(jobId) {
  return `jobs/${jobId}/job.json`;
}

async function readJson(bucket, key) {
  const object = await bucket.get(key);
  if (!object) return null;
  return JSON.parse(await object.text());
}

export async function createJob(bucket, job) {
  await bucket.put(metaKey(job.id), JSON.stringify(job), {
    customMetadata: { createdAt: String(Date.now()) },
  });
}

export async function getJob(bucket, jobId) {
  return readJson(bucket, metaKey(jobId));
}

export async function saveJob(bucket, job) {
  await bucket.put(metaKey(job.id), JSON.stringify(job));
}

export async function putPart(bucket, jobId, name, index, bytes) {
  await bucket.put(partKey(jobId, name, index), bytes);
}

/**
 * Async generator yielding the bytes of every part of a file in order.
 * Stops at the first missing part.
 */
export async function* readFileParts(bucket, jobId, name, count) {
  for (let i = 0; i < count; i++) {
    const object = await bucket.get(partKey(jobId, name, i));
    if (!object) throw new Error(`Missing part ${i} of ${name}`);
    yield new Uint8Array(await object.arrayBuffer());
    object.body?.cancel?.();
  }
}

export async function deleteJob(bucket, jobId) {
  let cursor;
  do {
    const listed = await bucket.list({ prefix: `jobs/${jobId}/`, cursor });
    if (listed.objects.length) await bucket.delete(listed.objects.map((o) => o.key));
    cursor = listed.truncated ? listed.cursor : undefined;
  } while (cursor);
}
