import { findCids } from "./cid";
import type { AttachmentInput, RequestDraft } from "./types";

/** The content-ids the request body refers to, which is what decides which files are attached. */
export const referencedCids = (draft: Pick<RequestDraft, "body" | "mtom">): string[] =>
  findCids(draft.body, { cdata: !draft.mtom });

/**
 * The files that go out with the request: the ones assigned to a `cid:` the body still refers to. A file whose
 * reference was deleted from the body stays in memory, so that retyping it does not lose the choice, but is not sent.
 */
export function activeAttachments(draft: Pick<RequestDraft, "body" | "mtom" | "attachments">): AttachmentInput[] {
  const wanted = new Set(referencedCids(draft));
  return draft.attachments.filter((a) => wanted.has(a.cid));
}
