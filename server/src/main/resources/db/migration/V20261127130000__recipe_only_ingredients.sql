-- Recipe-only (descriptive) ingredients live beside tracked recipe_ingredients.
-- Existing recipes intentionally receive an empty array; no historical row is rewritten.
ALTER TABLE recipes
    ADD COLUMN recipe_only_ingredients jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE recipes
    ADD CONSTRAINT recipes_recipe_only_ingredients_check
        CHECK (jsonb_typeof(recipe_only_ingredients) = 'array');
