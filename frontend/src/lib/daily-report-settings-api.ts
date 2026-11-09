import { getCsrfHeaders } from "./csrf";

export type DailyReportSettings = {
  enabled: boolean;
  runAtLocal: string;
  timezoneId: string;
  providerDisclosureRequired: boolean;
};

export type DailyReportSettingsUpdate = {
  enabled: boolean;
  runAtLocal: string;
  timezoneId: string;
  providerDisclosureConfirmed: boolean;
  providerConfigurationVersion: string | null;
};

export class DailyReportSettingsApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "DailyReportSettingsApiError";
  }
}

const settingsPath = "/api/v1/reports/daily/settings";
const requestTimeoutMs = 12_000;

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function normalizedTime(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const match = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  const seconds = Number(match[3] ?? "0");
  if (hours > 23 || minutes > 59 || seconds > 59) return null;
  return `${match[1]}:${match[2]}:${String(seconds).padStart(2, "0")}`;
}

function parseDailySettings(value: unknown): DailyReportSettings | null {
  const candidate = object(value);
  const runAtLocal = normalizedTime(candidate?.runAtLocal);
  if (
    !candidate || typeof candidate.enabled !== "boolean" || !runAtLocal ||
    typeof candidate.timezoneId !== "string" || !candidate.timezoneId.trim() ||
    typeof candidate.providerDisclosureRequired !== "boolean"
  ) return null;
  return {
    enabled: candidate.enabled,
    runAtLocal,
    timezoneId: candidate.timezoneId.trim(),
    providerDisclosureRequired: candidate.providerDisclosureRequired,
  };
}

async function request(method: "GET" | "PUT", update?: DailyReportSettingsUpdate): Promise<DailyReportSettings> {
  const controller = new AbortController();
  const timeoutId = window.setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const headers: Record<string, string> = {};
    if (update) headers["Content-Type"] = "application/json";
    if (method === "PUT") Object.assign(headers, getCsrfHeaders());
    const response = await fetch(settingsPath, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers: Object.keys(headers).length > 0 ? headers : undefined,
      body: update ? JSON.stringify(update) : undefined,
    });
    if (!response.ok) {
      if (response.status === 409) {
        let errorCode: unknown = null;
        try {
          errorCode = object(await response.json())?.errorCode;
        } catch {
          // Use the generic conflict message for unrecognized responses.
        }
        if (errorCode === "PROVIDER_DISCLOSURE_CHANGED") {
          throw new DailyReportSettingsApiError("The AI provider changed. Review its provider, model, and destination, then confirm the disclosure again.");
        }
      }
      if (response.status === 404 || response.status >= 500) {
        throw new DailyReportSettingsApiError("Daily report settings are unavailable. Try again when East is running.");
      }
      throw new DailyReportSettingsApiError(`The daily report settings request was rejected (HTTP ${response.status}). Review the schedule and try again.`);
    }
    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      throw new DailyReportSettingsApiError("The daily report service returned an invalid response.");
    }
    const parsed = parseDailySettings(payload);
    if (!parsed) throw new DailyReportSettingsApiError("The daily report settings response is invalid.");
    return parsed;
  } catch (error) {
    if (error instanceof DailyReportSettingsApiError) throw error;
    if (controller.signal.aborted) throw new DailyReportSettingsApiError("Daily report settings took too long to load. Try again.");
    throw new DailyReportSettingsApiError("Daily report settings could not reach East. Check that the local service is running.");
  } finally {
    window.clearTimeout(timeoutId);
  }
}

export function getDailyReportSettings(): Promise<DailyReportSettings> {
  return request("GET");
}

export function putDailyReportSettings(update: DailyReportSettingsUpdate): Promise<DailyReportSettings> {
  return request("PUT", update);
}
