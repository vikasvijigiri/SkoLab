/** Hands the browser a file to save. */
export function download(name: string, data: BlobPart, type: string) {
  const url = URL.createObjectURL(new Blob([data], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** A title made safe to use as a file name. */
export function fileName(title: string) {
  return title.trim().replace(/[^\w.-]+/g, "-").replace(/^-+|-+$/g, "") || "document";
}

/** The bytes of a fetched file. */
export async function bytesOf(blob: Blob): Promise<Uint8Array<ArrayBuffer>> {
  return new Uint8Array(await blob.arrayBuffer());
}

/** A data: URL, which the site's CSP allows for images (blob: it does not). */
export function dataUrl(bytes: Uint8Array, type: string): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return `data:${type};base64,${btoa(binary)}`;
}
