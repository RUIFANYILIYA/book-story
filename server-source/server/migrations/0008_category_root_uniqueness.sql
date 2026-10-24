-- 0008_category_root_uniqueness — make the root level obey the same unique rule as the rest.
--
-- The original index was on (parent_id, name_norm), and SQLite treats NULLs as distinct in a
-- unique index. A root category has parent_id IS NULL, so "科幻" could be created twice at the top
-- level while the same name was correctly rejected one level down — the least consistent place to
-- have the hole, since roots are what a user actually sees.

DROP INDEX IF EXISTS ux_categories_parent_name;

CREATE UNIQUE INDEX ux_categories_parent_name
  ON categories (ifnull(parent_id, ''), name_norm);
