/**
 * Shape types mirroring the Java Element sealed interface.
 * Matched by the "kind" Jackson discriminator on the wire.
 */

export type Element =
  | VStack | TextEl | Para | Rule | Spacer | Padded | Styled
  | Header | StatusLine | KeyMenu | TextField | Editor | Form;

export interface VStack    { kind: "vstack"; children: Element[]; gap: number; }
export interface TextEl    { kind: "text"; content: string; style: string; }
export interface Para      { kind: "para"; content: string; style: string; }
export interface Rule      { kind: "rule"; }
export interface Spacer    { kind: "spacer"; rows: number; }
export interface Padded    { kind: "padded"; child: Element; leftCols: number; }
export interface Styled    { kind: "styled"; child: Element; style: string; }

export interface Header     { kind: "header"; title: string; rightAnnotation: string | null; }
export interface StatusLine { kind: "statusLine"; mode: string; left: string; right: string; }
export interface KeyMenu    { kind: "keyMenu"; entries: { key: string; label: string }[]; }

export interface TextField {
  kind: "textField";
  id: string; label: string; value: string;
  maxLength: number | null; readOnly: boolean;
}

export interface Editor {
  kind: "editor";
  id: string; content: string; mode: string;
  syntaxMode: string; readOnly: boolean;
}

export interface Form {
  kind: "form"; id: string;
  children: Element[]; focusedChildId: string | null;
}
