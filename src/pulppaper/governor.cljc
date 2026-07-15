(ns pulppaper.governor
  "Pulp & Paper Plant Operations Governor -- the independent compliance
  layer that earns the PulpPaperAdvisor the right to commit. The
  advisor has no notion of whether a piece of equipment it wants to
  schedule maintenance against has actually been inspected/registered,
  whether a batch it wants to coordinate a shipment against has
  actually been QC-verified/registered, whether a maintenance proposal
  secretly tries to AUTHORIZE an effluent discharge (rather than
  merely draft-schedule a maintenance window), whether a shipment
  proposal's own claimed volume would blow through the batch's own
  logged production volume, or when an act stops being a coordination
  proposal and becomes direct pulping/paper-machine-equipment control,
  so this MUST be a separate system able to *reject* a proposal and
  fall back to HOLD.

  `:itonami.blueprint/governor` is `:pulp-paper-plant-operations-
  governor` (see docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:digester/actuate`
                                       or `:paper-machine/actuate`) is
                                       the 'direct pulping/paper-
                                       machine-equipment control' scope
                                       violation this actor must NEVER
                                       perform -- HARD, PERMANENT,
                                       unconditional.
    4. Effluent-discharge-
       authorization blocked       -- for `:schedule-maintenance`, does
                                       the proposal's own `:value`
                                       declare `:discharge-authorize?
                                       true`? Authorizing an effluent
                                       discharge is this actor's other
                                       permanent scope boundary (see
                                       README `What this actor does NOT
                                       do`) -- HARD, PERMANENT,
                                       unconditional. NO phase and NO
                                       human approval can ever override
                                       this (see `pulppaper.phase`: this
                                       op is never a member of any
                                       phase's `:auto` set either -- two
                                       independent layers agree).
    5. Equipment not verified/
       registered                  -- for `:schedule-maintenance`,
                                       INDEPENDENTLY verify the
                                       referenced equipment's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`pulppaper.registry/equipment-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status. Grounded in this
                                       blueprint's own HARD invariant
                                       ('plant/batch record must be
                                       independently verified/
                                       registered before any action'):
                                       maintenance must never be
                                       scheduled against equipment
                                       whose own conditions have not
                                       actually been inspected or
                                       whose registration is not
                                       actually on file.
    6. Already scheduled           -- for `:schedule-maintenance`,
                                       refuses to schedule the SAME
                                       maintenance record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
    7. Batch not verified/
       registered                  -- for `:coordinate-shipment`,
                                       INDEPENDENTLY verify the
                                       referenced batch's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`pulppaper.registry/batch-
                                       ready?`) -- never trust the
                                       advisor's own rationale. Also
                                       part of the 'plant/batch record'
                                       HARD invariant: a batch's own
                                       verified/registered status is as
                                       much a ground-truth fact as an
                                       equipment unit's own.
    8. Shipment volume exceeded    -- for `:coordinate-shipment`,
                                       INDEPENDENTLY recompute whether
                                       the batch's own recorded
                                       `:shipped-volume-tonnes` plus
                                       the proposal's own claimed
                                       `:volume-tonnes` would exceed
                                       the batch's own recorded
                                       `:volume-tonnes`
                                       (`pulppaper.registry/shipment-
                                       volume-exceeded?`) -- ground
                                       truth from the batch's own
                                       permanent fields, never a
                                       self-reported volume claim.
    9. Invalid grade               -- for `:log-production-batch`, if
                                       the patch declares a `:grade`
                                       outside the closed known set
                                       (`pulppaper.registry/grade-
                                       valid?`), the batch record is
                                       rejected rather than let a
                                       fabricated grade through.
   10. Invalid brightness          -- for `:log-production-batch`, if
                                       the patch declares a
                                       `:brightness-percent` that is
                                       not a physically plausible
                                       ISO-brightness reading
                                       (`pulppaper.registry/brightness-
                                       valid?`), the batch record is
                                       rejected rather than let
                                       fabricated/sensor-error data
                                       through.
   11. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`) -- escalate to a human
                                       plant supervisor. SOFT: the
                                       human may approve."
  (:require [pulppaper.registry :as registry]
            [pulppaper.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-production-batch :schedule-maintenance
    :flag-safety-concern :coordinate-shipment})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct pulping/paper-
  machine-equipment-control or effluent-discharge-authorize effect."
  #{:batch/upsert :maintenance/schedule
    :safety-concern/flag :shipment/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns are the one op in this domain that always demands
  human eyes regardless of confidence."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- equipment-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct digester/paper-machine/effluent-treatment
  equipment control, a fabricated actuation effect) is this actor's
  central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :equipment-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は蒸解釜・抄紙機・排水処理設備の直接操作/排水放流の許可確定に該当する可能性があり、恒久的に禁止")}]))

(defn- discharge-authorize-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-maintenance` proposal
  whose own `:value` declares `:discharge-authorize? true` is
  attempting to authorize an effluent discharge directly -- this actor
  may only ever propose/schedule a DRAFT maintenance window, never
  authorize a real effluent-discharge event. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-maintenance)
             (true? (:discharge-authorize? (:value proposal))))
    [{:rule :discharge-authorize-blocked
      :detail "排水放流の許可(discharge-authorize)確定提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- equipment-not-verified-violations
  "For `:schedule-maintenance`, INDEPENDENTLY verify the referenced
  equipment exists and is both `:verified?` AND `:registered?` --
  never trust the advisor's own report. This is the HARD invariant
  ('plant/batch record must be independently verified/registered
  before any action')."
  [{:keys [op]} proposal st]
  (when (= op :schedule-maintenance)
    (let [equipment-id (:equipment-id (:value proposal))
          eq (and equipment-id (store/equipment-unit st equipment-id))]
      (when-not (and eq (registry/equipment-ready? eq))
        [{:rule :equipment-not-verified
          :detail (str equipment-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み設備記録が無い状態での保守作業予定提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-maintenance`, refuses to schedule the SAME
  maintenance record twice, off a dedicated `:scheduled?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-maintenance)
    (when (store/maintenance-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- batch-not-verified-violations
  "For `:coordinate-shipment`, INDEPENDENTLY verify the referenced
  batch exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report. Also part of the 'plant/batch
  record must be independently verified/registered before any action'
  HARD invariant."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [batch-id (:batch-id (:value proposal))
          b (and batch-id (store/batch st batch-id))]
      (when-not (and b (registry/batch-ready? b))
        [{:rule :batch-not-verified
          :detail (str batch-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みバッチ記録が無い状態での出荷調整提案")}]))))

(defn- shipment-volume-exceeded-violations
  "For `:coordinate-shipment`, INDEPENDENTLY recompute whether the
  batch's own recorded shipped-to-date volume plus the proposal's own
  claimed volume would exceed the batch's own recorded
  `:volume-tonnes` -- ground truth from the batch's own permanent
  fields, never a self-reported volume claim."
  [{:keys [op]} proposal st]
  (when (= op :coordinate-shipment)
    (let [{:keys [batch-id volume-tonnes]} (:value proposal)
          b (and batch-id (store/batch st batch-id))]
      (when (and b (registry/shipment-volume-exceeded? b volume-tonnes))
        [{:rule :shipment-volume-exceeded
          :detail (str batch-id " の記録済み生産量(" (:volume-tonnes b)
                       "トン)を、既存出荷実績(" (:shipped-volume-tonnes b 0.0)
                       "トン)+今回申請(" volume-tonnes "トン)が超過")}]))))

(defn- invalid-grade-violations
  "For `:log-production-batch`, if the patch declares a `:grade`
  outside the closed known set, reject rather than let a fabricated
  grade through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [grade (:grade (:value proposal))]
      (when (and (some? grade) (not (registry/grade-valid? grade)))
        [{:rule :invalid-grade
          :detail (str grade " は既知の grade 値ではない")}]))))

(defn- invalid-brightness-violations
  "For `:log-production-batch`, if the patch declares a
  `:brightness-percent` that is not a physically plausible ISO-
  brightness reading, reject rather than let fabricated/sensor-error
  data through."
  [{:keys [op]} proposal]
  (when (= op :log-production-batch)
    (let [bp (:brightness-percent (:value proposal))]
      (when (and (some? bp) (not (registry/brightness-valid? bp)))
        [{:rule :invalid-brightness
          :detail (str bp "% は物理的に妥当なISO白色度の範囲外")}]))))

(defn check
  "Censors a PulpPaperAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (equipment-control-blocked-violations proposal)
                           (discharge-authorize-blocked-violations request proposal)
                           (equipment-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (batch-not-verified-violations request proposal st)
                           (shipment-volume-exceeded-violations request proposal st)
                           (invalid-grade-violations request proposal)
                           (invalid-brightness-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
