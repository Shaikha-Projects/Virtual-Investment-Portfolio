# Frontend ADMIN / USER separation

## Behavior

The existing `GET /auth/me` response remains the authority for identity and role. No email address, token claim, URL, or locally stored role determines access to the workspace.

| Role | Default after login/refresh | Available pages | Investment notifications |
| --- | --- | --- | --- |
| ADMIN | Admin Dashboard | Admin Dashboard, Asset Management, User Management, Profile | Not subscribed |
| USER | Investment Dashboard | Dashboard, Assets, My Holdings, Transactions, Watchlist, Profile | Existing live SSE connection |

The current backend emits only investment purchase/sale notifications. No admin notification history or administrative event feed is available, so the ADMIN workspace does not mount the investment notification component.

Navigation uses explicit role allowlists. Navigation requests outside the role are ignored, and page rendering independently resolves invalid or cross-role section state to that role's own dashboard. Admin rendering has no fallback to the investor dashboard. Unsupported/missing roles cannot enter either workspace and receive a friendly error with retry/sign-in options.

The existing JWT storage, validation, API handling, session restoration, and logout behavior are retained. Identity validation occurs before a newly issued token is persisted. The workspace is keyed by token, identity, and role so that an identity/role replacement remounts it. Logout still aborts account requests and removes the entire workspace, including page state and USER SSE notifications. Existing generation checks prevent late responses from updating a subsequent account.

The dark styling, responsive drawer, forms, investor calculations, trading locks, pagination/sorting, admin asset/user actions, and shared profile functionality are retained.

## Complete file list for this task

Modified:

- `frontend/src/App.jsx` — identity/role-scoped workspace key and friendly unavailable-access heading.
- `frontend/src/components/PortfolioShell.jsx` — separate navigation and rendering maps, navigation guard, appropriate workspace labels, USER-only investment notifications.
- `frontend/src/hooks/useSession.js` — validate the `/auth/me` identity before using it; fail safely when role information is unavailable.

Created:

- `frontend/src/auth/roles.js` — role allowlists, default sections, safe section resolution, and identity validation.
- `frontend/tests/roles.test.mjs` — role defaults, cross-role rejection, unknown roles, and email-independent identity tests.
- `frontend/tests/role-browser-smoke.mjs` — optional headless browser verification with mocked backend responses and no additional dependencies.
- `frontend/ROLE_SEPARATION.md` — this report and verification instructions.

All task changes are inside `frontend/`. Pre-existing backend edits from earlier tasks remain untouched. Backend source/config/test files were compared against their task-start contents, not against a clean Git checkout.

## Validation

- Frontend production build: passed.
- ESLint: passed.
- Node frontend tests: all 16 passed (11 existing, 5 new role tests).
- Headless Chrome smoke test: passed against the actual React UI with a mocked fetch backend. This was not a live Spring/PostgreSQL integration test.

Browser coverage:

1. ADMIN login and refresh land on Admin Dashboard; exactly four sidebar destinations.
2. USER login and refresh land on Dashboard; exactly six sidebar destinations.
3. Both roles access Profile.
4. Forced cross-role React section state renders only the role's own dashboard.
5. ADMIN makes no investor data requests and opens no SSE stream.
6. ADMIN asset editing, user status changes, and self-deactivation control remain operational.
7. USER fractional buying, holdings, transaction pagination/sorting, watchlist, and SSE display remain operational.
8. Switching ADMIN → USER → ADMIN clears the previous role's page and notification state. A delayed old profile response cannot enter the new account.
9. Identity service failure blocks workspace access, retains a retryable session, and can recover. Missing/unsupported roles display a safe error; failed role validation at login does not persist a token.
10. Mobile navigation/profile render without page-level horizontal overflow. No uncaught browser runtime exceptions were observed.

## Run the checks

From `frontend/`:

```powershell
npm.cmd run build
npm.cmd run lint
npm.cmd test
```

For the optional mocked browser smoke test, start a dedicated Vite server and disposable Chrome profile. Use a separate terminal for each long-running command:

```powershell
npm.cmd run dev -- --host localhost --port 5176 --strictPort
```

From `frontend/`, start Chrome using a fresh profile contained within the ignored dependency cache:

```powershell
$roleProfile = Join-Path (Get-Location) 'node_modules\.cache\role-separation-chrome'
Start-Process -FilePath 'C:\Program Files\Google\Chrome\Application\chrome.exe' -WindowStyle Hidden -ArgumentList @('--headless=new', '--disable-gpu', '--disable-extensions', '--no-first-run', '--remote-debugging-port=9228', "--user-data-dir=$roleProfile", 'about:blank')
node tests/role-browser-smoke.mjs
```

The script uses Node's built-in WebSocket implementation. `SMOKE_APP_URL` and `SMOKE_DEBUG_URL` can override the default Vite and debugging origins. Only use a disposable browser profile: the test clears storage for its test origin. Stop the dedicated browser/server after checking.

For live verification, log in with existing ADMIN and USER accounts, check each sidebar and profile, refresh from Profile to confirm the correct default dashboard, log out and switch roles in both directions, and confirm trade/admin actions against your existing backend. No backend credentials or schema changes are needed for this frontend task.

## Existing backend permission limitations

This is frontend separation, not a replacement for server-side authorization. Existing admin endpoints retain their server-side ADMIN checks. The backend's authenticated investor endpoints may still accept ADMIN tokens; hiding/guarding the investor UI does not revoke those API permissions. Tightening that policy would require a separately authorized backend change, which was intentionally not made. The existing SSE service remains live-only and supports one stream per account.

No backend files, backend tests, database/schema, JWT configuration, or API contracts were changed by this task. No commits or pushes were performed.
