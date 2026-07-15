# ADR-0001: PulpPaperAdvisor ⊣ Pulp & Paper Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-1701` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-1701` publishes an OSS blueprint for pulp/paper
mill **plant operations coordination** (production-batch pulp/paper-
grade/volume/ISO-brightness data logging, digester/paper-machine/
effluent-treatment-plant maintenance scheduling, safety-concern
flagging, and outbound pulp/paper/paperboard shipment coordination).
Like every actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-1610` (Sawmilling and
planing of wood): both are back-office coordination actors for a fixed
processing PLANT (not a field site) with heavy equipment, a real
physical-safety dimension, and a central ground-truth **production
batch** entity independently gated alongside an **equipment** entity.
Pulp/paper differs in one structural respect that shapes this design:
1610's safety hazard is mechanical (saw-blade injury, kiln fire,
airborne wood dust); 1701's is chemical/environmental (pulping-liquor
chemical hazard, high-pressure/high-temperature paper-machine
mechanical hazard, and, distinctly, a regulated effluent-discharge
concern with real environmental impact -- kraft/sulfite pulping
produces process effluent that must be treated before release). This
actor's second PERMANENT governor block therefore targets effluent-
discharge AUTHORIZATION specifically (mirroring 1610's kiln-schedule-
finalize block structurally, but on a distinct domain concern), rather
than a generic "finalize" flag.

This vertical has NO pre-existing `kotoba-lang/pulppaper`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`pulppaper.registry` (equipment/batch verification, shipment-volume
recompute, pulp/paper-grade validation, ISO-brightness plausibility
validation) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-1610`'s `sawmilling.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:pulp-paper-plant-operations-governor`, is grep-verified UNIQUE
fleet-wide (`gh search code "pulp-paper" --owner cloud-itonami`, zero
hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external pulppaper capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
pulp/paper vertical has NO pre-existing capability library to wrap.
The equipment/batch-verification / shipment-volume / grade /
brightness validation functions live as pure functions in
`pulppaper.registry` and are re-verified independently by
`pulppaper.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-1610`'s `sawmilling.registry`).

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of pulp/paper mill
plant operations. It does NOT:
- Control digesters, paper machines, or effluent-treatment equipment directly
- Make plant-safety or hazard decisions (exclusive to the human plant supervisor)
- Authorize an effluent discharge (human plant supervisor/environmental compliance officer decides)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority --
it is a proposal-screening and documentation layer.

**CRITICAL SAFETY/ENVIRONMENTAL BOUNDARY**: pulp/paper manufacturing is
a safety-critical AND environmentally-regulated domain (chemical
pulping-liquor hazard, high-pressure/high-temperature paper-machine
mechanical hazard, effluent-discharge environmental impact). Safety-
concern flagging NEVER auto-commits. All safety concerns escalate
immediately to human review, and effluent-discharge authorization is
permanently blocked regardless of confidence or approval.

### Decision 3: Safety-concern escalation -- always human sign-off

`:flag-safety-concern` (chemical-liquor hazard, effluent-discharge
concern, paper-machine mechanical hazard, crew exposure) ALWAYS
escalates, never auto-commits. This is not a "low-stakes proposal" --
it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Mirroring `cloud-itonami-isic-1610`'s own structure, this vertical has
TWO entity kinds each gating a different op: `:schedule-maintenance`
independently verifies the referenced **equipment** unit's own
`:verified?`/`:registered?` fields; `:coordinate-shipment`
independently verifies the referenced **batch**'s own
`:verified?`/`:registered?` fields. Both are the same "plant/batch
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-shipment` additionally independently
recomputes whether a batch's own recorded shipped-to-date volume plus
the proposal's own claimed volume would exceed the batch's own
recorded production volume -- never taken on the advisor's
self-report.

### Decision 5: A distinct second PERMANENT block -- effluent-discharge authorization, not equipment finalization

`cloud-itonami-isic-1610`'s kiln-schedule-finalize block targets a
generic "finalize" flag on the maintenance proposal's own equipment-
control axis. This vertical's task brief calls for a SEPARATE,
domain-specific PERMANENT block: authorizing an effluent discharge
(`:discharge-authorize? true` on a `:schedule-maintenance` proposal)
is blocked unconditionally, independent of and in addition to the
generic closed-effect-allowlist block (`equipment-control-blocked`,
which catches any hallucinated direct-actuation effect). Two
independent HARD checks therefore jointly implement the task brief's
"any proposal touching pulping/paper-machine-equipment control OR
effluent-discharge-authorization is a hard, permanent block":
`equipment-control-blocked-violations` (the effect-allowlist check,
catches fabricated actuation effects) and
`discharge-authorize-blocked-violations` (the domain-specific flag
check, catches an authorize-shaped proposal that would otherwise pass
the effect-allowlist check because it is still nominally
`:maintenance/schedule`).

### Decision 6: HARD invariants (no override)

Four HARD governor invariants (elaborated into ten concrete checks in
`pulppaper.governor`, mirroring `cloud-itonami-isic-1610`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's volume must independently recompute within the batch's own logged production volume
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct digester/paper-machine/effluent-treatment-equipment control or effluent-discharge authorization is permanently blocked
4. The op allowlist is closed -- `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Pulp/paper mill plant operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into ten concrete governor checks) protect against scope creep into
unauthorized equipment operation or effluent-discharge authorization.
Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical AND environmental-compliance discipline is
explicit: safety-concern flagging cannot be rate-limited, suppressed,
or auto-decided by phase gate; effluent-discharge authorization is
blocked by two independent layers (the closed-effect allowlist and the
domain-specific flag check).

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation and effluent-discharge decisions
remain human-controlled via external channels.

(-) No integration with real mill-management databases (equipment
telemetry, batch tracking, freight dispatch, environmental-compliance
reporting systems) -- this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-1701`: `clojure -M:test` green (71 tests / 200
  assertions / 0 failures / 0 errors, run from an independent fresh
  clone at the merge commit -- see the superproject ADR and
  `kotoba-lang/industry` registry entry for the exact re-verification
  output), `clojure -M:lint` clean, `clojure -M:dev:run` demo narrative
  exercises proposal submission, escalation, and every HARD-hold
  scenario directly (not-propose-effect, unknown-op,
  equipment-not-verified, batch-not-verified,
  shipment-volume-exceeded, discharge-authorize-blocked,
  already-scheduled, invalid-grade, invalid-brightness).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
