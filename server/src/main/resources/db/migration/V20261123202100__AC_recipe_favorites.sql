-- Unified "want to eat" favourites across the three recipe spaces.
CREATE TABLE recipe_favorites (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('CATALOG', 'PERSONAL', 'HOUSEHOLD')),
    recipe_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, kind, recipe_id)
);

CREATE INDEX recipe_favorites_user_idx ON recipe_favorites(user_id, created_at DESC);
