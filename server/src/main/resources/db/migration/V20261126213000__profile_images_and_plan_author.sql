-- Profile images for personal recipes and the member who last edited a plan slot.
ALTER TABLE user_recipes ADD COLUMN image_id UUID REFERENCES media(id);
ALTER TABLE plan_slots ADD COLUMN author_id UUID REFERENCES users(id);

CREATE INDEX idx_user_recipes_image ON user_recipes(image_id);
CREATE INDEX idx_plan_slots_author ON plan_slots(author_id);
