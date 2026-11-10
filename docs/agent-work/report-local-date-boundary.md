# Dashboard report date boundary

## Task

Resolve the local Dashboard report's HTTP 400 when the selected range ends on the computer's current date shortly after local midnight.

## Diagnosis

The frontend forms date-only report bounds using the computer's local calendar. The backend compared the selected end date strictly with the current UTC date. At 00:25 on 2026-11-11 in the local Asia/Manila environment, UTC was still 2026-11-10, so a valid end date of 2026-11-11 was rejected as future. The UI reduced the backend's detailed 400 response to a generic rejected-request message.

## Change

The Dashboard now sends the browser's IANA timezone with date-only filters. The backend validates “today” in that timezone, queries from local midnight through the next local midnight, and displays stored manual-report dates in the same zone. Older clients that omit the timezone retain UTC behavior. Future dates remain rejected.

No Gmail body reads, model calls, OAuth operations, database migrations, or schema changes are part of this fix.

## Verification

- `mvn -o -B -f backend/pom.xml -Dtest=ReportPhase3AcceptanceTest test` passed: 5 tests, 0 failures/errors/skips. The synthetic case covers a first-link report for 2026-10-13 through 2026-11-11 in `Asia/Manila` while UTC is still 2026-11-10. It checks exact local-midnight query bounds, the saved report's displayed dates, matching Dashboard-read bounds, and that a 2026-11-12 end date is rejected before body reads or provider calls.
- `npm run build` passed. Vite emitted its existing warning that the main JS chunk exceeds 500 kB after minification.
- `git diff --check` passed.
- The first review passed the one-day compatibility fix but noted that unscoped UTC bounds could still shift local-day coverage. The implementation was expanded to pass the IANA timezone; the same reviewer re-reviewed the revised diff and found no blocking issues.
- The reviewer could not run the frontend build because `tsc` was unavailable in its environment. The primary run succeeded with the shared workspace dependencies.
- Merge and local service restart results will be added after integration.
