(ns pulppaper.render-html
  "Build-time HTML renderer for docs/samples/operator-console.html.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300).
  Drives the REAL actor stack (pulppaper.operation -> pulppaper.governor -> pulppaper.store).
  No invented numbers, no timestamps, byte-identical across reruns."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [pulppaper.store :as store]
            [pulppaper.operation :as op]
            [pulppaper.phase :as phase]
            [pulppaper.governor :as governor]
            [langgraph.graph :as g]))

(def ^:private coordinator {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives the real OperationActor StateGraph through a scenario built
  directly from `pulppaper.store/sample-data!`'s seed and
  `pulppaper.governor`'s actual rules (this repo's own `pulppaper.sim`
  was run and cross-checked against `pulppaper.store`'s real seed ids
  and `pulppaper.governor`'s real rule names before this namespace was
  written -- every id/op/rule name below is verified real, none
  invented; this mirrors `pulppaper.sim`'s scenario rather than calling
  its `-main` directly, to keep this namespace's demo self-contained
  and its output free of `println` noise):

    1. `:log-production-batch` batch-001 with a clean grade patch --
       `pulppaper.phase`'s ONLY phase-3 auto-eligible op, governor-clean
       -> auto-commits, no human involved.
    2. `:flag-safety-concern` concern-1 on equip-001 -- `:stake
       :coordination/safety-concern` is the one member of
       `pulppaper.governor/high-stakes`, so this ALWAYS escalates
       regardless of confidence or phase -> human plant-supervisor
       approval -> commit.
    3. `:schedule-maintenance` mnt-1 on equip-001 (verified+registered
       digester, `:discharge-authorize? false`) -- clean, but
       `:schedule-maintenance` is never a member of any phase's `:auto`
       set (see `pulppaper.phase` ns docstring) -> escalates -> human
       approval -> commit.
    4. `:coordinate-shipment` ship-1 on batch-001, 50.0 tonnes (well
       within batch-001's own recorded 500.0t volume vs 100.0t already
       shipped) -- clean, but `:coordinate-shipment` is likewise never
       auto-eligible -> escalates -> human approval -> commit.

    Then four DISTINCT HARD-hold scenarios, each independently
    re-derived by the governor from the store's own ground-truth
    fields -- none ever reaches a human:

    5. `:schedule-maintenance` mnt-2 on equip-002 -- equip-002 is
       seeded `:verified? false :registered? false` (an
       UNVERIFIED/unregistered effluent-treatment-plant unit) -> HARD
       hold, rule `:equipment-not-verified`.
    6. `:coordinate-shipment` ship-2 on batch-003 -- batch-003 is
       seeded `:verified? false :registered? false` -> HARD hold, rule
       `:batch-not-verified`.
    7. `:coordinate-shipment` ship-3 on batch-002, 10.0 tonnes --
       batch-002's own recorded volume is 80.0t and its own recorded
       `:shipped-volume-tonnes` is already 75.0t, so 75.0+10.0 > 80.0
       -> HARD hold, rule `:shipment-volume-exceeded`.
    8. `:schedule-maintenance` mnt-3 on equip-001 with
       `:discharge-authorize? true` -- attempts to authorize a real
       effluent discharge via a maintenance proposal, permanently and
       unconditionally blocked -> HARD hold, rule
       `:discharge-authorize-blocked`.

  Returns the seeded `db` (a `pulppaper.store/MemStore`) after the run,
  so `render` can read every value straight off it."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (exec! actor "t1" {:op :log-production-batch :effect :propose :subject "batch-001"
                        :patch {:grade :bleached-kraft-pulp :last-assessed "2026-07-15"}})

    (exec! actor "t2" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                        :value {:equipment-id "equip-001" :severity :moderate
                                :description "蒸解釜付近で薬液臭気の上昇"}})
    (approve! actor "t2")

    (exec! actor "t3" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                        :value {:equipment-id "equip-001" :maintenance-type :digester-inspection
                                :scheduled-date "2026-08-01" :discharge-authorize? false}})
    (approve! actor "t3")

    (exec! actor "t4" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                        :value {:batch-id "batch-001" :volume-tonnes 50.0
                                :destination "buyer-mill-north"}})
    (approve! actor "t4")

    (exec! actor "t5" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                        :value {:equipment-id "equip-002" :maintenance-type :effluent-treatment-service
                                :scheduled-date "2026-08-01" :discharge-authorize? false}})

    (exec! actor "t6" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                        :value {:batch-id "batch-003" :volume-tonnes 10.0
                                :destination "buyer-mill-south"}})

    (exec! actor "t7" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                        :value {:batch-id "batch-002" :volume-tonnes 10.0
                                :destination "buyer-mill-east"}})

    (exec! actor "t8" {:op :schedule-maintenance :effect :propose :subject "mnt-3"
                        :value {:equipment-id "equip-001" :maintenance-type :effluent-discharge-event
                                :scheduled-date "2026-09-01" :discharge-authorize? true}})

    db))

