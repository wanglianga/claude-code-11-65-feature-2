-- Tenure versioning, case geometry freezing, boundary overlap analysis, pixel
-- allocation / arbitration and the dispute desk.
--
-- Design baseline: a claim case is always scored against the parcel geometry that
-- was already APPROVED at intake. A later re-survey is appended as a new version and
-- may only trigger a follow-up review; it never silently rewrites a historical case.
-- All frozen rows and human rulings are append-only / immutable.

-- ---------------------------------------------------------------------------
-- 1. Parcel tenure version library
--    权属、边界来源、生效日期以及审批状态都留版本，审批与回填并发时仍可按
--    “受理时点”精确还原当时生效的权属版本。
-- ---------------------------------------------------------------------------

create table parcel_tenure_version (
    id                      bigserial primary key,
    parcel_id               bigint not null references parcel(id),
    version_no              int not null,
    owner_name              varchar(200) not null,
    owner_id_code           varchar(120),
    tenure_type             varchar(24) not null
                                check (tenure_type in
                                       ('SELF_OPERATED','CONTRACTED','TRANSFERRED','COLLECTIVE')),
    boundary_version_id     bigint not null references parcel_boundary_version(id),
    boundary_source         varchar(32) not null
                                check (boundary_source in
                                       ('REPORTED_SURVEY','AGRI_REGISTRY','RE_SURVEY','TRANSFER_DEED',
                                        'MANUAL_ADJUSTMENT')),
    source_doc_no           varchar(120),
    effective_from          date not null,
    effective_to            date,
    status                  varchar(24) not null default 'PENDING_APPROVAL'
                                check (status in ('PENDING_APPROVAL','APPROVED','REJECTED','SUPERSEDED')),
    approved_by             varchar(120),
    approved_at             timestamptz,
    observed_at             timestamptz not null,
    ingested_at             timestamptz not null,
    supersedes_tenure_id    bigint references parcel_tenure_version(id),
    note                    text,
    created_at              timestamptz not null default now(),
    unique (parcel_id, version_no)
);

create index idx_tenure_parcel on parcel_tenure_version(parcel_id, version_no desc);
create index idx_tenure_boundary on parcel_tenure_version(boundary_version_id);
create index idx_tenure_status on parcel_tenure_version(status, approved_at);

-- ---------------------------------------------------------------------------
-- 2. Case geometry freeze
--    案件受理时固化可用于评分的几何版本（含几何副本）。后续测绘 / 权属审批
--    并发发生时，评分运行仍读取本快照，claim_case.boundary_version_id 与之
--    保持一致；该行不可更新、不可删除。
-- ---------------------------------------------------------------------------

create table case_geometry_freeze (
    id                      bigserial primary key,
    case_id                 bigint not null unique references claim_case(id),
    tenure_version_id       bigint references parcel_tenure_version(id),
    boundary_version_id     bigint not null references parcel_boundary_version(id),
    owner_name              varchar(200),
    boundary_source         varchar(32),
    source_doc_no           varchar(120),
    scoring_cutoff          timestamptz not null,
    geometry_snapshot       jsonb not null,
    area_ha                 numeric(14,4) not null,
    policy_tolerance_pct    numeric(7,4) not null default 5.0,
    note                    text,
    frozen_at               timestamptz not null default now()
);

create index idx_freeze_boundary on case_geometry_freeze(boundary_version_id);

create or replace function block_case_geometry_freeze_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'case geometry freezes are immutable (% on %)', tg_op, tg_table_name;
end;
$$;

create trigger trg_case_geometry_freeze_immutable
    before update or delete on case_geometry_freeze
    for each row execute function block_case_geometry_freeze_mutation();

-- ---------------------------------------------------------------------------
-- 3. Boundary overlap analysis (overlap graph)
--    一次分析覆盖同一地块的新旧版本以及相邻地块边界，逐条保存相交面积、
--    双方占比与是否超过政策容差。
-- ---------------------------------------------------------------------------

