# Contributing to ShiftGrab

## Branching model (Git Flow)

| Branch | Purpose | Merges into |
|---|---|---|
| `main` | Production. Every commit is deployable and tagged `vX.Y.Z`. | — |
| `develop` | Integration branch for the next release. | `release/*` |
| `feature/SG-XX-short-name` | One user story / task. | `develop` (via PR) |
| `release/X.Y` | Stabilisation before a release. | `main` + `develop` |
| `hotfix/X.Y.Z` | Urgent production fix. | `main` + `develop` |

Direct pushes to `main` and `develop` are not allowed; every change goes through a Pull Request with green CI.

## Commit messages (Conventional Commits)

```
<type>(<scope>): <summary in imperative mood>

[optional body]

Refs: SG-XX
```

Types: `feat`, `fix`, `refactor`, `test`, `docs`, `ci`, `chore`, `perf`.
Scopes: module names (`shift`, `registration`, `identity`, ...), `frontend`, `infra`, `docs`.

## Definition of Done

A story is done when:
1. Acceptance criteria are met and covered by automated tests.
2. `mvn verify` (backend) / `npm test` (frontend) pass and coverage did not drop.
3. CI is green on the PR.
4. API docs, ADRs and user docs are updated where relevant.
5. The PR is reviewed and merged into `develop`.

## Versioning

Semantic Versioning. Release notes are kept in `CHANGELOG.md`.
