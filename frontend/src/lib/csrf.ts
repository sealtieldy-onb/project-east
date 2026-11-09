const csrfCookieName = "EAST-XSRF-TOKEN";
const csrfHeaderName = "X-EAST-XSRF-TOKEN";

/**
 * Read the Spring CSRF cookie for same-origin state-changing API requests.
 * The value is only returned to the caller to place in a request header; never log it.
 */
export function getCsrfHeaders(): Record<string, string> {
  if (typeof document === "undefined") return {};

  const cookie = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${csrfCookieName}=`));

  if (!cookie) return {};

  const value = cookie.slice(csrfCookieName.length + 1);
  if (!value) return {};

  return { [csrfHeaderName]: value };
}
