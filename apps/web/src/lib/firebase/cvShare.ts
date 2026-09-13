import { collection, addDoc, serverTimestamp } from "firebase/firestore";
import { requireDb } from "./client";

/**
 * Writes a lightweight "I sent you my CV" record under the recipient's own
 * `researchers/{toUid}/cvShares` subcollection (decisions/0023's "Send to a
 * connection" flow). Mirrors the write shape of `CvShare` in lib/types.ts;
 * see firestore.rules for who may read/write it.
 */
export async function shareCv(opts: {
  toUid: string;
  fromUid: string;
  fromName: string;
  cvHref: string;
}): Promise<void> {
  await addDoc(collection(requireDb(), "researchers", opts.toUid, "cvShares"), {
    fromUid: opts.fromUid,
    fromName: opts.fromName,
    toUid: opts.toUid,
    cvHref: opts.cvHref,
    read: false,
    ts: serverTimestamp(),
  });
}
