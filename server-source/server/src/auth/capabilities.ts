/**
 * The authorization vocabulary.
 *
 * Two roles decide almost everything, and a capability list exists only so a trusted non-admin
 * (a family member who may fix covers) can be widened without being made an admin. It is not a
 * general ACL: there is no per-book ownership in this design, the library is shared and the
 * private layer is per-user.
 *
 * The list is a closed union on purpose. `parseGrants` rejects anything outside it, so a typo in
 * a permissions blob fails loudly at boot or at write time instead of silently granting nothing
 * while an operator believes they granted something.
 */
export const CAPABILITIES = [
  'library:read',
  'library:edit',
  'user:manage',
  'file:upload',
  'file:delete',
  'book:delete',
  'batch:delete',
  'collections:global',
  'settings:write',
  'audit:read',
  'storage:manage',
] as const;

export type Capability = (typeof CAPABILITIES)[number];

const ADMIN_CAPABILITIES: readonly Capability[] = CAPABILITIES;

const USER_CAPABILITIES: readonly Capability[] = ['library:read', 'file:upload'];

const isCapability = (value: unknown): value is Capability =>
  typeof value === 'string' && (CAPABILITIES as readonly string[]).includes(value);

export class CapabilityGrantError extends Error {
  constructor(unknown: string[]) {
    super(
      `unknown capability grant(s): ${unknown.join(', ')}. Allowed: ${CAPABILITIES.join(', ')}`,
    );
    this.name = 'CapabilityGrantError';
  }
}

/**
 * Validates a stored/submitted permissions object. Values may be `true` (widen a user) or
 * `false` (narrow an admin — pointless but harmless, and refusing it would make an accidental
 * `"file:upload": false` in a hand-edited blob un-bootable).
 */
export function parseGrants(raw: string): Partial<Record<Capability, boolean>> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new CapabilityGrantError(['<unparseable json>']);
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new CapabilityGrantError(['<not an object>']);
  }
  const out: Partial<Record<Capability, boolean>> = {};
  const unknown: string[] = [];
  for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
    if (!isCapability(key)) {
      unknown.push(key);
      continue;
    }
    if (typeof value !== 'boolean') {
      unknown.push(`${key} (value must be true or false)`);
      continue;
    }
    out[key] = value;
  }
  if (unknown.length > 0) throw new CapabilityGrantError(unknown);
  return out;
}

export interface CapabilitySubject {
  role: 'admin' | 'user';
  permissions_json: string;
  status: 'active' | 'disabled';
}

/**
 * Resolution order matters and is the whole rule set:
 *   disabled ⇒ nothing, regardless of role or grants
 *   admin    ⇒ all, minus anything explicitly set to false
 *   user     ⇒ the two defaults, plus anything explicitly set to true
 * A grant never *removes* a user default: revoking `file:upload` is `false`, and that is honoured.
 */
export function capabilitiesFor(subject: CapabilitySubject): Capability[] {
  if (subject.status !== 'active') return [];
  const grants = parseGrants(subject.permissions_json);
  const base = new Set<Capability>(
    subject.role === 'admin' ? ADMIN_CAPABILITIES : USER_CAPABILITIES,
  );
  for (const [capability, granted] of Object.entries(grants) as [Capability, boolean][]) {
    if (granted) base.add(capability);
    else base.delete(capability);
  }
  return [...base].sort();
}

export function hasCapability(capabilities: readonly Capability[], needed: Capability): boolean {
  return capabilities.includes(needed);
}
