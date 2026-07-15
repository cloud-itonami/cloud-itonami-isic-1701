# cloud-itonami-isic-1701: Manufacture of pulp, paper and paperboard

Open Business Blueprint for **ISIC Rev.5 1701**: manufacture of pulp, paper and paperboard — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office pulp/paper **mill plant operations**: production-batch data logging (pulp/paper grade/volume/ISO-brightness), digester/paper-machine/effluent-treatment-plant maintenance scheduling, safety-concern flagging, and outbound pulp/paper/paperboard shipment coordination.

This repository designs a forkable OSS business for pulp/paper mill plant
operations: run by a qualified operator so a mill keeps its own
operating records instead of renting a closed SaaS.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — pulp/paper-grade/volume/ISO-brightness data logging (administrative, not an operational decision)
- `:schedule-maintenance` — digester/paper-machine/effluent-treatment-plant maintenance scheduling proposal
- `:flag-safety-concern` — surface a chemical-hazard/effluent-discharge/equipment safety concern (always escalates)
- `:coordinate-shipment` — outbound pulp/paper/paperboard shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical and
environmentally-regulated domain** (chemical/mechanical pulping
digesters, high-pressure/high-temperature paper machines, effluent
discharge with real environmental impact):

- Does NOT control digesters, paper machines, or effluent-treatment equipment directly
- Does NOT make plant-safety or hazard decisions (that's the plant supervisor's exclusive human authority)
- Does NOT authorize an effluent discharge (human plant supervisor / environmental compliance officer decides)
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`pulppaper.operation/build`, a langgraph-clj StateGraph):
1. **`pulppaper.advisor`** (sealed intelligence node, `PulpPaperAdvisor`): proposes decisions only, never commits
2. **`pulppaper.governor`** (independent, `Pulp & Paper Plant Operations Governor`): validates against domain rules, re-derived from `pulppaper.registry`'s pure functions and `pulppaper.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct pulping/paper-machine-equipment control)
     - Authorizing an effluent discharge (`:discharge-authorize? true`) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped volume past its own logged production volume (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:grade` value on a production-batch patch
     - No physically implausible `:brightness-percent` (ISO brightness) value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`pulppaper.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`pulppaper.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
