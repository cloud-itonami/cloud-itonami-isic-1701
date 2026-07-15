(ns pulppaper.registry
  "Pure-function domain logic for the pulp/paper mill plant-operations
  coordination actor -- equipment/batch verification, shipment-volume
  recompute, pulp/paper-grade validation, ISO-brightness plausibility
  validation, and draft maintenance-schedule/shipment-coordination
  record construction.

  This vertical has NO pre-existing `kotoba-lang/pulppaper`-style
  capability library to wrap (verified: no such repo exists, mirroring
  `cloud-itonami-isic-1610`'s own sawmilling vertical, docs/adr/0001-
  architecture.md Decision 1). The domain logic therefore lives here as
  pure functions, re-verified INDEPENDENTLY by `pulppaper.governor` --
  the same 'ground truth, not self-report' discipline every sibling
  actor's own registry establishes: never trust a proposal's own
  self-reported volume/status when the inputs needed to recompute it
  independently are already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real mill-operations system. It builds the DRAFT record
  a plant coordinator would keep (a scheduled maintenance window, a
  coordinated shipment), not the act of actuating digester/paper-
  machine/effluent-treatment equipment or dispatching a real freight
  carrier (this actor NEVER does either -- see README `What this actor
  does NOT do`).")

;; ----------------------------- constants -----------------------------

(def valid-grades
  "The closed set of pulp/paper/paperboard-grade values a production-
  batch record may declare, spanning both intermediate pulp grades
  (chemical/mechanical pulping outputs) and finished paper/paperboard
  grades. Anything else is a fabricated/unrecognized grade -- the
  governor HARD-holds rather than let an invented grade pass through."
  #{:bleached-kraft-pulp :unbleached-kraft-pulp :mechanical-pulp
    :dissolving-pulp :recycled-fiber-pulp
    :newsprint :kraft-paper :fine-paper :tissue :paperboard
    :corrugating-medium})

(def brightness-min-percent
  "Physical floor for an ISO-brightness reading (a pulp/paper sample
  reflects no light at 0%)."
  0.0)

(def brightness-max-percent
  "Physical ceiling for an ISO-brightness reading. ISO 2470 brightness
  is expressed as a percentage of the reflectance of a magnesium-oxide
  reference standard and cannot exceed 100% -- a reading above this is
  implausible sensor/instrument data, not a real batch."
  100.0)

;; ----------------------------- equipment checks -----------------------------

(defn equipment-verified?
  "Ground-truth check: has `equipment`'s own record been marked
  verified (i.e. it has actually been inspected/commissioned and
  registered in the SSoT, not merely referenced from an unverified
  maintenance request)? A pure predicate over the equipment's own
  permanent field -- no proposal inspection needed."
  [equipment]
  (true? (:verified? equipment)))

(defn equipment-registered?
  "Ground-truth check: does `equipment`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the plant's
  equipment registry)? Scheduling maintenance against equipment that
  is not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [equipment]
  (true? (:registered? equipment)))

(defn equipment-ready?
  "Combined ground-truth gate: the equipment must be both `verified?`
  AND `registered?` before ANY maintenance may be scheduled against
  it. Two independent facts on the equipment's own permanent record,
  neither inferred from the advisor's own rationale."
  [equipment]
  (and (equipment-verified? equipment) (equipment-registered? equipment)))

;; ----------------------------- batch checks -----------------------------

(defn batch-verified?
  "Ground-truth check: has `batch`'s own record been marked verified
  (i.e. its grade/volume/brightness claims have actually been
  QC-inspected, not merely logged from an unverified intake patch)?"
  [batch]
  (true? (:verified? batch)))

(defn batch-registered?
  "Ground-truth check: is `batch`'s own record on file in the plant's
  production ledger? Coordinating a shipment against a batch that is
  not on file and registered is the exact scope violation this
  actor's HARD invariant ('plant/batch record must be independently
  verified/registered before any action') exists to block."
  [batch]
  (true? (:registered? batch)))

