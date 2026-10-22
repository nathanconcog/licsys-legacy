# LICSYS Next infra (AWS CDK v2, TypeScript)

Describes the production topology from `modern/ARCHITECTURE.md`. Never deployed from this repo; synth only.

```bash
npm ci
npm test          # Jest assertions + snapshot
npx cdk synth     # no AWS credentials needed (account-agnostic, fixed AZs)
npm run diagram   # regenerates architecture.md from lib/config.ts
```

See [architecture.md](architecture.md) for the diagram and the LICNIGHT JCL step -> AWS mapping.