;; ----------------------------- render helpers -----------------------------

(defn- esc
  "Minimal HTML-escape -- every rendered string passes through this."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- last-fact-for
  "The most recent ledger fact for `subject-id`, off the real
  subject-key field this repo's `commit-fact`/`hold-fact` records use:
  `:subject` (see `pulppaper.operation/commit-fact` and
  `pulppaper.governor/hold-fact`, both `:subject (:subject request)`)."
  [ledger subject-id]
  (last (filter #(= subject-id (:subject %)) ledger)))

(defn- status-cell
  "[css-class label] for the last known ledger fact of a subject --
  the same cond pattern used fleet-wide."
  [fact]
  (cond
    (nil? fact)                                 ["muted" "in progress"]
    (= :committed (:t fact))                    ["ok" "committed"]
    (= :approval-granted (:t fact))              ["ok" "approval-granted"]
    (= :governor-hold (:t fact))                 ["err" (str "governor-hold: " (str/join "," (map name (:basis fact))))]
    (= :approval-rejected (:t fact))             ["err" "approval-rejected"]
    (= :approval-requested (:t fact))            ["warn" "approval-requested"]
    :else                                        ["muted" "in progress"]))

(defn- batches-table [db]
  (let [batches (store/all-batches db)
        ledger (store/ledger db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>id</th><th>grade</th><th>species</th><th>volume (t)</th><th>brightness (%)</th>\n"
     "<th>verified?</th><th>registered?</th><th>shipped (t)</th><th>status</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [b batches
            :let [fact (last-fact-for ledger (:id b))
                  [cls label] (status-cell fact)]]
        (str "<tr>"
             "<td><code>" (esc (:id b)) "</code></td>"
             "<td><code>" (esc (:grade b)) "</code></td>"
             "<td>" (esc (:species b)) "</td>"
             "<td>" (esc (:volume-tonnes b)) "</td>"
             "<td>" (esc (:brightness-percent b)) "</td>"
             "<td>" (if (:verified? b) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if (:registered? b) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (esc (:shipped-volume-tonnes b)) "</td>"
             "<td class=\"" cls "\">" (esc label) "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- equipment-table [db]
  (let [equipment (store/all-equipment db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>id</th><th>kind</th><th>verified?</th><th>registered?</th>\n"
     "<th>last maintenance</th><th>last scheduled maintenance</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [e equipment]
        (str "<tr>"
             "<td><code>" (esc (:id e)) "</code></td>"
             "<td><code>" (esc (:kind e)) "</code></td>"
             "<td>" (if (:verified? e) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if (:registered? e) "yes" "<span class=\"critical\">no</span>") "</td>"
             "<td>" (if-let [d (:last-maintenance-date e)] (esc d) "&mdash;") "</td>"
             "<td>" (if-let [d (:last-scheduled-maintenance-date e)] (esc d) "&mdash;") "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- committed-records-table [db]
  (let [maintenances (store/maintenance-history db)
        shipments (store/shipment-history db)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>record_id</th><th>kind</th><th>maintenance_id / shipment_id</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [r (concat maintenances shipments)]
        (str "<tr>"
             "<td><code>" (esc (get r "record_id")) "</code></td>"
             "<td>" (esc (get r "kind")) "</td>"
             "<td><code>" (esc (or (get r "maintenance_id") (get r "shipment_id"))) "</code></td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- action-gate-table
  "Static op-contract description, sourced from the real
  `pulppaper.phase/phases` (phase 3, this actor's `default-phase`) and
  `pulppaper.governor/high-stakes` -- not invented, just rendered."
  []
  (let [ph (get phase/phases phase/default-phase)]
    (str
     "<table>\n<thead><tr>\n"
     "<th>op</th><th>phase-" phase/default-phase " write allowed?</th><th>auto-eligible?</th><th>always escalates (high-stakes)?</th>\n"
     "</tr></thead>\n<tbody>\n"
     (str/join
      "\n"
      (for [op (sort phase/write-ops)]
        (str "<tr>"
             "<td><code>" (esc op) "</code></td>"
             "<td>" (if (contains? (:writes ph) op) "yes" "<span class=\"warn\">no</span>") "</td>"
             "<td>" (if (contains? (:auto ph) op) "<span class=\"ok\">yes</span>" "no") "</td>"
             "<td>" (if (contains? governor/high-stakes op) "<span class=\"critical\">yes</span>" "no") "</td>"
             "</tr>")))
     "\n</tbody></table>")))

(defn- audit-ledger-table [db]
  (str
   "<table>\n<thead><tr>\n"
   "<th>t</th><th>op</th><th>subject</th><th>disposition</th><th>basis / rule</th>\n"
   "</tr></thead>\n<tbody>\n"
   (str/join
    "\n"
    (for [f (store/ledger db)]
      (str "<tr>"
           "<td>" (esc (:t f)) "</td>"
           "<td><code>" (esc (:op f)) "</code></td>"
           "<td><code>" (esc (:subject f)) "</code></td>"
           "<td class=\""
           (case (:disposition f) :commit "ok" :hold "err" "muted")
           "\">" (esc (:disposition f)) "</td>"
           "<td>" (if (seq (:basis f))
                    (str/join ", " (map (comp esc name) (:basis f)))
                    "&mdash;")
           "</td>"
           "</tr>")))
   "\n</tbody></table>"))

(def ^:private css
  "table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ok { color: #137a3f; }
body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }
header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }
th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }
h2 { margin-top: 0; font-size: 15px; }
.warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }
main { max-width: 980px; margin: 24px auto; padding: 0 20px; }
header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }
.muted { color: #888; font-size: 13px; }
.critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }
.card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }
th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }
code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }")

(defn render [db]
  (str
   "<!doctype html>\n"
   "<html lang=\"ja\">\n<head>\n<meta charset=\"utf-8\">\n"
   "<title>pulppaper.render-html -- Pulp &amp; Paper Plant Operations Governor operator console</title>\n"
   "<style>"
   (jp-go-dds.skin/dds+skin)
   "</style>\n"
   "</head>\n<body>\n"
   "<header class=\"bar\"><h1>Pulp &amp; Paper Plant Operations Governor -- Operator Console</h1>"
   "<span class=\"badge\">ISIC 1701 &middot; phase " phase/default-phase " (" (:label (get phase/phases phase/default-phase)) ")</span>"
   "</header>\n"
   "<main>\n"
   "<div class=\"card\">\n<h2>Production batches</h2>\n" (batches-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Equipment</h2>\n" (equipment-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Committed draft records (maintenance-schedule / shipment-coordination drafts)</h2>\n" (committed-records-table db) "\n</div>\n"
   "<div class=\"card\">\n<h2>Action gate (pulppaper.phase &middot; pulppaper.governor/high-stakes)</h2>\n" (action-gate-table) "\n</div>\n"
   "<div class=\"card\">\n<h2>Audit ledger</h2>\n" (audit-ledger-table db) "\n</div>\n"
   "</main>\n"
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
