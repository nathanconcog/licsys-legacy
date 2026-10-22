# LICSYS Next · web UI

The browser front end for LICSYS Next, the event-driven replacement for the legacy COBOL nightly batch.
It is built with React 18, Vite 5, TypeScript, Tailwind CSS and Framer Motion.

> Fictional demo system. All people, plates and rules are invented. It is not a real government service.

## Pages

| Route | What it shows |
| --- | --- |
| `/drivers` (default), `/drivers/:id` | **Driver 360** for one driver. It has a licence card with a tenure progress bar, a points gauge against the class limit, an offence timeline, notices and a *Record offence* form. Each decision has a **Why?** popover with the `rule` and `reason` from the API. Search by number or name, or press <kbd>⌘K</kbd>. |
| `/activity` | **Live activity**. Domain events from the SSE stream at `/notification/api/activity/stream`, colour-coded by bounded context. Each event shows source → `licsys-bus` → queue → consumer, and you can expand its JSON. |
| `/architecture` | **Architecture**. A context map of API Gateway, the four services (with live `actuator/health` dots and their DynamoDB tables), EventBridge and SQS. Events animate across the map as they arrive. A side panel maps each legacy COBOL program or paragraph to the service that replaces it. |
| `/parity` | **Parity**. Reads `/parity.json` and shows the headline (13 drivers · 8 identical · 5 policy fixes · 0 unexpected), the five fixes with their explanations, the full driver table and the renewal totals. |
| `/renewals` | **Registration quote**. Posts to `/registration/api/renewals/quote` and shows a line-item breakdown tagged with BR-06 clauses. You can then submit the renewal. |

The top bar has **Reset demo**, which calls `POST /api/admin/reset` on each of the four services.

## Running locally

```bash
cd modern/ui
npm install
npm run dev:mock     # mock mode: no services needed
npm run dev          # real mode: proxies to the services on localhost
```

Then open http://localhost:5173.

The dev server (and `npm run preview`) proxies these paths and strips the prefix:

| Path | Target | Override |
| --- | --- | --- |
| `/licence/*` | `http://localhost:8081` | `LICENCE_URL` |
| `/demerit/*` | `http://localhost:8082` | `DEMERIT_URL` |
| `/registration/*` | `http://localhost:8083` | `REGISTRATION_URL` |
| `/notification/*` | `http://localhost:8084` | `NOTIFICATION_URL` |

For example, `GET /demerit/api/drivers/D0000009/demerits` goes to `http://localhost:8082/api/drivers/D0000009/demerits`.

## Mock mode

Set `VITE_MOCK=1`, or use the **Mock data** switch in the sidebar (it is saved in `localStorage` as `licsys.mock`). The UI then talks to an in-browser model of the four service contracts in `modern/ARCHITECTURE.md` and makes no network calls apart from `/parity.json`.

- On load and on every reset, the model replays the shipped legacy files (`data/DRVMAST.dat`, `data/OFFENCES.dat`, `data/VEHICLES.dat`, run date 2026-10-20). It follows `policy/BUSINESS-RULES.md`, not the COBOL. For example, Isla Morgan (D0000009) ends on 10 points with no notice, Maya Rossi (D0000013) on 5, Jack Robinson (D0000010) on 11, and Henry Walker (D0000008) on 5 with a suspension notice. Daniel, Emma and Liam stay on P2, Alice moves from P1 to P2, and Chloe and Grace move from P2 to Open.
- Each change publishes the same domain events the real services would (`DriverRegistered`, `OffenceRecorded`, `DemeritPointsChanged`, `SuspensionThresholdReached`, `LicenceProgressed`, `RegistrationRenewed`, `NoticeIssued`). They are routed through the same `licsys-bus` → SQS rules.
- **Simulate traffic** publishes a random registration renewal every few seconds, so Live activity and Architecture keep moving during a demo.

The mock code is in `src/mock/`. The rules are in `src/mock/rules.ts`, and `src/mock/server.ts` handles routing and events.

## Docker

```bash
docker build -t licsys-next-ui modern/ui                         # real mode
docker build -t licsys-next-ui --build-arg VITE_MOCK=1 modern/ui # mock mode baked in
docker run -p 5173:5173 licsys-next-ui
```

The image is nginx serving the built app on port 5173, using the same prefix-stripping routes as the dev proxy. By default it proxies to `licence-service:8081`, `demerit-service:8082`, `registration-service:8083` and `notification-service:8084`, which are the hostnames in `modern/docker-compose.yml`. You can override them with `LICENCE_UPSTREAM`, `DEMERIT_UPSTREAM`, `REGISTRATION_UPSTREAM` and `NOTIFICATION_UPSTREAM`. Upstreams are resolved per request, so the UI starts even if a service is down. The notification route has buffering turned off so SSE works.

## Scripts

| Script | |
| --- | --- |
| `npm run dev` / `npm run dev:mock` | Vite dev server on :5173 |
| `npm run build` | Type-check (`tsc -b`) and production build to `dist/` |
| `npm run lint` | ESLint |
| `npm run preview` | Serve `dist/` on :5173 with the same proxy |

## Layout

```
src/
  App.tsx              routes
  components/          layout shell, command palette, gauge, record-offence form, UI primitives
  pages/               Driver, Activity, Architecture, Parity, Registration
  lib/api.ts           typed client for the four services (real fetch/SSE or mock)
  lib/live.tsx         activity stream + health polling context
  lib/domain.ts        classes, limits, offence codes, EventBridge routing, legacy → service map
  mock/                in-browser implementation of the service contracts
public/parity.json     sample parity report (schema below)
```

`parity.json` has the shape `{generatedAt, summary:{drivers, matched, expectedDifferences, unexpected, renewals, renewalMatched}, drivers:[{driverId, name, legacy:{licenceClass, points, suspensionNotice}, modern:{…}, status, rule, reason}], renewals:[{plate, legacyTotal, modernTotal, match}]}`. `status` is one of `MATCH`, `EXPECTED_DIFFERENCE` or `MISMATCH`. A parity job can overwrite the file in `dist/` (or mount it into the container at `/usr/share/nginx/html/parity.json`).
