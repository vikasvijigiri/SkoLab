/**
 * A minimal zip writer and reader (stored entries, no compression), for the
 * fake backend's archive and import routes and the tests that drive them.
 * The writer is the app's own (src/lib/zip.ts); the reader is test-only.
 */

import type { ZipEntry } from "../lib/zip";

export { writeZip, type ZipEntry } from "../lib/zip";

/** The entries of a zip of stored entries; throws on anything else (a deflated entry, a damaged file). */
export function readZip(zip: Uint8Array): ZipEntry[] {
  const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  let end = -1;
  for (let i = zip.length - 22; i >= 0; i -= 1) {
    if (view.getUint32(i, true) === 0x06054b50) {
      end = i;
      break;
    }
  }
  if (end < 0) throw new Error("not a zip");
  const count = view.getUint16(end + 10, true);
  let at = view.getUint32(end + 16, true);
  const decoder = new TextDecoder();
  const entries: ZipEntry[] = [];
  for (let i = 0; i < count; i += 1) {
    if (at + 46 > zip.length || view.getUint32(at, true) !== 0x02014b50) throw new Error("damaged zip");
    const method = view.getUint16(at + 10, true);
    const size = view.getUint32(at + 20, true);
    const nameLength = view.getUint16(at + 28, true);
    const extra = view.getUint16(at + 30, true);
    const comment = view.getUint16(at + 32, true);
    const local = view.getUint32(at + 42, true);
    const name = decoder.decode(zip.subarray(at + 46, at + 46 + nameLength));
    if (method !== 0) throw new Error("compressed entries are not supported");
    const dataAt = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
    if (dataAt + size > zip.length) throw new Error("damaged zip");
    entries.push({ name, data: zip.slice(dataAt, dataAt + size) });
    at += 46 + nameLength + extra + comment;
  }
  return entries;
}
