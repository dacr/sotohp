/**
 * Datetime helpers shared between the event-edit and media-edit forms.
 * `toLocalInputValue` formats an ISO string for `<input type="datetime-local">`.
 * `fromLocalInputValue` parses the input value back to an ISO string.
 */

export function toLocalInputValue(iso: string | undefined | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fromLocalInputValue(val: string | undefined | null): string | undefined {
  if (!val) return undefined;
  const d = new Date(val);
  if (isNaN(d.getTime())) return undefined;
  return d.toISOString();
}
