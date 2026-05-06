/**
 * In-memory line array backing the editor. Mutating methods are
 * destructive; copy with snapshot()/restore() if you need undo.
 */
export class Buffer {

  private lines: string[];

  private constructor(lines: string[]) {
    this.lines = lines.length === 0 ? [""] : lines;
  }

  static fromString(s: string): Buffer { return new Buffer(s.split("\n")); }

  lineCount(): number { return this.lines.length; }
  getLine(i: number): string { return this.lines[i] ?? ""; }
  toString(): string { return this.lines.join("\n"); }
  snapshot(): string[] { return this.lines.slice(); }
  restore(snap: string[]): void { this.lines = snap.length === 0 ? [""] : snap.slice(); }

  setLine(i: number, value: string): void {
    if (i >= 0 && i < this.lines.length) this.lines[i] = value;
  }

  insertChar(line: number, col: number, ch: string): void {
    const cur = this.getLine(line);
    this.lines[line] = cur.slice(0, col) + ch + cur.slice(col);
  }

  deleteChar(line: number, col: number): void {
    const cur = this.getLine(line);
    if (col >= cur.length) return;
    this.lines[line] = cur.slice(0, col) + cur.slice(col + 1);
  }

  splitLine(line: number, col: number): void {
    const cur = this.getLine(line);
    const left = cur.slice(0, col);
    const right = cur.slice(col);
    this.lines.splice(line, 1, left, right);
  }

  joinLines(line: number): void {
    if (line + 1 >= this.lines.length) return;
    this.lines[line] = this.getLine(line) + this.getLine(line + 1);
    this.lines.splice(line + 1, 1);
  }

  insertLine(line: number, content: string): void {
    this.lines.splice(line, 0, content);
  }

  deleteLine(line: number): void {
    if (line < 0 || line >= this.lines.length) return;
    this.lines.splice(line, 1);
    if (this.lines.length === 0) this.lines = [""];
  }
}
