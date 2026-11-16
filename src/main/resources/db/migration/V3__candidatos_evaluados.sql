-- Candidatos que el motor evaluó en cada decisión, con su puntaje desglosado o el motivo del descarte (Lote 5).
-- Antes solo se guardaba cuántos eran (evaluated_candidates_count), así que GET /history no podía mostrar por qué se
-- eligió a un conductor frente a los demás. rank_position es el orden del motor (mejor puntaje primero).
CREATE TABLE IF NOT EXISTS allocation_decision_candidates (
    decision_id                  BIGINT           NOT NULL REFERENCES allocation_decisions (id) ON DELETE CASCADE,
    rank_position                INTEGER          NOT NULL,
    driver_id                    VARCHAR(255),
    driver_name                  VARCHAR(255),
    vehicle_plate                VARCHAR(255),
    truck_type                   VARCHAR(60),
    eligible                     BOOLEAN          NOT NULL,
    score                        DOUBLE PRECISION,
    proximity_points             DOUBLE PRECISION,
    capacity_points              DOUBLE PRECISION,
    rating_points                DOUBLE PRECISION,
    deadhead_distance_km         DOUBLE PRECISION,
    capacity_utilization_percent DOUBLE PRECISION,
    reason                       VARCHAR(500),
    PRIMARY KEY (decision_id, rank_position)
);
