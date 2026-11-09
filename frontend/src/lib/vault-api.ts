import { getCsrfHeaders } from "./csrf";

export type VaultStatus = {
  initialized: boolean;
  unlocked: boolean;
};

export type VaultRequestErrorKind = "unavailable" | "rejected";

export class VaultRequestError extends Error {
  readonly kind: VaultRequestErrorKind;
  readonly status: number | null;

  constructor(message: string, kind: VaultRequestErrorKind, status: number | null = null) {
    super(message);
    this.name = "VaultRequestError";
    this.kind = kind;
    this.status = status;
  }
}

const vaultPath = "/api/v1/vault";
const requestTimeoutMs = 5000;

async function sendRequest(path: string, method: "GET" | "POST", body?: Record<string, string>): Promise<Response> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), requestTimeoutMs);

  try {
    const headers: Record<string, string> = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET") Object.assign(headers, getCsrfHeaders());

    const response = await fetch(`${vaultPath}${path}`, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers: Object.keys(headers).length === 0 ? undefined : headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    if (!response.ok) {
      const unavailable = response.status === 404 || response.status >= 500;
      throw new VaultRequestError(
        unavailable
          ? "The local vault service is unavailable. You can continue with fictional demo data."
          : response.status === 401
            ? "The current passphrase could not migrate the existing encrypted data. Check it and try again."
            : `The vault request was rejected (HTTP ${response.status}). Review the information and try again.`,
        unavailable ? "unavailable" : "rejected",
        response.status,
      );
    }

    return response;
  } catch (error) {
    if (error instanceof VaultRequestError) throw error;
    throw new VaultRequestError(
      "The local vault service could not be reached. You can continue with fictional demo data.",
      "unavailable",
    );
  } finally {
    window.clearTimeout(timeoutId);
  }
}

function isVaultStatus(value: unknown): value is VaultStatus {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.initialized === "boolean" &&
    typeof candidate.unlocked === "boolean"
  );
}

export async function getVaultStatus(): Promise<VaultStatus> {
  const response = await sendRequest("/status", "GET");
  let payload: unknown;

  try {
    payload = await response.json();
  } catch {
    throw new VaultRequestError("The local vault returned an invalid status response.", "unavailable");
  }

  if (!isVaultStatus(payload) || (payload.unlocked && !payload.initialized)) {
    throw new VaultRequestError("The local vault returned an invalid status response.", "unavailable");
  }

  return {
    initialized: payload.initialized,
    unlocked: payload.unlocked,
  };
}

export async function unlockVault(passphrase: string): Promise<void> {
  await sendRequest("/unlock", "POST", { passphrase });
}

export async function resetVault(confirmation: string): Promise<void> {
  await sendRequest("/reset", "POST", { confirmation });
}
