/** What must never be shown in clear: credentials in headers. Port of SecretMasker.java. */

export const MASK = "****";

const SECRET_NAME = /authorization|authbasic|authbearer|cookie|token|secret|password|passwd|pkpass|api[-_]?key|credential/i;

export function isSecretName(name: string): boolean {
  return SECRET_NAME.test(name);
}

/** Masks the value of a header whose name suggests credentials, keeping the auth scheme (`Basic ****`). */
export function maskHeader(name: string, value: string): string {
  if (!isSecretName(name) || !value) return value;
  if (name.toLowerCase().endsWith("authorization")) {
    const space = value.indexOf(" ");
    if (space > 0) return `${value.slice(0, space)} ${MASK}`;
  }
  return MASK;
}
