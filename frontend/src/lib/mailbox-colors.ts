/** The first eight indices preserve East's original Nord accent assignments. */
const nordMailboxColors = [
  "#88c0d0",
  "#81a1c1",
  "#a3be8c",
  "#b48ead",
  "#d08770",
  "#ebcb8b",
  "#5e81ac",
  "#8fbcbb",
] as const;

export type MailboxColorIndex = number;

export function isMailboxColorIndex(value: unknown): value is MailboxColorIndex {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

/**
 * Return a deterministic Nord-compatible account accent for a stored mailbox index.
 * Indices after the original eight use a golden-angle HSL sequence; no palette modulo
 * is used, so adding accounts does not silently recycle one of the earlier colors.
 */
export function mailboxColorAt(index: MailboxColorIndex): string | null {
  if (!isMailboxColorIndex(index)) return null;
  if (index < nordMailboxColors.length) return nordMailboxColors[index] ?? null;

  const generatedIndex = index - nordMailboxColors.length + 1;
  const hue = (193 + generatedIndex * 137.50776405003785) % 360;
  const saturation = 34 + fractionalPart(generatedIndex * 0.618033988749895) * 16;
  const lightness = 56 + fractionalPart(generatedIndex * 0.754877666246693) * 12;
  return `hsl(${hue} ${saturation}% ${lightness}%)`;
}

function fractionalPart(value: number): number {
  return value - Math.floor(value);
}
