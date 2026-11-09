import { getCsrfHeaders } from "./csrf";

export type AIProviderType = "openai" | "openai-compatible";

export type AIProviderSettings = {
  provider: AIProviderType | null;
  baseUrl: string | null;
  model: string | null;
  keyConfigured: boolean;
};

export type AIProviderUpdate = {
  provider: AIProviderType;
  baseUrl?: string;
  model: string;
  apiKey?: string;
};

export type RetentionSettings = {
  bodyCacheDays: number;
};

export class SettingsApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SettingsApiError";
  }
}

const settingsPath = "/api/v1/settings";
const requestTimeoutMs = 5000;

async function request(path: string, method: "GET" | "PUT" | "DELETE" | "PATCH", body?: object): Promise<Response> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), requestTimeoutMs);

  try {
    const headers: Record<string, string> = {};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET") Object.assign(headers, getCsrfHeaders());

    const response = await fetch(`${settingsPath}${path}`, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers: Object.keys(headers).length === 0 ? undefined : headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    if (!response.ok) {
      const isUnavailable = response.status === 404 || response.status >= 500;
      throw new SettingsApiError(
        isUnavailable
          ? "The local settings service is unavailable. Try again when East is running."
          : `The settings request was rejected (HTTP ${response.status}). Check the values and try again.`,
      );
    }

    return response;
  } catch (error) {
    if (error instanceof SettingsApiError) throw error;
    throw new SettingsApiError("The local settings service could not be reached. Try again when East is running.");
  } finally {
    window.clearTimeout(timeoutId);
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    throw new SettingsApiError("The local settings service returned an invalid response.");
  }
}

function isAIProviderSettings(value: unknown): value is AIProviderSettings {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Record<string, unknown>;
  return (
    (candidate.provider === null || candidate.provider === "openai" || candidate.provider === "openai-compatible") &&
    (candidate.baseUrl === null || typeof candidate.baseUrl === "string") &&
    (candidate.model === null || typeof candidate.model === "string") &&
    typeof candidate.keyConfigured === "boolean"
  );
}

function isRetentionSettings(value: unknown): value is RetentionSettings {
  if (!value || typeof value !== "object") return false;
  const days = (value as Record<string, unknown>).bodyCacheDays;
  return typeof days === "number" && Number.isSafeInteger(days) && days >= 1;
}

export async function getAIProviderSettings(): Promise<AIProviderSettings> {
  const payload = await readJson(await request("/ai-provider", "GET"));
  if (!isAIProviderSettings(payload)) {
    throw new SettingsApiError("The AI provider settings response is invalid.");
  }
  return payload;
}

export async function putAIProviderSettings(update: AIProviderUpdate): Promise<void> {
  await request("/ai-provider", "PUT", update);
}

export async function deleteAIProviderSettings(): Promise<void> {
  await request("/ai-provider", "DELETE");
}

export async function getRetentionSettings(): Promise<RetentionSettings> {
  const payload = await readJson(await request("/retention", "GET"));
  if (!isRetentionSettings(payload)) {
    throw new SettingsApiError("The retention settings response is invalid.");
  }
  return payload;
}

export async function patchRetentionSettings(update: RetentionSettings): Promise<void> {
  await request("/retention", "PATCH", update);
}