create table boundary_overlap_analysis (
    id                          bigserial primary key,
    parcel_id                   bigint not null references parcel(id),
    base_boundary_version_id    bigint references parcel_boundary_version(id),
    comparison_scope            varchar(24) not null
                                    check (comparison_scope in ('PARCEL_VERSIONS','NEIGHBOURS','BOTH')),
    policy_tolerance_pct        numeric(7,4) not null default 5.0,
    status                      varchar(16) not null default 'COMPLETED',
    summary                     jsonb not null,
    created_by                  varchar(120),
    created_at                  timestamptz not null default now()
);

create index idx_overlap_analysis_parcel on boundary_overlap_analysis(parcel_id, created_at desc);

create table boundary_overlap_pair (
    id                          bigserial primary key,
    analysis_id                 bigint not null references boundary_overlap_analysis(id) on delete cascade,
    parcel_id_a                 bigint not null references parcel(id),
    boundary_version_id_a       bigint not null references parcel_boundary_version(id),
    parcel_id_b                 bigint not null references parcel(id),
    boundary_version_id_b       bigint not null references parcel_boundary_version(id),
    same_parcel                 boolean not null,
    intersection_area_ha        numeric(14,4) not null,
    area_a_ha                   numeric(14,4) not null,
    area_b_ha                   numeric(14,4) not null,
    overlap_pct_of_a            numeric(7,4) not null,
    overlap_pct_of_b            numeric(7,4) not null,
    exceeds_tolerance           boolean not null,
    intersection_geojson        jsonb,
    unique (analysis_id, boundary_version_id_a, boundary_version_id_b)
);

create index idx_overlap_pair_analysis on boundary_overlap_pair(analysis_id);
create index idx_overlap_pair_versions on boundary_overlap_pair(boundary_version_id_a, boundary_version_id_b);

-- ---------------------------------------------------------------------------
-- 4. Pixel allocator ledger
--    每个运行、每个像元的归属结论都留痕：正常归属 / 排除 / 待裁定。
--    超容差区域只能 PENDING_ARBITRATION，绝不允许并列归属两笔申请。
-- ---------------------------------------------------------------------------

create table pixel_allocation (
    id                      bigserial primary key,
    scene_id                bigint not null references satellite_scene(id),
    cell_id                 bigint not null references satellite_scene_cell(id),
    run_id                  bigint references feature_run(id) on delete set null,
    case_id                 bigint references claim_case(id),
    parcel_id               bigint not null references parcel(id),
    boundary_version_id     bigint not null references parcel_boundary_version(id),
    allocation_status       varchar(24) not null
                                check (allocation_status in
                                       ('ATTRIBUTED','EXCLUDED','PENDING_ARBITRATION')),
    rule_code               varchar(56) not null,
    overlap_analysis_id     bigint references boundary_overlap_analysis(id),
    dispute_id              bigint,
    ndvi                    numeric(8,5) not null,
    reason                  text,
    allocated_at            timestamptz not null default now()
);

create index idx_pixel_alloc_run on pixel_allocation(run_id);
create index idx_pixel_alloc_cell on pixel_allocation(scene_id, cell_id);
create index idx_pixel_alloc_status on pixel_allocation(allocation_status);
create index idx_pixel_alloc_dispute on pixel_allocation(dispute_id);

-- ---------------------------------------------------------------------------
-- 5. Dispute desk
--    人工裁定与撤回都只追加 boundary_dispute_ruling 记录；原裁定和原面积
--    结果（快照 id、面积）永久保留。
-- ---------------------------------------------------------------------------

