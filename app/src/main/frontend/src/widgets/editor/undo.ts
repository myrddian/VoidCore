import type { Buffer } from "./buffer.js";

/**
 * Single-step undo/redo. Holds two snapshots: `prev` and `redoSnap`.
 * snapshot(buf) replaces prev; undo() swaps prev↔redoSnap with the
 * current buffer state.
 */
export class UndoRing {
  private prev: string[] | null = null;
  private redoSnap: string[] | null = null;

  snapshot(buf: Buffer): void {
    this.prev = buf.snapshot();
    this.redoSnap = null;
  }

  undo(buf: Buffer): boolean {
    if (this.prev == null) return false;
    const cur = buf.snapshot();
    buf.restore(this.prev);
    this.redoSnap = cur;
    this.prev = null;
    return true;
  }

  redo(buf: Buffer): boolean {
    if (this.redoSnap == null) return false;
    const cur = buf.snapshot();
    buf.restore(this.redoSnap);
    this.prev = cur;
    this.redoSnap = null;
    return true;
  }
}
