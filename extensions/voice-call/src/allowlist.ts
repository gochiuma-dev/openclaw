// Caller allowlist helpers for provider-normalized phone numbers.

/** Normalize a phone number to digits only. */
export function normalizePhoneNumber(input?: string): string {
  if (!input) {
    return "";
  }
  return input.replace(/\D/g, "");
}

/**
 * Normalize the formats commonly produced by Japanese cellular gateways.
 * chan_quectel reports domestic numbers such as 07012345678, while the
 * configuration schema requires the equivalent E.164 form +817012345678.
 */
function normalizePhoneNumberForMatch(input?: string): string {
  const digits = normalizePhoneNumber(input);
  if (/^0(?:70|80|90)\d{8}$/.test(digits)) {
    return `81${digits.slice(1)}`;
  }
  return digits;
}

/** Return true when the normalized caller exactly matches an allowlist entry. */
export function isAllowlistedCaller(
  normalizedFrom: string,
  allowFrom: string[] | undefined,
): boolean {
  if (!normalizedFrom) {
    return false;
  }
  return (allowFrom ?? []).some((num) => {
    const normalizedAllow = normalizePhoneNumberForMatch(num);
    return (
      normalizedAllow !== "" && normalizedAllow === normalizePhoneNumberForMatch(normalizedFrom)
    );
  });
}
