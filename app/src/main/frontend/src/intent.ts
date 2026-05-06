/**
 * Read the URL fragment as an optional deep-link intent per SPEC §4.6.
 * v1 grammar (server enforces the closed set):
 *   nfo/<filename>  bulletin/<id>  chat  user/<handle>  thread/<id>
 */
export function readIntentFromUrl(): string | undefined {
  const frag = window.location.hash;
  if (!frag || frag === "#") return undefined;
  return frag.replace(/^#/, "");
}