(defn batch-ready?
  "Combined ground-truth gate: the batch must be both `verified?` AND
  `registered?` before ANY shipment may be coordinated against it."
  [batch]
  (and (batch-verified? batch) (batch-registered? batch)))

(defn shipment-volume-exceeded?
  "Ground-truth check for a `:coordinate-shipment` proposal:
  would `shipped-to-date-tonnes` + `new-volume-tonnes` exceed
  `batch`'s own recorded `:volume-tonnes` (the batch's own logged
  production volume)? Needs no proposal inspection or stored-verdict
  lookup -- its inputs are permanent fields already on the batch's
  own record, the same shape every sibling actor's own cost/total-
  matching check uses."
  [batch new-volume-tonnes]
  (let [capacity (:volume-tonnes batch)
        so-far (:shipped-volume-tonnes batch 0.0)]
    (and (number? capacity)
         (number? new-volume-tonnes)
         (> (+ (double so-far) (double new-volume-tonnes)) (double capacity)))))

(defn grade-valid?
  "Is `grade` one of the closed, known pulp/paper/paperboard-grade
  values? nil/blank is treated as invalid (a production-batch patch
  must declare a real grade, not omit it silently)."
  [grade]
  (contains? valid-grades grade))

(defn brightness-valid?
  "Is `percent` a physically plausible ISO-brightness reading? Rejects
  nil, non-numbers, negative values, and values beyond
  `brightness-max-percent` -- a fabricated or sensor-error reading,
  never let through as a real batch fact."
  [percent]
  (and (number? percent)
       (>= (double percent) brightness-min-percent)
       (<= (double percent) brightness-max-percent)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human plant supervisor's/shipping approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-maintenance
  "Validate + construct the MAINTENANCE-SCHEDULE DRAFT -- a proposed
  digester/paper-machine/effluent-treatment maintenance window against
  a verified, registered piece of equipment. Pure function -- does not
  actuate pulping/paper-machine/effluent-treatment equipment or
  execute any maintenance; it builds the RECORD a plant coordinator
  would keep. `pulppaper.governor` independently re-verifies the
  equipment's own verified/registered ground truth, and permanently
  blocks any attempt to set `:discharge-authorize? true` on a
  maintenance proposal (see README `Actuation`), before this is ever
  allowed to commit."
  [maintenance-id equipment-id sequence]
  (when-not (and maintenance-id (not= maintenance-id ""))
    (throw (ex-info "maintenance: maintenance_id required" {})))
  (when-not (and equipment-id (not= equipment-id ""))
    (throw (ex-info "maintenance: equipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance: sequence must be >= 0" {})))
  (let [maintenance-number (str "MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-schedule-draft"
                "maintenance_id" maintenance-id
                "equipment_id" equipment-id
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceSchedule" maintenance-number maintenance-number)}))

(defn register-shipment
  "Validate + construct the SHIPMENT-COORDINATION DRAFT -- a proposed
  outbound pulp/paper/paperboard shipment against a verified,
  registered production batch. Pure function -- does not dispatch any
  real freight carrier; it builds the RECORD a plant coordinator would
  keep. `pulppaper.governor` independently re-verifies the shipment's
  own claimed volume against `shipment-volume-exceeded?`, before this
  is ever allowed to commit."
  [shipment-id sequence]
  (when-not (and shipment-id (not= shipment-id ""))
    (throw (ex-info "shipment: shipment_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "shipment: sequence must be >= 0" {})))
  (let [shipment-number (str "SHP-" (zero-pad sequence 6))
        record {"record_id" shipment-number
                "kind" "shipment-coordination-draft"
                "shipment_id" shipment-id
                "immutable" true}]
    {"record" record "shipment_number" shipment-number
     "certificate" (unsigned-certificate "ShipmentCoordination" shipment-number shipment-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
