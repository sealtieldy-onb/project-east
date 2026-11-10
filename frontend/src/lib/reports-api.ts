import { getCsrfHeaders } from "./csrf";

export type ReportInsightKind = "URGENT_ACTION" | "AWAITING_REPLY" | "REPLY_RECEIVED" | "TRANSACTION";
export type ReportInsightStatus = "OPEN" | "DISMISSED" | "HANDLED";
export type ReportCoverageStatus = "COMPLETE" | "PARTIAL" | "UNKNOWN";

export type ReportSourceReference = {
  mailboxId: string;
  threadId: string;
  messageId: string;
  mailboxEmail: string | null;
  mailboxName: string | null;
  sentAt: string | null;
};

export type ReportTransactionDetails = {
  merchant: string | null;
  type: string | null;
  amount: number | null;
  currency: string | null;
  date: string | null;
};

export type ReportInsight = {
  id: string;
  kind: ReportInsightKind;
  confidence: number;
  text: string;
  status: ReportInsightStatus | null;
  urgency: string | null;
  deadline: string | null;
  transaction: ReportTransactionDetails | null;
  sources: ReportSourceReference[];
};

export type DashboardReportSnapshot = {
  resultType: "SNAPSHOT";
  aiConfigured: boolean;
  firstReportRecommended: boolean;
  periodStart: string;
  periodEnd: string;
  coverage: {
    status: ReportCoverageStatus;
    eligibleMessageCount: number;
    selectedMessageCount: number;
    additionalMessageCount: number;
  };
  summary: {
    id: string;
    status: string;
    generatedAt: string | null;
    narrative: string;
  } | null;
  insights: ReportInsight[];
};

export type ReportExpansionRequired = {
  resultType: "EXPANSION_REQUIRED";
  status: "EXPANSION_REQUIRED";
  initialLimit: number;
  additionalMessageCount: number;
  maxAdditionalMessageCount: number;
};

export type ReportDisclosureRequired = {
  resultType: "DISCLOSURE_REQUIRED";
  status: "DISCLOSURE_REQUIRED";
  initialLimit: number;
  eligibleMessageCount: number;
  additionalMessageCount: number;
};

export type ReportGenerationResult = DashboardReportSnapshot | ReportExpansionRequired | ReportDisclosureRequired;

export type ReportDashboardQuery = {
  periodStart: string;
  periodEnd: string;
  timezoneId: string;
  mailboxId?: string;
};

export type ManualReportRequest = Pick<ReportDashboardQuery, "periodStart" | "periodEnd" | "timezoneId"> & {
  mailboxIds?: string[];
  providerDisclosureConfirmed: boolean;
  providerConfigurationVersion: string;
  expectedEligibleMessageCount?: number;
  expectedAdditionalMessageCount?: number;
  expandedReadDecision?: "ALLOW" | "DECLINE";
};

export class ReportsApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ReportsApiError";
  }
}