create table boundary_dispute (
    id                                  bigserial primary key,
    dispute_no                          varchar(80) not null unique,
    case_id                             bigint references claim_case(id),
    parcel_id                           bigint not null references parcel(id),
    overlap_analysis_id                 bigint references boundary_overlap_analysis(id),
    overlap_pair_id                     bigint references boundary_overlap_pair(id),
    subject_boundary_version_id_a       bigint not null references parcel_boundary_version(id),
    subject_boundary_version_id_b       bigint not null references parcel_boundary_version(id),
    overlap_area_ha                     numeric(14,4) not null,
    tolerance_pct                       numeric(7,4) not null,
    status                              varchar(24) not null default 'OPEN'
                                            check (status in ('OPEN','RULED','WITHDRAWN','CLOSED')),
    reason                              text,
    opened_by                           varchar(120),
    opened_at                           timestamptz not null default now(),
    ruling                              varchar(24)
                                            check (ruling in
                                                   ('GRANT_TO_A','GRANT_TO_B','EXCLUDE_BOTH','RE_SURVEY_REQUIRED')),
    ruled_by                            varchar(120),
    ruled_at                            timestamptz,
    ruling_note                         text,
    withdrawn_by                        varchar(120),
    withdrawn_at                        timestamptz,
    withdrawal_note                     text,
    closed_at                           timestamptz
);

create index idx_dispute_case on boundary_dispute(case_id);
create index idx_dispute_status on boundary_dispute(status);
create index idx_dispute_versions
    on boundary_dispute(subject_boundary_version_id_a, subject_boundary_version_id_b);

alter table pixel_allocation
    add constraint fk_pixel_alloc_dispute
    foreign key (dispute_id) references boundary_dispute(id);

create table boundary_dispute_ruling (
    id                      bigserial primary key,
    dispute_id              bigint not null references boundary_dispute(id) on delete cascade,
    action                  varchar(16) not null check (action in ('RULE','WITHDRAW')),
    ruling                  varchar(24)
                                check (ruling in
                                       ('GRANT_TO_A','GRANT_TO_B','EXCLUDE_BOTH','RE_SURVEY_REQUIRED')),
    decided_by              varchar(120) not null,
    decided_at              timestamptz not null default now(),
    note                    text,
    retained_area_ha        numeric(14,4),
    retained_snapshot_id    bigint references scoring_snapshot(id),
    sampling_run_id         bigint references feature_run(id)
);

create index idx_ruling_dispute on boundary_dispute_ruling(dispute_id, decided_at);

create or replace function block_dispute_ruling_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'dispute rulings are append-only (% on %)', tg_op, tg_table_name;
end;
$$;

create trigger trg_dispute_ruling_immutable
    before update or delete on boundary_dispute_ruling
    for each row execute function block_dispute_ruling_mutation();

-- ---------------------------------------------------------------------------
-- 6. Re-sampling requests
--    裁定撤回后再次发起复核时，必须新建取样运行，同时登记原裁定与原面积
--    结果来源，保证回查链路完整。
-- ---------------------------------------------------------------------------

create table boundary_review_request (
    id                      bigserial primary key,
    dispute_id              bigint not null references boundary_dispute(id),
    case_id                 bigint not null references claim_case(id),
    sampling_run_id         bigint references feature_run(id),
    status                  varchar(24) not null default 'REQUESTED'
                                check (status in ('REQUESTED','RUNNING','COMPLETED','FAILED')),
    requested_by            varchar(120) not null,
    requested_at            timestamptz not null default now(),
    reason                  text,
    prior_ruling_id         bigint references boundary_dispute_ruling(id),
    prior_area_ha           numeric(14,4),
    prior_snapshot_id       bigint references scoring_snapshot(id),
    completed_at            timestamptz
);

create index idx_review_request_dispute on boundary_review_request(dispute_id, requested_at desc);
create index idx_review_request_run on boundary_review_request(sampling_run_id);

-- ---------------------------------------------------------------------------
-- 7. feature_run gains a dispute re-sampling trigger
-- ---------------------------------------------------------------------------

alter table feature_run drop constraint feature_run_trigger_type_check;
alter table feature_run add constraint feature_run_trigger_type_check
    check (trigger_type in
           ('CASE_SCORING','BACKFILL_REQUEST','IMPACT_ANALYSIS','REPRODUCTION','DISPUTE_RESAMPLING'));
