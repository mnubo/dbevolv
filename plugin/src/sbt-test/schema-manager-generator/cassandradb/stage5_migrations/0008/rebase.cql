
CREATE TABLE IF NOT EXISTS kv (
    k TEXT,
    v TEXT,
    PRIMARY KEY ((k))
);

CREATE TABLE IF NOT EXISTS shouldnotbehere (
    k TEXT,
    v TEXT,
    PRIMARY KEY ((k))
);

