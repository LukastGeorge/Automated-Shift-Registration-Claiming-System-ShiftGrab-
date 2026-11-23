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

### Running the backend quality gate locally

`mvn verify` needs JDK 21 and a running Docker daemon (Testcontainers starts PostgreSQL and Keycloak). It fails if JaCoCo coverage is below **70% lines / 60% branches**; the HTML report is at `backend/target/site/jacoco/index.html`. CI publishes the same numbers in the job summary and the report as the `jacoco-report` artifact.

### Running the frontend quality gate locally

From `frontend/` with Node.js 24: `npm ci`, `npm run lint`, `npx ng test --watch=false --coverage`, `npm run build`.
The test step fails below **70% lines, statements and functions / 60% branches** (thresholds in `angular.json`); the HTML report is under `frontend/coverage/`. CI (`ci-frontend.yml`) runs the same steps and publishes the numbers in the job summary and the report as the `frontend-coverage` artifact.

### Security checks and branch protection

`security.yml` runs on every PR, on pushes to `main`/`develop` and weekly: **CodeQL** (security-extended queries) for Java and TypeScript, and **Trivy** on the Dockerfiles and the built images. A **fixable CRITICAL** vulnerability fails the build; HIGH ones are reported in the repository's Security tab. Fix by updating the base image or the dependency, never by silencing the scanner.

`main` and `develop` require a pull request and the three security checks (`codeql (java-kotlin)`, `codeql (javascript-typescript)`, `trivy (Dockerfiles and images)`). The backend and frontend CI checks are not marked *required* because they only run when their folder changes (path filters), and a required check that never runs would block unrelated PRs; they must still be green before merging.

## Versioning

Semantic Versioning. Release notes are kept in `CHANGELOG.md`.
