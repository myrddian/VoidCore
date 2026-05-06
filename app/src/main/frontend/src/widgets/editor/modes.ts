export type Mode = "READ_ONLY" | "NORMAL" | "INSERT" | "COMMAND";

/**
 * Returns the next mode given the current mode + key. Pure function;
 * doesn't perform any edits or motion. Editing logic lives in edits.ts;
 * motion in motions.ts.
 *
 * Insert-entry keys: i I a A o O.
 * Esc: INSERT/COMMAND → NORMAL.
 * READ_ONLY can't enter INSERT/COMMAND.
 */
export function transitionFromKey(mode: Mode, key: string): Mode {
  if (mode === "READ_ONLY") return "READ_ONLY";

  if (mode === "INSERT" || mode === "COMMAND") {
    if (key === "Escape") return "NORMAL";
    return mode;
  }

  if (["i", "I", "a", "A", "o", "O"].includes(key)) return "INSERT";
  if (key === ":") return "COMMAND";
  return "NORMAL";
}
