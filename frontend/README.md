# EndpointGuard frontend

React + Vite UI for the existing EndpointGuard API.

## Local development

```powershell
npm install
npm run dev
```

Vite proxies `/api` and `/actuator` to `http://localhost:8080`. Set `VITE_API_BASE_URL` only if the API is hosted elsewhere.

## Docker Compose

The Compose frontend is served by Nginx on port 5173 and proxies API requests to the backend service. Sign in with an account already registered through the backend.

## Current API coverage

The UI uses the backend's login, projects, repositories, endpoints, endpoint metrics, and risk evaluation routes. Pull Request webhooks are ingested by the backend, but it does not currently expose Pull Request, monitoring, risk-rule, or audit-log read APIs; those areas are disclosed as unavailable rather than populated with demo data.
