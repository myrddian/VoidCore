import { type Envelope, PROTOCOL_VERSION } from "./types.js";

/** Build an outbound envelope. seq=0 / mac=null per SPEC §4.2 (v1). */
export function envelope<P>(type: string, payload: P, id: string | null = null): Envelope<P> {
  return {
    id: id ?? makeId(),
    type,
    protocol_version: PROTOCOL_VERSION,
    seq: 0,
    mac: null,
    payload,
  };
}

let counter = 0;
function makeId(): string {
  counter = (counter + 1) & 0xffffff;
  return `c-${Date.now().toString(36)}-${counter.toString(36)}`;
}
