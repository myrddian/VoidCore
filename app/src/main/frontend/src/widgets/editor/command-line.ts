export type Command =
  | { kind: "save" }
  | { kind: "save_quit" }
  | { kind: "quit"; force?: boolean }
  | { kind: "reload" }
  | { kind: "toggle_ro" }
  | { kind: "unknown"; input: string };

export function parseCommand(raw: string): Command {
  const s = raw.trim();
  if (s === "ZZ") return { kind: "save_quit" };
  switch (s) {
    case ":w":      return { kind: "save" };
    case ":q":      return { kind: "quit" };
    case ":wq":     return { kind: "save_quit" };
    case ":q!":     return { kind: "quit", force: true };
    case ":e!":     return { kind: "reload" };
    case ":set ro": return { kind: "toggle_ro" };
    default:        return { kind: "unknown", input: raw };
  }
}
