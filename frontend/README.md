# Virtual Investment Portfolio frontend

React 19 and Vite investor/admin interface for the existing Spring Boot backend.

See [VERIFICATION.md](VERIFICATION.md) for prerequisites, environment configuration, exact run commands, automated checks, and the full manual verification checklist.

```powershell
npm.cmd ci
Copy-Item .env.example .env.local
npm.cmd run dev -- --host localhost --port 5173 --strictPort
```

`VITE_API_BASE_URL` defaults to `http://localhost:8081`. The backend must be running and currently allows the frontend origin `http://localhost:5173`.

```powershell
npm.cmd run build
npm.cmd run lint
npm.cmd test
```

The frontend contains no credentials. Vite environment variables are public configuration. Notification entries are live events held only in the current browser session.
