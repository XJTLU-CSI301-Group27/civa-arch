-- Personal recipes: owned by the user, free-text ingredients, independent of households.
CREATE TABLE user_recipes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    category VARCHAR(40) NOT NULL DEFAULT '',
    description VARCHAR(2000) NOT NULL DEFAULT '',
    emoji VARCHAR(16),
    steps JSONB NOT NULL CHECK (jsonb_typeof(steps) = 'array' AND jsonb_array_length(steps) >= 1),
    ingredients JSONB NOT NULL CHECK (jsonb_typeof(ingredients) = 'array' AND jsonb_array_length(ingredients) >= 1),
    methods TEXT[] NOT NULL DEFAULT '{}',
    flavors TEXT[] NOT NULL DEFAULT '{}',
    meal_types TEXT[] NOT NULL DEFAULT '{}',
    estimated_minutes INTEGER CHECK (estimated_minutes IS NULL OR estimated_minutes > 0),
    servings NUMERIC(18, 3) CHECK (servings IS NULL OR servings > 0),
    serving_size VARCHAR(60),
    kcal NUMERIC(18, 3) CHECK (kcal IS NULL OR kcal >= 0),
    protein NUMERIC(18, 3) CHECK (protein IS NULL OR protein >= 0),
    carbs NUMERIC(18, 3) CHECK (carbs IS NULL OR carbs >= 0),
    fat NUMERIC(18, 3) CHECK (fat IS NULL OR fat >= 0),
    protein_per100g NUMERIC(18, 3) CHECK (protein_per100g IS NULL OR protein_per100g >= 0),
    carbs_per100g NUMERIC(18, 3) CHECK (carbs_per100g IS NULL OR carbs_per100g >= 0),
    fat_per100g NUMERIC(18, 3) CHECK (fat_per100g IS NULL OR fat_per100g >= 0),
    origin_household_recipe_id UUID,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX user_recipes_user_idx ON user_recipes(user_id, updated_at DESC, id);
CREATE INDEX user_recipes_name_idx ON user_recipes(name);
