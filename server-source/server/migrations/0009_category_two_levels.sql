-- 0009_category_two_levels — shelves nest exactly one level, the way the app draws them.
--
-- The app renders a shelf and the sub-shelves inside it, and nothing below that. The server used
-- to accept any depth, so a third level could be created and then be invisible on every client:
-- data that exists but cannot be shown is worse than a rejected write. This migration flattens
-- whatever is already there and makes the rule a database constraint rather than a convention.

-- 1. Anything three or more levels deep is re-parented to its root ancestor.
--    A path is /<root>/<child>/<grandchild>/..., so the root id is the first segment after the
--    leading slash. Depth is the slash count: 2 for a root, 3 for a sub-shelf, 4+ for deeper.
UPDATE categories
   SET parent_id = substr(path, 2, instr(substr(path, 2), '/') - 1),
       updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
 WHERE (length(path) - length(replace(path, '/', ''))) >= 4;

-- 2. Rebuild every path from the tree as it now stands. Browse filters scan the stored path, so a
--    stale one would leave the flattened sub-shelves unreachable.
WITH RECURSIVE tree(id, rebuilt) AS (
  SELECT id, '/' || id || '/'
    FROM categories
   WHERE parent_id IS NULL
  UNION ALL
  SELECT c.id, tree.rebuilt || c.id || '/'
    FROM categories c
    JOIN tree ON c.parent_id = tree.id
)
UPDATE categories
   SET path = (SELECT rebuilt FROM tree WHERE tree.id = categories.id)
 WHERE EXISTS (SELECT 1 FROM tree WHERE tree.id = categories.id);

-- 3. Backstop: the repository already refuses a third level with a readable error, but a trigger
--    keeps the invariant for any future writer (import, CLI, a route that forgets the check).
CREATE TRIGGER trg_categories_depth_insert
BEFORE INSERT ON categories
WHEN NEW.parent_id IS NOT NULL
BEGIN
  SELECT RAISE(ABORT, 'category_depth_exceeded: a sub-shelf cannot contain another shelf')
   WHERE (SELECT parent_id FROM categories WHERE id = NEW.parent_id) IS NOT NULL;
END;

CREATE TRIGGER trg_categories_depth_update
BEFORE UPDATE OF parent_id ON categories
WHEN NEW.parent_id IS NOT NULL
BEGIN
  SELECT RAISE(ABORT, 'category_depth_exceeded: a sub-shelf cannot contain another shelf')
   WHERE (SELECT parent_id FROM categories WHERE id = NEW.parent_id) IS NOT NULL
      OR EXISTS (SELECT 1 FROM categories WHERE parent_id = NEW.id);
END;
