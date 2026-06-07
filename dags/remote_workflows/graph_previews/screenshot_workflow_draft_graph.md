# screenshot_workflow_draft Airflow Graph Preview

Source config: `dags/remote_workflows/screenshot_workflow_draft.json`

The workflow parses successfully for `dev`, `qa`, `prod`, and `dr`.

- `dev`: 38 logical tasks, 10 task groups.
- `qa`, `prod`, `dr`: 44 logical tasks, 13 task groups.
- Airflow creates one remote operation per logical task and host, named like `run__{task_id}__{host}`.

## Start DAG, Collapsed TaskGroup View

`qa`, `prod`, and `dr` include all offer groups shown below. `dev` only includes the `_1` offer groups because the draft JSON defines only one host for each offer name in `dev`.

```mermaid
flowchart TD
  start((start)) --> grp_db

  subgraph grp_db["grp_db"]
    arb_service["arb_service"] --> sync_a["sync_a"]
    arb_service --> sync_b["sync_b"]
  end

  grp_db --> grp_trading1
  subgraph grp_trading1["grp_trading1"]
    drtp1["drtp1"] --> trade_a["trade_a"]
    drtp1 --> ptmq_a["ptmq_a"]
    drtp1 --> trmq_a["trmq_a"]
    drtp1 --> phmq_a["phmq_a"]
    drtp1 --> push_a["push_a"]
    drtp1 --> tr_mng1["tr_mng1"]
    drtp1 --> tds1["tds1"]
    drtp1 --> portal1["portal1"]
  end

  grp_trading1 --> grp_trading2
  subgraph grp_trading2["grp_trading2"]
    drtp2["drtp2"] --> trade_b["trade_b"]
    drtp2 --> ptmq_b["ptmq_b"]
    drtp2 --> trmq_b["trmq_b"]
    drtp2 --> phmq_b["phmq_b"]
    drtp2 --> push_b["push_b"]
    drtp2 --> tr_mng2["tr_mng2"]
    drtp2 --> tds2["tds2"]
    drtp2 --> portal2["portal2"]
  end

  grp_trading2 --> grp_lv2_1
  subgraph grp_lv2_1["grp_lv2_1"]
    sybase_drtp_1["sybase_drtp_1"] --> sybase_dms_1["sybase_dms_1"]
    sybase_drtp_1 --> risksvr_1["risksvr_1"]
  end

  grp_trading2 --> grp_lv2_2
  subgraph grp_lv2_2["grp_lv2_2"]
    sybase_drtp_2["sybase_drtp_2"] --> sybase_dms_2["sybase_dms_2"]
    sybase_drtp_2 --> monsvr_2["monsvr_2"]
  end

  grp_db --> grp_risk
  subgraph grp_risk["grp_risk"]
    risk_a["risk_a"] --> risk_b["risk_b"]
  end

  grp_db --> zk
  subgraph zk["zk"]
    zk_1["zk_1"]
    zk_2["zk_2"]
    zk_3["zk_3"]
  end

  grp_db --> grp_offer_dce_1
  grp_db --> grp_offer_dce_2
  grp_db --> grp_offer_insfe_1
  grp_db --> grp_offer_insfe_2
  grp_db --> grp_offer_cex_1
  grp_db --> grp_offer_cex_2

  subgraph grp_offer_dce_1["grp_offer_dce_1"]
    offer_1_dce_drtp["offer_1_dce_drtp"] --> offer_1_dce_dms["offer_1_dce_dms"]
  end
  subgraph grp_offer_dce_2["grp_offer_dce_2"]
    offer_2_dce_drtp["offer_2_dce_drtp"] --> offer_2_dce_dms["offer_2_dce_dms"]
  end
  subgraph grp_offer_insfe_1["grp_offer_insfe_1"]
    offer_1_insfe_drtp["offer_1_insfe_drtp"] --> offer_1_insfe_dms["offer_1_insfe_dms"]
  end
  subgraph grp_offer_insfe_2["grp_offer_insfe_2"]
    offer_2_insfe_drtp["offer_2_insfe_drtp"] --> offer_2_insfe_dms["offer_2_insfe_dms"]
  end
  subgraph grp_offer_cex_1["grp_offer_cex_1"]
    offer_1_cex_drtp["offer_1_cex_drtp"] --> offer_1_cex_dms["offer_1_cex_dms"]
  end
  subgraph grp_offer_cex_2["grp_offer_cex_2"]
    offer_2_cex_drtp["offer_2_cex_drtp"] --> offer_2_cex_dms["offer_2_cex_dms"]
  end
```

## Stop DAG, Collapsed TaskGroup View

No explicit `stop_depends_on` graph is defined in this draft, so the stop DAG uses the reversed start graph.

```mermaid
flowchart TD
  start((start)) --> grp_lv2_1
  start --> grp_lv2_2
  start --> grp_risk
  start --> zk
  start --> grp_offer_dce_1
  start --> grp_offer_dce_2
  start --> grp_offer_insfe_1
  start --> grp_offer_insfe_2
  start --> grp_offer_cex_1
  start --> grp_offer_cex_2

  grp_lv2_1 --> grp_trading2
  grp_lv2_2 --> grp_trading2
  grp_trading2 --> grp_trading1
  grp_trading1 --> grp_db

  grp_risk --> grp_db
  zk --> grp_db
  grp_offer_dce_1 --> grp_db
  grp_offer_dce_2 --> grp_db
  grp_offer_insfe_1 --> grp_db
  grp_offer_insfe_2 --> grp_db
  grp_offer_cex_1 --> grp_db
  grp_offer_cex_2 --> grp_db
```

## Status DAG

The status DAG has no workflow dependency ordering. All active logical tasks for the selected environment are independently gated by the Airflow UI selection parameters, then each task fans out to its host operation tasks.