const dashboardPath = "/api/v1/dashboard";
const requestTimeoutMs = 12_000;
const generationTimeoutMs = 120_000;
const insightKinds = new Set<ReportInsightKind>(["URGENT_ACTION", "AWAITING_REPLY", "REPLY_RECEIVED", "TRANSACTION"]);
const insightStatuses = new Set<ReportInsightStatus>(["OPEN", "DISMISSED", "HANDLED"]);

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function text(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function nullableText(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function nonNegativeInteger(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : fallback;
}

function parseSource(value: unknown): ReportSourceReference | null {
  const candidate = object(value);
  const mailboxId = text(candidate?.mailboxId);
  const threadId = text(candidate?.threadId);
  const messageId = text(candidate?.messageId);
  if (!candidate || !mailboxId || !threadId || !messageId) return null;
  return {
    mailboxId,
    threadId,
    messageId,
    mailboxEmail: nullableText(candidate.mailboxEmail ?? candidate.accountEmail),
    mailboxName: nullableText(candidate.mailboxName),
    sentAt: nullableText(candidate.sentAt),
  };
}

function parseTransaction(value: unknown, insight: Record<string, unknown>): ReportTransactionDetails | null {
  const nested = object(value);
  const candidate = nested ?? insight;
  const hasTransactionField = ["merchant", "payee", "type", "transactionType", "amount", "currency", "date", "transactionDate"]
    .some((key) => candidate[key] !== null && candidate[key] !== undefined);
  if (!hasTransactionField) return null;
  const amount = candidate.amount;
  return {
    merchant: nullableText(candidate.merchant ?? candidate.payee),
    type: nullableText(candidate.type ?? candidate.transactionType),
    amount: typeof amount === "number" && Number.isFinite(amount) ? amount : null,
    currency: nullableText(candidate.currency),
    date: nullableText(candidate.date ?? candidate.transactionDate),
  };
}

function parseInsight(value: unknown): ReportInsight | null {
  const candidate = object(value);
  const kind = candidate?.kind;
  const confidence = candidate?.confidence;
  const displayText = text(candidate?.displayText ?? candidate?.text);
  if (
    !candidate || typeof candidate.id !== "string" || !candidate.id.trim() ||
    typeof kind !== "string" || !insightKinds.has(kind as ReportInsightKind) ||
    typeof confidence !== "number" || !Number.isFinite(confidence) || confidence < 0 || confidence > 1 ||
    !displayText || !Array.isArray(candidate.sources)
  ) return null;
  const sources = candidate.sources.map(parseSource);
  if (sources.length === 0 || sources.some((source) => source === null)) return null;
  const status = typeof candidate.status === "string" && insightStatuses.has(candidate.status as ReportInsightStatus)
    ? candidate.status as ReportInsightStatus
    : null;
  return {
    id: candidate.id,
    kind: kind as ReportInsightKind,
    confidence,
    text: displayText,
    status,
    urgency: nullableText(candidate.urgency),
    deadline: nullableText(candidate.deadline ?? candidate.dueAt),
    transaction: parseTransaction(candidate.transaction, candidate),
    sources: sources as ReportSourceReference[],
  };
}

function parseCoverage(value: unknown, root: Record<string, unknown>): DashboardReportSnapshot["coverage"] | null {
  const candidate = object(value);
  const rawStatus = typeof value === "string" ? value : candidate?.status;
  if (rawStatus !== "COMPLETE" && rawStatus !== "PARTIAL" && rawStatus !== "UNKNOWN") return null;
  return {
    status: rawStatus,
    eligibleMessageCount: nonNegativeInteger(candidate?.eligibleMessageCount ?? root.eligibleMessageCount),
    selectedMessageCount: nonNegativeInteger(candidate?.selectedMessageCount ?? root.selectedMessageCount),
    additionalMessageCount: nonNegativeInteger(candidate?.additionalMessageCount ?? root.additionalMessageCount),
  };
}

function parseSnapshot(payload: unknown, query: ReportDashboardQuery, allowMissingAiConfigured = false): DashboardReportSnapshot | null {
  const outer = object(payload);
  const candidate = object(outer?.dashboard) ?? outer;
  if (!candidate) return null;
  const coverage = parseCoverage(candidate.coverage, candidate);
  const periodStart = text(candidate.periodStart) ?? query.periodStart;
  const periodEnd = text(candidate.periodEnd) ?? query.periodEnd;
  const rawSummary = candidate.summary;
  let summary: DashboardReportSnapshot["summary"] = null;
  if (rawSummary !== null && rawSummary !== undefined) {
    const item = object(rawSummary);
    const narrative = text(item?.narrative);
    if (!item || !narrative) return null;
    summary = {
      id: text(item.id) ?? "latest",
      status: text(item.status) ?? coverage?.status ?? "COMPLETED",
      generatedAt: nullableText(item.generatedAt),
      narrative,
    };
  }
  const rawInsights = candidate.insights ?? [];
  const aiConfigured = typeof candidate.aiConfigured === "boolean"
    ? candidate.aiConfigured
    : allowMissingAiConfigured ? true : null;
  const firstReportRecommended = typeof candidate.firstReportRecommended === "boolean"
    ? candidate.firstReportRecommended
    : allowMissingAiConfigured ? false : null;
  if (!coverage || aiConfigured === null || firstReportRecommended === null || !Array.isArray(rawInsights)) return null;
  const insights = rawInsights.map(parseInsight);
  if (insights.some((insight) => insight === null)) return null;
  return {
    resultType: "SNAPSHOT",
    aiConfigured,
    firstReportRecommended,
    periodStart,
    periodEnd,
    coverage,
    summary,
    insights: insights as ReportInsight[],
  };
}

function parseGenerationResult(payload: unknown, query: ReportDashboardQuery): ReportGenerationResult | null {
  const candidate = object(payload);
  if (candidate?.status === "DISCLOSURE_REQUIRED") {
    const coverage = parseCoverage(candidate.coverage, candidate);
    const initialLimit = nonNegativeInteger(candidate.initialLimit, -1);
    if (!coverage || initialLimit < 1) return null;
    return {
      resultType: "DISCLOSURE_REQUIRED",
      status: "DISCLOSURE_REQUIRED",
      initialLimit,
      eligibleMessageCount: coverage.eligibleMessageCount,
      additionalMessageCount: coverage.additionalMessageCount,
    };
  }
  if (candidate?.status === "EXPANSION_REQUIRED") {
    const coverage = parseCoverage(candidate.coverage, candidate);
    const additionalMessageCount = nonNegativeInteger(
      candidate.additionalMessageCount ?? coverage?.additionalMessageCount,
      -1,
    );
    const initialLimit = nonNegativeInteger(candidate.initialLimit, -1);
    const maxAdditionalMessageCount = nonNegativeInteger(candidate.maxAdditionalMessageCount, -1);
    if (!coverage || additionalMessageCount < 1 || initialLimit < 1 || maxAdditionalMessageCount < 1 || maxAdditionalMessageCount > additionalMessageCount) return null;
    return { resultType: "EXPANSION_REQUIRED", status: "EXPANSION_REQUIRED", initialLimit, additionalMessageCount, maxAdditionalMessageCount };
  }
  if (candidate?.status === "COMPLETED") return parseSnapshot(payload, query, true);
  return parseSnapshot(payload, query, true);
}

async function request(path: string, method: "GET" | "POST", body?: ManualReportRequest, signal?: AbortSignal): Promise<unknown> {
  const controller = new AbortController();
  const abortFromCaller = () => controller.abort();
  if (signal?.aborted) controller.abort();
  else signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeoutId = window.setTimeout(
    () => controller.abort(),
    method === "POST" ? generationTimeoutMs : requestTimeoutMs,
  );
  try {
    const headers: Record<string, string> = {};
    if (body) headers["Content-Type"] = "application/json";
    if (method === "POST") Object.assign(headers, getCsrfHeaders());
    const response = await fetch(path, {
      method,
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
      headers: Object.keys(headers).length ? headers : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) {
      if (response.status === 409) {
        const error = object(await response.json().catch(() => null));
        if (error?.errorCode === "PROVIDER_DISCLOSURE_CHANGED" || error?.errorCode === "DISCLOSURE_CHANGED") {
          throw new ReportsApiError("The AI provider changed. Review its provider, model, and destination, then confirm the disclosure again.");
        }
      }
      if (response.status === 401 || response.status === 403) {
        throw new ReportsApiError("East needs an active local sign-in to load reports. Reload East and try again.");
      }
      if (response.status === 404 || response.status >= 500) {
        throw new ReportsApiError("The local reports service is unavailable. Try again when East is running.");
      }
      throw new ReportsApiError(`The report request was rejected (HTTP ${response.status}). Review the selected dates and account scope, then try again.`);
    }
    try {
      return await response.json();
    } catch {
      throw new ReportsApiError("The local reports service returned an invalid response.");
    }
  } catch (error) {
    if (error instanceof ReportsApiError) throw error;
    if (controller.signal.aborted) {
      throw new ReportsApiError(method === "POST" ? "Report generation took too long. Try again." : "Loading the report timed out. Try again.");
    }
    throw new ReportsApiError("The local reports service could not be reached. Check that East is running and try again.");
  } finally {
    signal?.removeEventListener("abort", abortFromCaller);
    window.clearTimeout(timeoutId);
  }
}

function dashboardUrl(query: ReportDashboardQuery): string {
  const params = new URLSearchParams({
    periodStart: query.periodStart,
    periodEnd: query.periodEnd,
    timezoneId: query.timezoneId || "UTC",
  });
  if (query.mailboxId) params.set("mailboxId", query.mailboxId);
  return `${dashboardPath}?${params.toString()}`;
}

export async function getDashboardReport(query: ReportDashboardQuery, signal?: AbortSignal): Promise<DashboardReportSnapshot> {
  const payload = await request(dashboardUrl(query), "GET", undefined, signal);
  const snapshot = parseSnapshot(payload, query);
  if (!snapshot) throw new ReportsApiError("The dashboard report response is invalid.");
  return snapshot;
}

export async function generateManualReport(requestBody: ManualReportRequest): Promise<ReportGenerationResult> {
  const payload = await request(`${dashboardPath}/summaries`, "POST", requestBody);
  const result = parseGenerationResult(payload, requestBody);
  if (!result) throw new ReportsApiError("Report generation returned an invalid result.");
  return result;
}
